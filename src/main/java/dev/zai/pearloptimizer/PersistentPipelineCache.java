package dev.zai.pearloptimizer;

import com.google.gson.Gson;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent on-disk extension of the in-memory {@code SharedPipelineCache}.
 *
 * <p>The shared {@code VkPipelineCache} dies with the process, so the driver
 * has to redo the expensive preparation of every pipeline on every launch. This
 * module serializes the cache with {@code vkGetPipelineCacheData} at device
 * close and feeds the validated blob back through {@code pInitialData} of
 * {@code vkCreatePipelineCache} on the next launch, as recommended by Khronos.</p>
 *
 * <p>File layout (versioned by {@link #PIPELINE_CACHE_FORMAT}, independent from
 * the shader cache format): {@code pc-&lt;sha256[0..16] of device identity&gt;.bin}
 * holds the payload exactly as returned by {@code vkGetPipelineCacheData}; the
 * sidecar {@code .meta.json} holds format, device identity, size, payload
 * SHA-256 and a timestamp. The meta file is authoritative: a payload without a
 * matching meta (for example after a torn write) is a plain miss.</p>
 *
 * <p>Load order is strictly: meta first, then payload, then the embedded Vulkan
 * pipeline cache header ({@code headerSize == 32}, {@code headerVersion == 1},
 * vendor/device/UUID), then the payload SHA-256. Anything that fails, including
 * any IO error or unexpected throwable, is a cache miss and never an error; an
 * invalid file is deliberately left in place and will be overwritten by a
 * successful save of the same session (a transient disk/antivirus glitch must
 * not destroy a potentially usable cache).</p>
 *
 * <p>Per the Vulkan specification an implementation may either reject
 * incompatible cache data (any result other than VK_SUCCESS) or silently start
 * from an empty cache. The result of {@code vkCreatePipelineCache} is therefore
 * never interpreted: on any non-success the cache is immediately recreated with
 * {@code initialDataSize = 0}, which is byte-for-byte the vanilla path.</p>
 *
 * <p>Saving happens only in {@code VulkanDevice.close()} (via
 * {@code VulkanDeviceMixin}), only after at least one pipeline compile went
 * through the cache, and never inside the frame loop. Every entry point is
 * wrapped so that no throwable can ever break device teardown.</p>
 */
public final class PersistentPipelineCache {

    /**
     * Version of the pipeline-cache file layout. Independent of
     * {@code ShaderCacheManager.CACHE_FORMAT}: changing this
     * value only invalidates pipeline-cache files, never shader-cache entries.
     */
    static final int PIPELINE_CACHE_FORMAT = 1;

    /** {@code VkPipelineCacheHeaderVersionOne}: 4 + 4 + 4 + 4 + 16 bytes. */
    private static final int HEADER_SIZE = 32;
    private static final int HEADER_VERSION_ONE = 1;
    private static final int UUID_SIZE = 16;

    /** Applied payload limits: nothing smaller than the header can be valid; larger blobs are never read or written. */
    private static final long MAX_PAYLOAD_BYTES = 512L * 1024L * 1024L;
    /** Housekeeping cap for cache/pearl-optimizer/pipeline-cache/ (own pc-*.bin files only). */
    private static final long DIRECTORY_CAP_BYTES = 512L * 1024L * 1024L;

    private static final String FILE_PREFIX = "pc-";
    private static final String BIN_SUFFIX = ".bin";
    private static final String META_SUFFIX = ".meta.json";

    /** Per-device session state: identity used for load/save and whether the cache saw at least one compile. */
    private static final ConcurrentHashMap<Long, CacheSession> SESSIONS = new ConcurrentHashMap<>();
    /** Exactly one start line per session, regardless of how many devices or loads happen. */
    private static final AtomicBoolean START_REPORTED = new AtomicBoolean(false);
    /** At most one warning per session. */
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    private static final Gson GSON = new Gson();

    /** Test hook: redirects the cache directory without a Fabric runtime. */
    private static volatile Path testCacheRoot;

    private PersistentPipelineCache() {
    }

    /** Identity fields the file name and validation are derived from (Khronos: desirably pipelineCacheUUID). */
    record DeviceIdentity(int vendorID, int deviceID, int driverVersion, byte[] pipelineCacheUUID) {
        /** Canonical string hashed into the file name; unsigned rendering of the uint32 fields. */
        String identityString() {
            return "vendorID=" + (vendorID & 0xFFFFFFFFL)
                + ";deviceID=" + (deviceID & 0xFFFFFFFFL)
                + ";driverVersion=" + (driverVersion & 0xFFFFFFFFL)
                + ";pipelineCacheUUID=" + uuidHex();
        }

        String uuidHex() {
            return PearlUtil.hex(pipelineCacheUUID);
        }
    }

    /** Outcome of the load attempt; {@code payload} is non-null only for {@code loaded}. */
    record LoadOutcome(byte[] payload, String state, int sizeBytes) {
        static final LoadOutcome DISABLED = new LoadOutcome(null, "disabled", 0);
        static final LoadOutcome NO_IDENTITY = new LoadOutcome(null, "no-identity", 0);
        static final LoadOutcome FRESH = new LoadOutcome(null, "fresh", 0);
        static final LoadOutcome INVALID = new LoadOutcome(null, "invalid", 0);
        static final LoadOutcome ERROR = new LoadOutcome(null, "error", 0);
    }

    /** Sidecar metadata parsed with Gson; uint32 fields are held as long to keep unsigned values intact. */
    private static final class MetaJson {
        int format;
        long vendorID;
        long deviceID;
        long driverVersion;
        String pipelineCacheUUID;
        long size;
        String payloadSha256;
        long timestamp;
    }

    private static final class CacheSession {
        final DeviceIdentity identity;
        volatile boolean used;

        CacheSession(DeviceIdentity identity) {
            this.identity = identity;
        }
    }

    // ----------------------------------------------------------------------
    // Device identity probe (one-shot per device; create() runs exactly once
    // per device inside SharedPipelineCache.computeIfAbsent, so no extra
    // caching is needed here)
    // ----------------------------------------------------------------------

    static DeviceIdentity probeIdentity(VkDevice device) {
        try {
            if (!PearlConfig.get().persistentPipelineCache) {
                return null;
            }
            VkPhysicalDevice physical = device.getPhysicalDevice();
            if (physical == null || physical.address() == 0L) {
                return null;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
                VK12.vkGetPhysicalDeviceProperties(physical, props);
                byte[] uuid = new byte[UUID_SIZE];
                props.pipelineCacheUUID().get(0, uuid);
                return new DeviceIdentity(props.vendorID(), props.deviceID(), props.driverVersion(), uuid);
            }
        } catch (Throwable t) {
            warnOnce("Pipeline cache disk: identity probe failed, disk persistence off for this device", t);
            return null;
        }
    }

    // ----------------------------------------------------------------------
    // Session registration, called from SharedPipelineCache
    // ----------------------------------------------------------------------

    static void onCacheCreated(long deviceAddress, DeviceIdentity identity) {
        // With the race-loser pattern in
        // SharedPipelineCache, both the winner and the loser of the create race
        // reach this method; the loser must never reset the winner's session
        // (its "used" flag). Identity is probed from the same device, so the
        // first registration is the correct one.
        if (identity != null) {
            SESSIONS.putIfAbsent(deviceAddress, new CacheSession(identity));
        }
    }

    /** Marks the cache as used; called once per pipeline-compile call site (both vkCreateGraphicsPipelines sites). */
    static void markUsed(long deviceAddress) {
        CacheSession session = SESSIONS.get(deviceAddress);
        if (session != null && !session.used) {
            session.used = true;
        }
    }

    // ----------------------------------------------------------------------
    // Load path: meta first, then payload, then the embedded Vulkan header
    // ----------------------------------------------------------------------

    static LoadOutcome loadPayload(DeviceIdentity identity) {
        try {
            PearlConfig config = PearlConfig.get();
            if (!config.persistentPipelineCache) {
                return LoadOutcome.DISABLED;
            }
            if (identity == null) {
                return LoadOutcome.NO_IDENTITY;
            }
            Path root = cacheRoot();
            if (root == null) {
                return LoadOutcome.FRESH;
            }
            String base = FILE_PREFIX + fileHash(identity);
            Path metaPath = root.resolve(base + META_SUFFIX);
            Path binPath = root.resolve(base + BIN_SUFFIX);
            if (!Files.isRegularFile(metaPath) || !Files.isRegularFile(binPath)) {
                return LoadOutcome.FRESH;
            }

            // Meta is authoritative: an unparsable or mismatched meta invalidates
            // the entry even if the payload looks fine.
            MetaJson meta;
            try {
                meta = GSON.fromJson(Files.readString(metaPath, StandardCharsets.UTF_8), MetaJson.class);
            } catch (Throwable parseFailure) {
                return LoadOutcome.INVALID;
            }
            if (!isMetaValid(meta, identity)) {
                return LoadOutcome.INVALID;
            }

            byte[] payload = Files.readAllBytes(binPath);
            if (payload.length != meta.size
                || payload.length < HEADER_SIZE
                || payload.length > MAX_PAYLOAD_BYTES
                || !PearlUtil.hex(PearlUtil.sha256(payload)).equalsIgnoreCase(meta.payloadSha256)
                || !headerMatches(payload, identity)) {
                return LoadOutcome.INVALID;
            }
            return new LoadOutcome(payload, "loaded", payload.length);
        } catch (Throwable t) {
            warnOnce("Pipeline cache disk: load failed, continuing with an empty cache", t);
            return LoadOutcome.ERROR;
            // Deliberately no cleanup: an invalid file stays in place and gets
            // atomically replaced by a successful save later in this session.
        }
    }

    /**
     * Validates the sidecar meta against the probed device and the format
     * limits. Kept as one predicate so the load path reads as a linear
     * "meta, then payload, then header, then sha" chain.
     */
    private static boolean isMetaValid(MetaJson meta, DeviceIdentity identity) {
        return meta != null
            && meta.format == PIPELINE_CACHE_FORMAT
            && meta.vendorID == (identity.vendorID() & 0xFFFFFFFFL)
            && meta.deviceID == (identity.deviceID() & 0xFFFFFFFFL)
            && meta.driverVersion == (identity.driverVersion() & 0xFFFFFFFFL)
            && meta.pipelineCacheUUID != null
            && meta.pipelineCacheUUID.equalsIgnoreCase(identity.uuidHex())
            && meta.size >= HEADER_SIZE
            && meta.size <= MAX_PAYLOAD_BYTES
            && meta.payloadSha256 != null;
    }

    /**
     * Checks the embedded Vulkan pipeline cache header against the probed
     * device: {@code headerSize == 32}, {@code headerVersion == 1}, matching
     * vendorID/deviceID and the full {@code pipelineCacheUUID}.
     */
    private static boolean headerMatches(byte[] payload, DeviceIdentity identity) {
        if (payload.length < HEADER_SIZE) {
            return false;
        }
        ByteBuffer header = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        if (header.getInt(0) != HEADER_SIZE || header.getInt(4) != HEADER_VERSION_ONE) {
            return false;
        }
        if (header.getInt(8) != identity.vendorID() || header.getInt(12) != identity.deviceID()) {
            return false;
        }
        byte[] uuid = new byte[UUID_SIZE];
        header.get(16, uuid);
        return Arrays.equals(uuid, identity.pipelineCacheUUID());
    }

    // ----------------------------------------------------------------------
    // Save path (VulkanDeviceMixin -> VulkanDevice.close HEAD)
    // ----------------------------------------------------------------------

    /**
     * Serializes and stores the shared pipeline cache for the closing device.
     * Swallows every throwable: teardown must always continue exactly as
     * vanilla. Always clears the in-memory bookkeeping (the shared-cache map
     * and the session registry) for the closing device regardless of
     * configuration; disk IO is skipped when the module is disabled, when the
     * device never created a cache, or when no pipeline compile went through
     * it.
     */
    public static void saveOnDeviceClose(VkDevice device) {
        try {
            if (device == null || device.address() == 0L) {
                return;
            }
            long deviceAddress = device.address();
            // Remove both records BEFORE any IO and BEFORE the feature toggle:
            // a repeated close() can never double-save, a late lookup cannot
            // resurrect a dying device, and the SharedPipelineCache bookkeeping
            // is cleared even when disk persistence is off (otherwise a stale
            // entry keyed by a reusable device address would survive teardown).
            long cacheHandle = SharedPipelineCache.remove(deviceAddress);
            CacheSession session = SESSIONS.remove(deviceAddress);
            PearlConfig config = PearlConfig.get();
            if (!config.persistentPipelineCache) {
                return;
            }
            if (cacheHandle == 0L || session == null || !session.used) {
                return; // module never engaged for this device - stay silent
            }

            byte[] payload = serialize(device, cacheHandle);
            if (payload == null) {
                reportClose(config, "skipped (driver returned no usable cache data this session)");
                return;
            }
            savePayload(session.identity, payload, config);
        } catch (Throwable t) {
            warnOnce("Pipeline cache disk: save failed at device close, teardown continues normally", t);
        }
    }

    /**
     * Two-call serialization: {@code pData = NULL} for the size, then a
     * LWJGL-allocated buffer. {@code VK_INCOMPLETE} or any negative result
     * aborts the save - a partial blob is never written. Uses the size Vulkan
     * actually wrote back, which can be smaller than the requested one.
     */
    private static byte[] serialize(VkDevice device, long cacheHandle) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer sizeBuf = stack.callocPointer(1);
            int rc = VK12.vkGetPipelineCacheData(device, cacheHandle, sizeBuf, (ByteBuffer) null);
            if (rc != VK12.VK_SUCCESS) {
                return null;
            }
            long declared = sizeBuf.get(0);
            if (declared <= 0L || declared > MAX_PAYLOAD_BYTES) {
                return null;
            }

            ByteBuffer data = MemoryUtil.memAlloc((int) declared);
            try {
                sizeBuf.put(0, declared);
                int rcSecond = VK12.vkGetPipelineCacheData(device, cacheHandle, sizeBuf, data);
                if (rcSecond == VK12.VK_INCOMPLETE) {
                    return null; // data did not fit - skip saving in this session
                }
                if (rcSecond < 0) {
                    return null;
                }
                long actual = sizeBuf.get(0); // size written by the implementation
                if (actual <= 0L || actual > declared) {
                    return null;
                }
                byte[] bytes = new byte[(int) actual];
                data.limit((int) actual).position(0);
                data.get(bytes);
                return bytes;
            } finally {
                MemoryUtil.memFree(data);
            }
        } catch (Throwable t) {
            warnOnce("Pipeline cache disk: vkGetPipelineCacheData failed, skipping save", t);
            return null;
        }
    }

    private static void savePayload(DeviceIdentity identity, byte[] payload, PearlConfig config) {
        if (payload.length < HEADER_SIZE || payload.length > MAX_PAYLOAD_BYTES) {
            reportClose(config, "skipped (cache data out of size limits)");
            return;
        }
        if (!headerMatches(payload, identity)) {
            // Cannot happen for data from this device, but if a driver ever
            // violates the header contract, storing it would only guarantee a
            // validation miss on the next launch.
            reportClose(config, "skipped (cache header does not match this device)");
            return;
        }
        Path root = cacheRoot();
        if (root == null) {
            reportClose(config, "skipped (no game directory)");
            return;
        }
        try {
            Files.createDirectories(root);
            String base = FILE_PREFIX + fileHash(identity);
            // Payload first, meta second: a torn write leaves an orphan .bin,
            // which the meta-first load order rejects - never a trusted but
            // corrupt file.
            PearlUtil.writeAtomically(root.resolve(base + BIN_SUFFIX), payload);
            PearlUtil.writeAtomically(root.resolve(base + META_SUFFIX),
                buildMetaJson(payload.length, identity, payload).getBytes(StandardCharsets.UTF_8));
            reportClose(config, "saved (" + Math.max(1, payload.length / 1024) + " KB)");
            evictIfNeeded(DIRECTORY_CAP_BYTES);
        } catch (Throwable t) {
            warnOnce("Pipeline cache disk: write failed, skipping save", t);
            reportClose(config, "failed");
        }
    }

    /** Builds the sidecar metadata; uint32 fields are written unsigned. */
    static String buildMetaJson(int payloadSize, DeviceIdentity identity, byte[] payload) {
        return """
            {
              "format": %d,
              "vendorID": %d,
              "deviceID": %d,
              "driverVersion": %d,
              "pipelineCacheUUID": "%s",
              "size": %d,
              "payloadSha256": "%s",
              "timestamp": %d
            }
            """.formatted(
                PIPELINE_CACHE_FORMAT,
                identity.vendorID() & 0xFFFFFFFFL,
                identity.deviceID() & 0xFFFFFFFFL,
                identity.driverVersion() & 0xFFFFFFFFL,
                identity.uuidHex(),
                payloadSize,
                PearlUtil.hex(PearlUtil.sha256(payload)),
                System.currentTimeMillis()
            );
    }

    // ----------------------------------------------------------------------
    // Housekeeping: evict only own pc-*.bin (and their meta) by oldest-modified
    // ----------------------------------------------------------------------

    /**
     * Delegates to the shared walk body: the
     * classification, T-C1 orphan-meta absorption and T-C2 delete guard are
     * byte-identical to the shader-cache purge and live in
     * {@link PearlUtil#housekeepingWalk} - one copy, one test contour. This
     * module only picks its flat directory, the {@code pc-}-prefixed file
     * names and its cap; the walk is best-effort and never surfaces.
     */
    static void evictIfNeeded(long capBytes) {
        PearlUtil.housekeepingWalk(cacheRoot(), false, FILE_PREFIX, BIN_SUFFIX, null, capBytes);
    }

    // ----------------------------------------------------------------------
    // Logging (one start line, one close line, at most one warning)
    // ----------------------------------------------------------------------

    /** Emits the single start line for this session, reflecting the final outcome. */
    static void reportStartLine(PearlConfig config, LoadOutcome outcome, boolean driverRejected, boolean cacheCreated) {
        if (!config.verboseLog || !START_REPORTED.compareAndSet(false, true)) {
            return;
        }
        String line;
        if (outcome == LoadOutcome.DISABLED) {
            line = "Pipeline cache disk: disabled by config";
        } else if (outcome == LoadOutcome.NO_IDENTITY) {
            line = "Pipeline cache disk: disabled (device identity unavailable)";
        } else if (driverRejected) {
            line = "Pipeline cache disk: driver did not accept cached data, pipeline cache recreated empty";
        } else if ("loaded".equals(outcome.state())) {
            line = "Pipeline cache disk: loaded " + Math.max(1, outcome.sizeBytes() / 1024) + " KB into the pipeline cache";
        } else if ("invalid".equals(outcome.state())) {
            line = "Pipeline cache disk: cached file failed validation, starting fresh (file left in place)";
        } else if (cacheCreated) {
            line = "Pipeline cache disk: no usable disk cache, pipeline cache starts fresh";
        } else {
            return; // error state already warned; failed creation already warned by tryCreate
        }
        PearlOptimizer.LOGGER.info(line);
    }

    private static void reportClose(PearlConfig config, String detail) {
        if (config.verboseLog) {
            PearlOptimizer.LOGGER.info("Pipeline cache disk: {}", detail);
        }
    }

    private static void warnOnce(String message, Throwable t) {
        if (WARNED.compareAndSet(false, true)) {
            PearlOptimizer.LOGGER.warn(message, t);
        }
    }

    public static void setCacheRootForTest(Path path) {
        testCacheRoot = path;
    }

    // ----------------------------------------------------------------------
    // Small helpers
    // ----------------------------------------------------------------------

    private static Path cacheRoot() {
        Path override = testCacheRoot;
        if (override != null) {
            return override;
        }
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getGameDir()
                .resolve("cache")
                .resolve("pearl-optimizer")
                .resolve("pipeline-cache");
        } catch (Throwable t) {
            return null;
        }
    }

    private static final HexFormat HEX = HexFormat.of();

    /** First 16 bytes of SHA-256 over the identity string, hex-encoded (32 chars, lowercase). */
    private static String fileHash(DeviceIdentity identity) {
        byte[] digest = PearlUtil.sha256(identity.identityString().getBytes(StandardCharsets.UTF_8));
        return HEX.formatHex(digest, 0, 16);
    }
}
