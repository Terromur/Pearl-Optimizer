package dev.zai.pearloptimizer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One lazily created {@code VkPipelineCache} per {@code VkDevice}, substituted
 * for the {@code VK_NULL_HANDLE} that vanilla 26.3 passes to
 * {@code vkCreateGraphicsPipelines} (both call sites inside
 * {@code VulkanRenderPipeline.compile}).
 *
 * <p>At device close the cache is destroyed explicitly ({@link #destroy},
 * called right before {@code vkDestroyDevice}): the spec guarantee that every
 * device child object dies together with the device still holds as a
 * fallback, but the validation layer reports such objects as leaked at
 * {@code vkDestroyDevice} (VUID-vkDestroyDevice-device-05137). If cache
 * creation fails for any reason the holder returns 0, which is byte-for-byte
 * the vanilla argument, so the fallback is exact.</p>
 *
 * <p>The same cache object is also persistent: on first creation
 * {@link PersistentPipelineCache} supplies validated on-disk data as
 * {@code pInitialData}, and on device close the very same cache is serialized
 * back to disk. There is exactly one cache object per device - the module only
 * adds load and save, never a second cache.</p>
 *
 * <p>The per-device close also carries a closed-guard (audit
 * finding B-4): once the close-HEAD hook has removed the handle, {@link
 * #forDevice} returns 0, so a late pipeline compile stays on the vanilla path
 * instead of recreating the cache against a closing device.</p>
 *
 * <p>The one-time creation runs OUTSIDE the
 * map bin lock: {@code putIfAbsent} inserts the freshly created handle
 * atomically and the race loser destroys its redundant cache (the same
 * pattern the object cache's {@code acquireSlow} uses). The old
 * {@code computeIfAbsent} mapping function performed the disk load and
 * {@code vkCreatePipelineCache} while holding the bin lock; a documented
 * contract is preserved byte-for-byte: a failed creation caches 0 (the
 * vanilla argument) and is never retried until the device reopens.</p>
 */
public final class SharedPipelineCache {
    private static final Map<Long, Long> BY_DEVICE_ADDRESS = new ConcurrentHashMap<>();
    /** Handles parked by {@link #remove} so the device-destroy flush can destroy them explicitly. */
    private static final Map<Long, Long> PENDING_DESTROY = new ConcurrentHashMap<>();
    /**
     * Device addresses whose close-HEAD hook has already run (audit finding
     * B-4). After {@link #remove} the device is closing: handing a cache handle
     * to a compile would let it recreate the {@code VkPipelineCache} and race
     * the explicit destroy, so {@link #forDevice} hands out 0 (the vanilla
     * {@code VK_NULL_HANDLE} argument) instead. Entries are never removed: the
     * guard must cover the whole close tail up to and including {@code
     * vkDestroyDevice}, and within a Minecraft process a closed device address
     * is never re-registered by a fresh device (a hypothetical address reuse
     * would only keep the module on the vanilla path - safe fallback).
     */
    private static final Set<Long> CLOSED = ConcurrentHashMap.newKeySet();
    /** One INFO line per session (first successful pipeline-cache engagement). */
    private static final AtomicBoolean reported = new AtomicBoolean(false);
    /** At most one warning per session (log policy: warnings never repeat). */
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    private SharedPipelineCache() {
    }

    /**
     * Returns a shared pipeline cache handle for the device, or 0 when the
     * module is disabled or creation failed (both behave exactly like vanilla).
     */
    public static long forDevice(VkDevice device) {
        try {
            PearlConfig config = PearlConfig.get();
            if (!config.sharedPipelineCache || device == null || device.address() == 0L) {
                return 0L;
            }
            long deviceAddress = device.address();
            // B-4 guard: once close HEAD has run, the cache handle is parked for
            // destruction and the device is tearing down - hand out 0 (the
            // vanilla argument) instead of letting this compile recreate the
            // VkPipelineCache and race the explicit destroy. Narrows the race
            // window to "check -> create"; the residual interval is the
            // acknowledged B-1 semantics.
            if (CLOSED.contains(deviceAddress)) {
                return 0L;
            }
            Long cached = BY_DEVICE_ADDRESS.get(deviceAddress);
            long handle = cached != null
                ? cached.longValue()
                : createOutsideBinLock(deviceAddress, device);
            if (handle != 0L) {
                // Both vkCreateGraphicsPipelines call sites funnel through here,
                // so a non-zero handle returned from this method means a pipeline
                // compile is about to use the cache.
                PersistentPipelineCache.markUsed(deviceAddress);
                if (reported.compareAndSet(false, true) && config.verboseLog) {
                    PearlOptimizer.LOGGER.info(
                        "Pipelines are compiled through a shared VkPipelineCache (vanilla 26.3 passes VK_NULL_HANDLE); "
                            + "repeated pipeline compiles get faster, world-join stutter shrinks");
                }
            }
            return handle;
        } catch (Throwable t) {
            if (WARNED.compareAndSet(false, true)) {
                PearlOptimizer.LOGGER.warn("Pipeline cache lookup failed, falling back to vanilla", t);
            }
            return 0L;
        }
    }

    /**
     * Removes and returns the cache handle for the device (0 when absent).
     * Called exactly once at device close, before the disk write, so a repeated
     * close can never double-save. The handle is parked in
     * {@link #PENDING_DESTROY} instead of being dropped: the serialization keeps
     * its own copy of the bytes, and the parked handle is what
     * {@link #destroy} destroys right before {@code vkDestroyDevice}.
     *
     * <p>Also marks the address in {@link #CLOSED} (the B-4 guard): from this
     * point {@link #forDevice} returns 0 for the device, so late compiles stay
     * on the vanilla path while the close tail runs.</p>
     */
    static long remove(long deviceAddress) {
        // B-4 guard: mark the device closed BEFORE the handle leaves the live
        // map, so every forDevice call that starts after close HEAD sees the
        // guard and stays on the vanilla path.
        CLOSED.add(deviceAddress);
        Long handle = BY_DEVICE_ADDRESS.remove(deviceAddress);
        if (handle != null) {
            PENDING_DESTROY.put(deviceAddress, handle);
        }
        return handle == null ? 0L : handle.longValue();
    }

    /**
     * Destroys the device's shared {@code VkPipelineCache} explicitly. Called
     * from {@code VulkanDeviceMixin} right before the
     * {@code VK12.vkDestroyDevice} call - without this the validation layer
     * reports the cache as leaked (VUID-vkDestroyDevice-device-05137).
     *
     * <p>Resolves the handle parked by {@link #remove} at close HEAD, falling
     * back to the live map when the close-HEAD save hook never ran. Returns
     * {@code true} when a cache was destroyed, {@code false} when the module
     * never engaged for this device (or creation failed - vanilla behaviour in
     * both cases). Idempotent: both lookups are removals, so a repeated call
     * finds nothing. Swallows every throwable: the device teardown continues
     * exactly as vanilla.</p>
     */
    public static boolean destroy(VkDevice device) {
        try {
            if (device == null || device.address() == 0L) {
                return false;
            }
            long address = device.address();
            // Invariant (see remove()): the handle sits in exactly one of the
            // two maps — parked at close HEAD, or still live when the close-HEAD
            // save hook never ran. Removing from both is a no-op for the empty one.
            Long parked = PENDING_DESTROY.remove(address);
            Long live = BY_DEVICE_ADDRESS.remove(address);
            long handle = parked != null ? parked : (live != null ? live : 0L);
            if (handle == 0L) {
                return false; // never engaged (or creation failed) - nothing to destroy
            }
            VK12.vkDestroyPipelineCache(device, handle, null);
            return true;
        } catch (Throwable t) {
            if (WARNED.compareAndSet(false, true)) {
                PearlOptimizer.LOGGER.warn("Shared pipeline cache: explicit destroy at device close failed, the cache dies with vkDestroyDevice", t);
            }
            return false;
        }
    }

    /**
     * One-time creation path: the disk load and
     * {@code vkCreatePipelineCache} run WITHOUT any map lock held; the map
     * insert is an atomic {@code putIfAbsent} and a racing loser destroys its
     * redundant cache - the exact pattern of the object cache's
     * {@code acquireSlow}.
     *
     * <p>Contract notes (preserved from the old {@code computeIfAbsent}):
     * the first successful insert wins; a cached 0 (the winner's failed
     * creation) is never replaced or retried - the documented "0 = exactly
     * vanilla, no attempts until the device reopens" behavior that prevents
     * a spam of failed {@code vkCreatePipelineCache} calls. A loser that did
     * create a working cache while the winner cached 0 destroys it (the
     * exceptional path costs one throwaway cache object, never a correctness
     * problem). The residual "check CLOSED -&gt; create" race is the
     * acknowledged B-1 semantics: a handle inserted after {@link #remove} is
     * still covered by the {@link #destroy} fallback at device close.</p>
     */
    private static long createOutsideBinLock(long deviceAddress, VkDevice device) {
        long fresh = create(device);
        Long previous = BY_DEVICE_ADDRESS.putIfAbsent(deviceAddress, fresh);
        if (previous == null) {
            return fresh; // won the race: fresh is cached (0 included - documented fallback)
        }
        if (fresh != 0L) {
            // Lost the race: destroy the cache nobody has ever seen. Never
            // surface: a leaked loser would only be reported by the validation
            // layer, a failed destroy still dies with the device (spec).
            try {
                VK12.vkDestroyPipelineCache(device, fresh, null);
            } catch (Throwable ignored) {
            }
        }
        return previous.longValue(); // the winner's handle, or the cached 0
    }

    private static long create(VkDevice device) {
        PersistentPipelineCache.DeviceIdentity identity = PersistentPipelineCache.probeIdentity(device);
        PersistentPipelineCache.LoadOutcome outcome = PersistentPipelineCache.loadPayload(identity);

        long handle = tryCreate(device, outcome.payload());
        boolean driverRejected = false;
        if (handle == 0L && outcome.payload() != null) {
            // Per spec the driver may either reject cache data (any result other
            // than VK_SUCCESS) or silently ignore it. Never interpreted as a
            // fatal error: recreate with initialDataSize = 0, the vanilla path.
            handle = tryCreate(device, null);
            driverRejected = handle != 0L;
        }
        if (handle != 0L) {
            PersistentPipelineCache.onCacheCreated(device.address(), identity);
            if (PearlConfig.get().verboseLog) {
                PearlOptimizer.LOGGER.info(
                    "Created shared VkPipelineCache 0x{} for device 0x{}",
                    Long.toHexString(handle), Long.toHexString(device.address()));
            }
        }
        PersistentPipelineCache.reportStartLine(PearlConfig.get(), outcome, driverRejected, handle != 0L);
        return handle;
    }

    /**
     * Creates the cache, optionally seeded with validated on-disk data. The
     * seed buffer is an LWJGL allocation (at least 8-byte aligned, native byte
     * order) - never a JDK direct buffer or a heap array - and is freed right
     * after the call, which the spec allows.
     */
    private static long tryCreate(VkDevice device, byte[] payload) {
        ByteBuffer initialData = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineCacheCreateInfo info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            if (payload != null) {
                initialData = MemoryUtil.memAlloc(payload.length);
                initialData.put(payload).flip();
                info.pInitialData(initialData);
            }
            LongBuffer out = stack.mallocLong(1);
            int rc = VK12.vkCreatePipelineCache(device, info, null, out);
            if (rc == VK12.VK_SUCCESS) {
                return out.get(0);
            }
            return 0L;
        } catch (Throwable t) {
            PearlOptimizer.LOGGER.warn("Failed to create VkPipelineCache, using no cache (vanilla behaviour)", t);
            return 0L;
        } finally {
            if (initialData != null) {
                MemoryUtil.memFree(initialData);
            }
        }
    }
}
