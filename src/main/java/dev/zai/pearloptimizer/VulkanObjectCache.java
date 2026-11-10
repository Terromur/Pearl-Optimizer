package dev.zai.pearloptimizer;

import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Per-device deduplication registry for the three create-once Vulkan object
 * kinds that vanilla 26.3 rebuilds from scratch on every pipeline compile
 * (both {@code VulkanRenderPipeline.compile} call sites run for every
 * {@code VulkanRenderPipeline} instance):
 *
 * <ul>
 *   <li>{@code VkShaderModule} - keyed by the SHA-256 of the full SPIR-V
 *       contents ({@code pCode}), so byte-identical modules compiled for
 *       different pipelines or reloaded resources resolve to one GPU object;</li>
 *   <li>{@code VkDescriptorSetLayout} - keyed by the ordered list of all
 *       bindings (binding, descriptorType, descriptorCount, stageFlags) plus
 *       the create flags; immutable samplers would be part of the key, but
 *       since they embed foreign {@code VkSampler} handles, any binding that
 *       carries them is not cached at all (rule: when in doubt, do not
 *       cache);</li>
 *   <li>{@code VkPipelineLayout} - keyed by the registry record id of the
 *       concrete {@code VkDescriptorSetLayout} entry it was built from (never
 *       a Java address and never a Vulkan handle), the full push constant
 *       range list and the create flags.</li>
 * </ul>
 *
 * <p>Lifetime is refcounted. Every acquire in {@code compile} takes one
 * reference, every destroy in {@code VulkanRenderPipeline.destroy} returns
 * one; the Vulkan object is destroyed only when the refcount reaches zero,
 * which makes resource reloads safe: old and new pipelines may reference the
 * same cached object while they overlap, and it dies after the last of them
 * is destroyed. A descriptor set layout is therefore never destroyed while a
 * live pipeline layout user still holds it - both objects are acquired
 * together by every compile, so their refcounts only reach zero together.</p>
 *
 * <p>Thread safety: {@code compile} may run concurrently. Lookup is a
 * lock-free {@link ConcurrentHashMap} read; on a miss the Vulkan object is
 * created outside any global lock and inserted atomically afterwards; a lost
 * insert race destroys the redundant object and uses the winner. An acquire
 * can never attach to a dying entry (refcount 0 fails {@code tryAcquire}).</p>
 *
 * <p>Device close: {@link #onDeviceClose} (from {@code VulkanDeviceMixin},
 * {@code VulkanDevice.close} HEAD) marks the registry retired. Nothing is
 * destroyed there on purpose: at close HEAD the GPU has not been waited on
 * yet, and a shared object referenced by several queued pipelines would
 * otherwise be destroyed once per reference. Every later destroy of a
 * retired object is absorbed, so each cached object dies exactly once.
 * The retired registry also absorbs late releases, never leaking a destroy
 * onto a dead device.</p>
 *
 * <p>Explicit flush: {@link #flushRetired} (from {@code VulkanDeviceMixin},
 * right before the {@code VK12.vkDestroyDevice} call, after
 * {@code commandEncoder.destroy()} has drained the queue) destroys every
 * object the retired registry still holds - shader modules first, then
 * pipeline layouts, then descriptor set layouts - so nothing is left to die
 * implicitly with the device. Without this flush the validation layer
 * reports all surviving registry objects as leaked at
 * {@code vkDestroyDevice} (VUID-vkDestroyDevice-device-05137). The flush
 * runs once per registry; its entries stay in {@code byHandle}, so a late
 * destroy remains absorbed with zero double-destroy risk.</p>
 *
 * <p>Fail-safe: every entry point catches every {@link Throwable}; a disabled
 * module, an unparseable create info or any error degrades to the exact
 * vanilla create/destroy call. A registry-owned handle is never handed back
 * to vanilla destroy (that would risk double destruction); an unknown handle
 * always falls through to vanilla, which is correct for objects the cache
 * never owned.</p>
 */
public final class VulkanObjectCache {

    /** Object kinds; separate counters and destroy paths per kind. */
    public static final int KIND_SHADER_MODULE = 0;
    public static final int KIND_DESCRIPTOR_SET_LAYOUT = 1;
    public static final int KIND_PIPELINE_LAYOUT = 2;
    private static final int KIND_COUNT = 3;

    /** Live registries per VkDevice address; closed registries are removed from here. */
    private static final ConcurrentHashMap<Long, Registry> LIVE = new ConcurrentHashMap<>();
    /** Retired registries kept only so late destroys of their objects can be absorbed. */
    private static final ConcurrentHashMap<Long, Registry> RETIRED = new ConcurrentHashMap<>();

    /** Exactly one start line per session. */
    private static final AtomicBoolean START_REPORTED = new AtomicBoolean(false);
    /** Exactly one close line per session. */
    private static final AtomicBoolean CLOSE_REPORTED = new AtomicBoolean(false);
    /** At most one warning per session. */
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    private VulkanObjectCache() {
    }

    // ----------------------------------------------------------------------
    // Entry points used by the mixins (all of them never throw)
    // ----------------------------------------------------------------------

    /**
     * Returns the cached {@code VkShaderModule} for the SPIR-V in
     * {@code createInfo}, creating it on first use, or 0 when the module is
     * not cached and the caller must run the vanilla create.
     */
    private static long acquireObject(VkDevice device, int kind,
                                      Function<VkDevice, String> keyBuilder,
                                      LongSupplier creator,
                                      String failureMessage) {
        try {
            PearlConfig config = PearlConfig.get();
            if (!config.objectCache) {
                reportStart(config);
                return 0L;
            }
            Registry registry = liveRegistry(device);
            if (registry == null) {
                return 0L;
            }
            reportStart(config);
            String key = keyBuilder.apply(device);
            if (key == null) {
                return 0L; // not cacheable
            }
            return acquire(registry, kind, key, creator);
        } catch (Throwable t) {
            warnOnce(failureMessage, t);
            return 0L;
        }
    }

    public static long acquireShaderModule(VkDevice device, VkShaderModuleCreateInfo createInfo) {
        return acquireObject(device, KIND_SHADER_MODULE,
            dev -> {
                if (createInfo == null) {
                    return null;
                }
                ByteBuffer code = createInfo.pCode();
                if (code == null || code.remaining() == 0) {
                    return null;
                }
                return "sm|" + sha256Hex(code);
            },
            () -> createShaderModule(device, createInfo),
            "Object cache: shader module lookup failed, using vanilla creation");
    }

    /**
     * Returns the cached {@code VkDescriptorSetLayout} for the binding list in
     * {@code createInfo}, or 0 when the layout is not cacheable (unknown pNext
     * chain, immutable samplers) and the caller must run the vanilla create.
     */
    public static long acquireDescriptorSetLayout(VkDevice device, VkDescriptorSetLayoutCreateInfo createInfo) {
        return acquireObject(device, KIND_DESCRIPTOR_SET_LAYOUT,
            dev -> {
                if (createInfo == null) {
                    return null;
                }
                if (createInfo.pNext() != 0L) {
                    return null; // unknown extension chain - do not interpret, do not cache
                }
                VkDescriptorSetLayoutBinding.Buffer bindings = createInfo.pBindings();
                if (bindings == null) {
                    return null;
                }
                StringBuilder key = new StringBuilder("dsl|").append(createInfo.flags());
                int count = bindings.remaining();
                for (int i = 0; i < count; i++) {
                    VkDescriptorSetLayoutBinding binding = bindings.get(i);
                    LongBuffer immutableSamplers = binding.pImmutableSamplers();
                    if (immutableSamplers != null && immutableSamplers.remaining() > 0) {
                        // Immutable samplers embed foreign VkSampler handles; the key
                        // would have to carry them, so per the fail-safe rule such
                        // layouts are simply not cached (vanilla create/destroy).
                        return null;
                    }
                    key.append('|').append(binding.binding())
                        .append(',').append(binding.descriptorType())
                        .append(',').append(binding.descriptorCount())
                        .append(',').append(binding.stageFlags());
                }
                return key.toString();
            },
            () -> createDescriptorSetLayout(device, createInfo),
            "Object cache: descriptor set layout lookup failed, using vanilla creation");
    }

    /**
     * Returns the cached {@code VkPipelineLayout} for {@code createInfo}, or 0
     * when it is not cacheable - in particular when its descriptor set layout
     * is not a registry record (then the record id needed for the key does not
     * exist and the object is left to vanilla).
     */
    public static long acquirePipelineLayout(VkDevice device, VkPipelineLayoutCreateInfo createInfo) {
        return acquireObject(device, KIND_PIPELINE_LAYOUT,
            dev -> {
                if (createInfo == null) {
                    return null;
                }
                if (createInfo.pNext() != 0L) {
                    return null;
                }
                LongBuffer setLayouts = createInfo.pSetLayouts();
                if (setLayouts == null || setLayouts.remaining() != 1) {
                    return null;
                }
                // The registry lookup needs the live registry, which acquireObject
                // already resolved before calling the key builder - fetch it again.
                Registry registry = liveRegistry(dev);
                if (registry == null) {
                    return null;
                }
                long setLayout = setLayouts.get(setLayouts.position());
                Registry.Entry setLayoutEntry = registry.byHandle.get(setLayout);
                if (setLayoutEntry == null || setLayoutEntry.kind != KIND_DESCRIPTOR_SET_LAYOUT) {
                    return null; // DS layout not owned by the cache - vanilla path
                }
                StringBuilder key = new StringBuilder("pl|").append(createInfo.flags())
                    .append("|dsl").append(setLayoutEntry.id);
                VkPushConstantRange.Buffer ranges = createInfo.pPushConstantRanges();
                if (ranges == null) {
                    key.append("|none");
                } else {
                    int count = ranges.remaining();
                    for (int i = 0; i < count; i++) {
                        VkPushConstantRange range = ranges.get(i);
                        key.append('|').append(range.stageFlags())
                            .append(',').append(range.offset())
                            .append(',').append(range.size());
                    }
                }
                return key.toString();
            },
            () -> createPipelineLayout(device, createInfo),
            "Object cache: pipeline layout lookup failed, using vanilla creation");
    }

    /**
     * Returns true when the destroy of {@code handle} is absorbed by the
     * registry (the caller must skip the vanilla destroy); false when the
     * object was never owned by the cache and the vanilla destroy must run.
     */
    public static boolean releaseShaderModule(VkDevice device, long handle) {
        return release(device, KIND_SHADER_MODULE, handle);
    }

    /** See {@link #releaseShaderModule}. */
    public static boolean releaseDescriptorSetLayout(VkDevice device, long handle) {
        return release(device, KIND_DESCRIPTOR_SET_LAYOUT, handle);
    }

    /** See {@link #releaseShaderModule}. */
    public static boolean releasePipelineLayout(VkDevice device, long handle) {
        return release(device, KIND_PIPELINE_LAYOUT, handle);
    }

    /**
     * Device teardown hook ({@code VulkanDevice.close} HEAD, before any
     * Vulkan teardown work): retires the registry and emits the one close
     * line. Nothing is destroyed here - the GPU has not been waited on yet;
     * the retired objects are destroyed later by {@link #flushRetired},
     * right before {@code VK12.vkDestroyDevice}. Swallows every throwable
     * so teardown always continues as vanilla.
     */
    public static void onDeviceClose(VkDevice device) {
        try {
            if (device == null || device.address() == 0L) {
                return;
            }
            Registry registry = LIVE.get(device.address());
            if (registry == null) {
                return; // module never engaged for this device - stay silent
            }
            // closed FIRST, so a concurrent acquire either sees the retired
            // registry (and bypasses it) or the removal below; RETIRED keeps it
            // reachable for absorption; the two-arg remove only drops it while
            // it is still the mapped value.
            registry.closed = true;
            RETIRED.put(device.address(), registry);
            LIVE.remove(device.address(), registry);
            reportClose(PearlConfig.get(), registry);
            // Deliberately no vkDestroy* here: queued pipeline destroys that run
            // later inside commandEncoder.destroy() (after graphicsQueue().waitIdle())
            // are absorbed against the retired registry. The surviving objects are
            // destroyed explicitly by flushRetired(), right before vkDestroyDevice.
        } catch (Throwable t) {
            warnOnce("Object cache: device close handling failed, teardown continues normally", t);
        }
    }

    /**
     * Destroys every object the retired registry of {@code device} still
     * holds. Called from {@code VulkanDeviceMixin} right before the
     * {@code VK12.vkDestroyDevice} call, after
     * {@code commandEncoder.destroy()} drained the queue: all submitted work
     * is done, every queued vanilla destroy was absorbed, so the surviving
     * registry objects are exactly the ones that would otherwise be reported
     * as leaked by VUID-vkDestroyDevice-device-05137.
     *
     * <p>Destruction order: shader modules, then pipeline layouts, then
     * descriptor set layouts (users before the objects they were built
     * from). Entries stay in {@code byHandle} - late destroys remain absorbed;
     * byKey records destroyed by the flush are evicted on the acquire path
     * (B-prime livelock fix), and tryAcquire re-checks destroyed after the
     * refcount CAS (TOCTOU close) - the zero double-destroy guarantee is unchanged.</p>
     *
     * <p>Idempotent: a registry is flushed at most once; every repeated call
     * is a no-op. Returns {@code {flushed shader modules, flushed descriptor
     * set layouts, flushed pipeline layouts}} (all zero when there was
     * nothing to flush). Swallows every throwable.</p>
     */
    public static long[] flushRetired(VkDevice device) {
        try {
            long[] counts = new long[KIND_COUNT];
            if (device == null || device.address() == 0L) {
                return counts;
            }
            Registry registry = RETIRED.get(device.address());
            if (registry == null || !registry.flushed.compareAndSet(false, true)) {
                return counts; // nothing retired for this device, or already flushed
            }
            destroyKind(registry, KIND_SHADER_MODULE, counts);
            destroyKind(registry, KIND_PIPELINE_LAYOUT, counts);
            destroyKind(registry, KIND_DESCRIPTOR_SET_LAYOUT, counts);
            return counts;
        } catch (Throwable t) {
            warnOnce("Object cache: retired-object flush failed, remaining objects die with vkDestroyDevice", t);
            return new long[KIND_COUNT];
        }
    }

    /** Destroys every entry of one kind; the counters are indexed by the kind. */
    private static void destroyKind(Registry registry, int kind, long[] counts) {
        for (Registry.Entry entry : registry.byHandle.values()) {
            if (entry.kind == kind) {
                if (!entry.destroyed.compareAndSet(false, true)) {
                    continue; // lost the CAS - already destroyed elsewhere, no-op
                }
                destroyQuietly(registry, kind, entry.handle);
                entry.handle = 0L; // destroyed handle value never handed out again
                counts[kind]++;
            }
        }
    }

    /** Warn-once hook for the flush handler in {@code VulkanDeviceMixin}. */
    public static void reportFlushFailure(Throwable t) {
        warnOnce("Object cache: device-destroy flush handler failed, teardown continues as vanilla", t);
    }

    // ----------------------------------------------------------------------
    // Registry
    // ----------------------------------------------------------------------

    /**
     * Live registry for the device. Never returns a retired registry and
     * never returns a registry created for a previous {@code VkDevice}: if
     * the address was recycled by a new device (or the registry is closing),
     * the old one is moved to {@link #RETIRED} (its outstanding destroys keep
     * being absorbed) and the caller falls back to the vanilla path.
     */
    private static Registry liveRegistry(VkDevice device) {
        if (device == null || device.address() == 0L) {
            return null;
        }
        long address = device.address();
        // Lock-free fast path: the overwhelmingly common case is
        // a live registry for this exact device. `closed` is volatile and
        // `device` is final, so a registry passing both checks here is live at
        // this instant - the same guarantee window compute() provided, but
        // without taking the ConcurrentHashMap bin lock on every acquire of a
        // parallel pipeline compile. This restores the documented
        // "lock-free ConcurrentHashMap read" contract of this class.
        Registry fast = LIVE.get(address);
        if (fast != null && !fast.closed && fast.device == device) {
            return fast;
        }
        // Slow path: creation, address recycling or a closing registry.
        return LIVE.compute(address, (addr, existing) -> {
            if (existing == null) {
                return new Registry(device);
            }
            if (existing.closed || existing.device != device) {
                existing.closed = true;
                RETIRED.put(addr, existing);
                return null; // removes the mapping; the caller bypasses the cache
            }
            return existing;
        });
    }

    private static long acquire(Registry registry, int kind, String key, LongSupplier create) {
        Registry.Entry cached = registry.byKey.get(key);
        if (cached != null && cached.tryAcquire()) {
            registry.reused[kind].incrementAndGet();
            return cached.handle;
        }
        return acquireSlow(registry, kind, key, create);
    }

    /**
     * Miss path: the Vulkan object is created outside any global lock, then
     * inserted atomically. If another thread inserted first, the redundant
     * object is destroyed and the winner used. A dying entry (refcount 0,
     * removal in flight) can briefly win the putIfAbsent - the loop then
     * retries and never attaches to it.
     */
    private static long acquireSlow(Registry registry, int kind, String key, LongSupplier create) {
        long handle = create.getAsLong();
        if (handle == 0L) {
            return 0L; // creation failed - caller falls back to the vanilla call and its error handling
        }
        registry.created[kind].incrementAndGet(); // optimistic; undone below when an insert race is lost
        // the record is allocated once per acquire,
        // not once per retry attempt. A failed putIfAbsent never published it,
        // so the retry loop reuses the same clean instance (no state of the
        // failed attempt is mutated: handle, refcount and destroyed are only
        // touched by the owner after a successful insert).
        Registry.Entry fresh = registry.new Entry(kind, key, handle);
        for (;;) {
            Registry.Entry previous = registry.byKey.putIfAbsent(key, fresh);
            if (previous == null) {
                registry.byHandle.put(handle, fresh);
                return handle; // refcount starts at 1: this acquire
            }
            if (previous.tryAcquire()) {
                destroyQuietly(registry, kind, handle);
                registry.created[kind].decrementAndGet();
                return previous.handle;
            }
            // B-prime: the flush path destroys entries but never removes them
            // from byKey (absorption needs byHandle only); without eviction the
            // loop above would spin forever against a destroyed entry. Evict
            // exactly the destroyed one; a merely dying entry is still removed
            // by its releasing thread, as before. byHandle stays untouched.
            if (previous.destroyed.get() && registry.byKey.remove(key, previous)) {
                continue;
            }
            // previous was dying and will be removed in a moment - retry insert
        }
    }

    /**
     * Returns true when this destroy is absorbed by the registry. Once a
     * handle is recognised as registry-owned the outcome is always "absorb":
     * handing it back to vanilla destroy could double-destroy a shared
     * object. Unknown handles are never owned and always fall through.
     */
    private static boolean release(VkDevice device, int kind, long handle) {
        try {
            if (device == null || handle == 0L) {
                return false;
            }
            long address = device.address();
            Registry live = LIVE.get(address);
            if (live != null) {
                Registry.Entry entry = live.byHandle.get(handle);
                if (entry != null) {
                    if (entry.kind != kind) {
                        // Bookkeeping inconsistency - fail safe: absorb, the
                        // object stays owned by the registry until device close.
                        return true;
                    }
                    try {
                        if (entry.refcount.decrementAndGet() > 0) {
                            return true; // still referenced - skip vanilla destroy
                        }
                        // Last reference: detach BEFORE destroying so no acquire
                        // can ever attach to a dying entry.
                        live.byKey.remove(entry.key, entry);
                        live.byHandle.remove(handle, entry);
                        if (entry.destroyed.compareAndSet(false, true)) {
                            destroyQuietly(live, kind, handle);
                        } // else: flush won the CAS - decrement only, never re-destroy
                        live.released[kind].incrementAndGet();
                    } catch (Throwable t) {
                        // Registry-owned: absorb unconditionally, never hand the
                        // handle back to vanilla after a partial release.
                        warnOnce("Object cache: release failed, object left to the device teardown", t);
                    }
                    return true;
                }
            }
            Registry retired = RETIRED.get(address);
            if (retired != null && retired.byHandle.containsKey(handle)) {
                // Device already closing/closed: the handle stays registry-owned
                // (destroyed by flushRetired before vkDestroyDevice, or dying with
                // the device if the flush never ran) - absorb the destroy, do not
                // touch the GPU and never double-destroy.
                return true;
            }
            return false; // never ours - the caller must run the vanilla destroy
        } catch (Throwable t) {
            warnOnce("Object cache: release failed, falling back to vanilla destroy", t);
            return false;
        }
    }

    private static void destroyQuietly(Registry registry, int kind, long handle) {
        try {
            switch (kind) {
                case KIND_SHADER_MODULE -> VK12.vkDestroyShaderModule(registry.device, handle, null);
                case KIND_DESCRIPTOR_SET_LAYOUT -> VK12.vkDestroyDescriptorSetLayout(registry.device, handle, null);
                case KIND_PIPELINE_LAYOUT -> VK12.vkDestroyPipelineLayout(registry.device, handle, null);
                default -> {
                }
            }
        } catch (Throwable t) {
            warnOnce("Object cache: vkDestroy* failed for a fully released object", t);
        }
    }

    // ----------------------------------------------------------------------
    // Vanilla-equivalent creation helpers (MemoryStack-scoped, no allocation
    // is retained; the caller's create info struct is still alive here)
    // ----------------------------------------------------------------------

    private static long createShaderModule(VkDevice device, VkShaderModuleCreateInfo createInfo) {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            LongBuffer out = stack.callocLong(1);
            int rc = VK12.vkCreateShaderModule(device, createInfo, null, out);
            return rc == VK12.VK_SUCCESS ? out.get(0) : 0L;
        }
    }

    private static long createDescriptorSetLayout(VkDevice device, VkDescriptorSetLayoutCreateInfo createInfo) {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            LongBuffer out = stack.callocLong(1);
            int rc = VK12.vkCreateDescriptorSetLayout(device, createInfo, null, out);
            return rc == VK12.VK_SUCCESS ? out.get(0) : 0L;
        }
    }

    private static long createPipelineLayout(VkDevice device, VkPipelineLayoutCreateInfo createInfo) {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            LongBuffer out = stack.callocLong(1);
            int rc = VK12.vkCreatePipelineLayout(device, createInfo, null, out);
            return rc == VK12.VK_SUCCESS ? out.get(0) : 0L;
        }
    }

    // ----------------------------------------------------------------------
    // Logging: one start line, one close line, at most one warning
    // ----------------------------------------------------------------------

    private static void reportStart(PearlConfig config) {
        if (!config.verboseLog || !START_REPORTED.compareAndSet(false, true)) {
            return;
        }
        PearlOptimizer.LOGGER.info(config.objectCache
            ? "Object cache: enabled - identical VkShaderModule / VkDescriptorSetLayout / VkPipelineLayout objects are deduplicated per device (refcounted)"
            : "Object cache: disabled by config");
    }

    private static void reportClose(PearlConfig config, Registry registry) {
        if (!config.verboseLog || !CLOSE_REPORTED.compareAndSet(false, true)) {
            return;
        }
        PearlOptimizer.LOGGER.info(
            "Object cache closed: shader modules created={} reused={} released={}; descriptor set layouts created={} reused={} released={}; pipeline layouts created={} reused={} released={}",
            registry.created[KIND_SHADER_MODULE].get(), registry.reused[KIND_SHADER_MODULE].get(), registry.released[KIND_SHADER_MODULE].get(),
            registry.created[KIND_DESCRIPTOR_SET_LAYOUT].get(), registry.reused[KIND_DESCRIPTOR_SET_LAYOUT].get(), registry.released[KIND_DESCRIPTOR_SET_LAYOUT].get(),
            registry.created[KIND_PIPELINE_LAYOUT].get(), registry.reused[KIND_PIPELINE_LAYOUT].get(), registry.released[KIND_PIPELINE_LAYOUT].get());
    }

    private static void warnOnce(String message, Throwable t) {
        if (WARNED.compareAndSet(false, true)) {
            PearlOptimizer.LOGGER.warn(message, t);
        }
    }

    // ----------------------------------------------------------------------
    // Registry internals
    // ----------------------------------------------------------------------

    private static final class Registry {
        final VkDevice device;
        final ConcurrentHashMap<String, Entry> byKey = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Long, Entry> byHandle = new ConcurrentHashMap<>();
        /** created / reused / released counters per object kind. */
        final AtomicLong[] created = newCounterArray();
        final AtomicLong[] reused = newCounterArray();
        final AtomicLong[] released = newCounterArray();
        /** Record ids for pipeline layout keys; registry-unique, not a Java address, not a Vulkan handle. */
        final AtomicLong nextEntryId = new AtomicLong(1);
        volatile boolean closed;
        /** Set once by {@link #flushRetired}: the surviving objects were destroyed explicitly. */
        final AtomicBoolean flushed = new AtomicBoolean(false);

        Registry(VkDevice device) {
            this.device = device;
        }

        private static AtomicLong[] newCounterArray() {
            AtomicLong[] counters = new AtomicLong[KIND_COUNT];
            for (int i = 0; i < KIND_COUNT; i++) {
                counters[i] = new AtomicLong();
            }
            return counters;
        }

        /** A refcounted cache record. Acquire = CAS 0-guarded increment. */
        final class Entry {
            final int kind;
            final String key;
            volatile long handle; // blanked to 0 by the destroy winner (B-prime fix)
            final long id = nextEntryId.getAndIncrement();
            final AtomicInteger refcount = new AtomicInteger(1);
            final AtomicBoolean destroyed = new AtomicBoolean(false);

            Entry(int kind, String key, long handle) {
                this.kind = kind;
                this.key = key;
                this.handle = handle;
            }

            /** Fails while the refcount is 0 - a dying entry is never reused. */
            boolean tryAcquire() {
                for (;;) {
                    if (destroyed.get()) {
                        return false; // B-prime fix: never attach to a flushed entry
                    }
                    int current = refcount.get();
                    if (current <= 0) {
                        return false;
                    }
                    if (refcount.compareAndSet(current, current + 1)) {
                        if (destroyed.get()) {
                            // TOCTOU close: a destroy landing
                            // between the guard and the CAS must not hand out
                            // the dying handle - roll the increment back and
                            // let the caller's evict path remove the entry.
                            refcount.decrementAndGet();
                            return false;
                        }
                        return true;
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------------------
    // Small helpers
    // ----------------------------------------------------------------------

    /**
     * SHA-256 over the raw bytes of {@code data}; reads via a duplicate, never
     * disturbing the struct memory. The chunk array (and its full
     * copy) is gone: {@code MessageDigest.update(ByteBuffer)} consumes the
     * duplicate directly, so a shader-module acquire allocates nothing on the
     * hashing path (the old code allocated a 64 KB scratch array per call).
     */
    private static String sha256Hex(ByteBuffer data) {
        try {
            MessageDigest digest = PearlUtil.sha256();
            digest.update(data.duplicate());
            return PearlUtil.hex(digest.digest());
        } catch (Throwable t) {
            throw new IllegalStateException("SHA-256 unavailable", t);
        }
    }
}
