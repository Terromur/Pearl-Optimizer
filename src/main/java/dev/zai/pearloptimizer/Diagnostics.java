package dev.zai.pearloptimizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Diagnostics-only counters (plan v2, modules b47b92e + b100be3; census v2
 * caller attribution). NOT an optimization: nothing here changes
 * rendering, Vulkan calls or game behaviour, and no Vulkan API is invoked from
 * here. Two instruments:
 *
 * <ul>
 *   <li><b>Barrier census</b> (b47b92e): every hooked {@code vkCmdPipelineBarrier*}
 *       call site reports one increment with its site label; frames are counted
 *       at the head of the surface {@code present()}. Answers the track-13 arm
 *       "barriers/frame"; the arm "share of submit-boundary barriers" is already
 *       answered statically by recon (the only vkQueueSubmit2KHR call site
 *       contains no barrier calls), the census confirms the totals live.</li>
 *
 *   <li><b>Caller attribution</b>: the two real-hardware
 *       logs of 2026-11-08 (7951 and 7025 frames) confirmed the funnel census
 *       prediction - exactly one bucket {@code VulkanCommandEncoder.memoryBarrier
 *       srcStage=0x10000 srcAccess=0x18000 ...} carrying ~45 barriers/frame
 *       (82% of the total), which is where any future P2 lever lives. But the
 *       funnel cannot say WHICH vanilla caller emits them. The
 *       per-callsite hooks (see {@code VulkanEncoderBarrierSitesMixin}) tag the
 *       thread with a site label before the funnel runs, so the census reports
 *       {@code submitRenderPass}, {@code writeToBuffer}, {@code endCommandBuffer(init)},
 *       {@code VulkanTransientMemory.endSubmit} &c. as separate buckets; the
 *       masked funnel label remains as the unknown-caller bucket - a caller
 *       without a per-callsite hook (drift, or a foreign mod calling into the
 *       engine) still lands there with live masks, so the recon stays
 *       self-verifying and the total stays exact (every barrier is counted
 *       exactly once - either at its attributed site or as unknown). Since
 *       the dump line always surfaces the unknown-caller aggregate
 *       when non-zero, even beyond the top-N cut - the acceptance runs of
 *       (real-hardware runs: 7862 and 10314 frames) measured zero unknown
 *       barriers and showed the real distribution: the dominant caller is
 *       copyBufferToTexture (~17-22/frame), not submitRenderPass (~4-5/frame)
 *       as the pre-acceptance recon predicted; world streaming bursts reach
 *       ~89 barriers/frame, an idle world drops to ~10.</li>
 *   <li><b>Pipeline create-info timing</b> (b100be3): {@code generateBackendCreateInfo}
 *       total minus the wrapped cache/compile part (ours) = game-side residual
 *       (spvc reflection + slot maps + create-info assembly), plus a direct
 *       {@code generateSlotMap} measurement.</li>
 * </ul>
 *
 * <p>Gating: everything is off unless {@code PearlConfig.verboseLog} is true
 * (config is loaded once, so the check is two volatile reads per hook; with
 * verboseLog=false the cost is one volatile {@link #BROKEN} read). Every hook
 * swallows any throwable and disables the whole instrument for the session
 * after one warn line - diagnostics must never surface.</p>
 *
 * <p>Thread safety: create-info hooks fire on worker threads (pipeline compiles
 * run on an executor) and barrier hooks on the render thread, so all
 * accumulators are atomic/concurrent. Per-call start values travel through
 * ThreadLocals and are always written by the begin hook before the matching
 * end hook on the same thread; a failed begin degrades to no accounting.</p>
 */
public final class Diagnostics {
    /** One window dump line per this many frames (present() calls). */
    private static final int DUMP_EVERY_FRAMES = 1200;
    /** Top-N attributed barrier sites per dump line. */
    private static final int TOP_SITES = 5;

    private static final AtomicBoolean BROKEN = new AtomicBoolean(false);
    private static final AtomicBoolean START_REPORTED = new AtomicBoolean(false);
    private static final AtomicBoolean FINAL_DUMPED = new AtomicBoolean(false);

    // ------------------------------------------------------------------
    // Barrier census (module b47b92e)
    // ------------------------------------------------------------------

    private static final AtomicLong FRAMES = new AtomicLong();
    private static final AtomicLong FRAMES_WITH_BARRIERS = new AtomicLong();
    private static final AtomicLong BARRIERS_TOTAL = new AtomicLong();
    private static final AtomicBoolean BARRIER_SEEN_THIS_FRAME = new AtomicBoolean(false);
    /** Site label -&gt; count; labels are constants or short mask hex, bounded variety. */
    private static final Map<String, LongAdder> BARRIER_SITES = new ConcurrentHashMap<>();
    /**
     * Site label of the barrier currently passing the funnel on
     * this thread. Set by the per-callsite hooks (begin) and always cleared
     * by the same handler (end) on the same thread; barriers never nest. A
     * funnel run without a label = caller without a per-callsite hook -
     * counted under the masked unknown-caller bucket.
     */
    private static final ThreadLocal<String> BARRIER_SITE_TL = new ThreadLocal<>();

    // ------------------------------------------------------------------
    // Pipeline create-info timing (module b100be3)
    // ------------------------------------------------------------------

    private static final AtomicLong CREATE_CALLS = new AtomicLong();
    private static final AtomicLong CREATE_TOTAL_NANOS = new AtomicLong();
    private static final AtomicLong CREATE_OURS_NANOS = new AtomicLong();
    private static final AtomicLong CREATE_MAX_RESIDUAL_NANOS = new AtomicLong();
    /** {startNanos, oursNanos} for one generateBackendCreateInfo call on one thread. */
    private static final ThreadLocal<long[]> CREATE_TL = new ThreadLocal<>();

    private static final AtomicLong SLOTMAP_CALLS = new AtomicLong();
    private static final AtomicLong SLOTMAP_NANOS = new AtomicLong();
    private static final AtomicLong SLOTMAP_MAX_NANOS = new AtomicLong();
    private static final ThreadLocal<Long> SLOTMAP_TL = new ThreadLocal<>();

    private Diagnostics() {
    }

    /**
     * Pure gate: true only when the instrument is enabled for this session.
     * No side effects - safe to call from any number of hot paths.
     */
    private static boolean isEnabled() {
        return !BROKEN.get() && PearlConfig.get().verboseLog;
    }

    /** Prints the one start line and installs the shutdown hook, exactly once. Best-effort. */
    private static void ensureStartReported() {
        if (START_REPORTED.compareAndSet(false, true)) {
            try {
                PearlOptimizer.LOGGER.info("Pearl diag: enabled (verboseLog) - barrier census + pipeline-create timing; diagnostics only, no behaviour change");
                registerShutdownHook();
            } catch (Throwable ignored) {
                // the start line is best-effort; the instrument itself stays usable
            }
        }
    }

    /** Full gate for public hooks: checks enable AND prints the one start line on first true. Never throws. */
    private static boolean gate() {
        if (!isEnabled()) {
            return false;
        }
        ensureStartReported();
        return true;
    }

    private static void disable(String where, Throwable t) {
        if (BROKEN.compareAndSet(false, true)) {
            PearlOptimizer.LOGGER.warn("Pearl diag: disabled for this session after unexpected error in {}", where, t);
        }
    }

    // ------------------------------------------------------------------
    // Barrier hooks (called from passthrough WrapOperation handlers)
    // ------------------------------------------------------------------

    /** One Vulkan barrier recorded at the call site named by the label. */
    public static void onBarrier(String site) {
        if (!gate()) {
            return;
        }
        try {
            recordBarrier(site);
        } catch (Throwable t) {
            disable("barrier", t);
        }
    }

    /**
     * Public gate for passthrough hooks that build a runtime site label
     * (self-verifying recon, the patch-B pattern): {@code true} only when the
     * instrument is on for this session, so the hook can skip all label
     * formatting work (struct reads, string concatenation) while the census
     * is off - the default with {@code verboseLog=false}. Pure check with no
     * side effects; the one start line still prints from the counting funnel
     * itself ({@code onBarrier} and friends go through {@link #gate()}).
     */
    public static boolean censusEnabled() {
        return isEnabled();
    }

    // ------------------------------------------------------------------
    // Caller attribution protocol
    // ------------------------------------------------------------------

    /**
     * Per-callsite hook protocol, begin half: tag this thread with the site
     * label so the funnel (the {@code VulkanCommandEncoder.memoryBarrier}
     * wrap) counts the barrier under it instead of the unknown-caller
     * bucket. No-op while the census is off. Every begin must be paired with
     * an {@link #endBarrierSite()} in a finally block on the same thread.
     */
    public static void beginBarrierSite(String site) {
        if (gate()) {
            BARRIER_SITE_TL.set(site);
        }
    }

    /** Per-callsite hook protocol, end half: always clears the tag. */
    public static void endBarrierSite() {
        BARRIER_SITE_TL.remove();
    }

    /** Site label tagged by the per-callsite hook on this thread, or null. */
    public static String currentBarrierSite() {
        return BARRIER_SITE_TL.get();
    }

    /**
     * Mask-carrying variant for the {@code VulkanGpuTexture.<init>} init
     * barrier: the site label embeds two runtime mask values, and building it
     * costs a string concatenation plus two hex renderings. This overload
     * checks the gate FIRST, so a disabled instrument (the default since
     * verboseLog=false) costs two volatile reads per call and zero formatting
     * work; the label is only built once the instrument is on.
     */
    public static void onBarrierMasks(int srcStageMask, int dstStageMask) {
        if (!gate()) {
            return;
        }
        try {
            recordBarrier("VulkanGpuTexture.<init> srcStage=0x" + Integer.toHexString(srcStageMask)
                + " dstStage=0x" + Integer.toHexString(dstStageMask));
        } catch (Throwable t) {
            disable("barrier", t);
        }
    }

    private static void recordBarrier(String site) {
        BARRIERS_TOTAL.incrementAndGet();
        BARRIER_SITES.computeIfAbsent(site, k -> new LongAdder()).increment();
        BARRIER_SEEN_THIS_FRAME.set(true);
    }

    /** Frame boundary: called at the head of the surface present(). */
    public static void onFrame() {
        if (!gate()) {
            return;
        }
        try {
            long frames = FRAMES.incrementAndGet();
            if (BARRIER_SEEN_THIS_FRAME.getAndSet(false)) {
                FRAMES_WITH_BARRIERS.incrementAndGet();
            }
            if (frames % DUMP_EVERY_FRAMES == 0) {
                PearlOptimizer.LOGGER.info("Pearl diag [{}]: {}", "barriers/" + DUMP_EVERY_FRAMES + " frames", barrierSummary());
            }
        } catch (Throwable t) {
            disable("frame", t);
        }
    }

    // ------------------------------------------------------------------
    // generateBackendCreateInfo hooks (b100be3)
    // ------------------------------------------------------------------

    /** HEAD of generateBackendCreateInfo: starts the total timer. */
    public static void onCreateInfoBegin() {
        if (!gate()) {
            return;
        }
        try {
            CREATE_TL.set(new long[]{System.nanoTime(), 0L});
        } catch (Throwable t) {
            disable("createInfoBegin", t);
        }
    }

    /**
     * Called by the existing compileToSpv wrap right before it invokes the
     * cache/compile path. Returns the start stamp, or 0 when disabled (the
     * caller skips the matching End call on 0).
     */
    public static long onCreateInfoOursBegin() {
        if (!isEnabled()) {
            return 0L;
        }
        return System.nanoTime();
    }

    /** Called by the existing wrap after loadOrCompile returned: stores "ours". */
    public static void onCreateInfoOursEnd(long t0) {
        if (t0 == 0L) {
            return;
        }
        try {
            long[] slot = CREATE_TL.get();
            if (slot != null) {
                slot[1] = System.nanoTime() - t0;
            }
        } catch (Throwable t) {
            disable("createInfoOursEnd", t);
        }
    }

    /** RETURN of generateBackendCreateInfo: residual = total - ours. */
    public static void onCreateInfoEnd() {
        try {
            long[] slot = CREATE_TL.get();
            if (slot == null) {
                return;
            }
            CREATE_TL.remove();
            if (!isEnabled()) {
                return;
            }
            long total = Math.max(0L, System.nanoTime() - slot[0]);
            long ours = Math.max(0L, slot[1]);
            long residual = Math.max(0L, total - ours);
            CREATE_CALLS.incrementAndGet();
            CREATE_TOTAL_NANOS.addAndGet(total);
            CREATE_OURS_NANOS.addAndGet(ours);
            CREATE_MAX_RESIDUAL_NANOS.updateAndGet(prev -> Math.max(prev, residual));
        } catch (Throwable t) {
            disable("createInfoEnd", t);
        }
    }

    // ------------------------------------------------------------------
    // generateSlotMap hooks (b100be3: direct reflection measurement)
    // ------------------------------------------------------------------

    /** HEAD of generateSlotMap. */
    public static void onSlotMapBegin() {
        if (!gate()) {
            return;
        }
        try {
            SLOTMAP_TL.set(System.nanoTime());
        } catch (Throwable t) {
            disable("slotMapBegin", t);
        }
    }

    /** RETURN of generateSlotMap. */
    public static void onSlotMapEnd() {
        try {
            Long start = SLOTMAP_TL.get();
            if (start == null) {
                return;
            }
            SLOTMAP_TL.remove();
            if (!isEnabled()) {
                return;
            }
            long nanos = Math.max(0L, System.nanoTime() - start);
            SLOTMAP_CALLS.incrementAndGet();
            SLOTMAP_NANOS.addAndGet(nanos);
            SLOTMAP_MAX_NANOS.updateAndGet(prev -> Math.max(prev, nanos));
        } catch (Throwable t) {
            disable("slotMapEnd", t);
        }
    }

    // ------------------------------------------------------------------
    // Dumps
    // ------------------------------------------------------------------

    /** Start of a resource reload: dump the finished phase, reset its counters. */
    public static void onReloadStart() {
        if (!isEnabled()) {
            return;
        }
        try {
            if (CREATE_CALLS.get() > 0 || SLOTMAP_CALLS.get() > 0) {
                PearlOptimizer.LOGGER.info("Pearl diag [createInfo/reload boundary]: {}", createInfoSummary());
                PearlOptimizer.LOGGER.info("Pearl diag [slotMap/reload boundary]: {}", slotMapSummary());
            }
            CREATE_CALLS.set(0);
            CREATE_TOTAL_NANOS.set(0);
            CREATE_OURS_NANOS.set(0);
            CREATE_MAX_RESIDUAL_NANOS.set(0);
            SLOTMAP_CALLS.set(0);
            SLOTMAP_NANOS.set(0);
            SLOTMAP_MAX_NANOS.set(0);
        } catch (Throwable t) {
            disable("reloadStart", t);
        }
    }

    /** Device close (Vulkan backend): final barrier totals + final phase. */
    public static void dumpAtClose() {
        if (!isEnabled()) {
            return;
        }
        try {
            if (FINAL_DUMPED.compareAndSet(false, true)) {
                PearlOptimizer.LOGGER.info("Pearl diag [barriers/total]: {}", barrierSummary());
                // store health in the standard diag report - the
                // chronic-failure latch of the disk writer is visible without
                // log forensics.
                PearlOptimizer.LOGGER.info("Pearl diag [store]: {}", storeHealthSummary());
                if (CREATE_CALLS.get() > 0 || SLOTMAP_CALLS.get() > 0) {
                    PearlOptimizer.LOGGER.info("Pearl diag [createInfo/final phase]: {}", createInfoSummary());
                    PearlOptimizer.LOGGER.info("Pearl diag [slotMap/final phase]: {}", slotMapSummary());
                }
            }
        } catch (Throwable t) {
            disable("dumpAtClose", t);
        }
    }

    /**
     * Best-effort final dump for sessions without a Vulkan device close
     * (OpenGL backend): registered once the instrument first fires. Uses
     * System.out because the logging backend may already be stopping.
     */
    private static void registerShutdownHook() {
        try {
            Thread hook = new Thread(() -> {
                try {
                    if (BROKEN.get() || FINAL_DUMPED.get()) {
                        return;
                    }
                    boolean noCensus = BARRIERS_TOTAL.get() == 0 && CREATE_CALLS.get() == 0 && SLOTMAP_CALLS.get() == 0;
                    // the store health line is printed when the
                    // census produced anything OR the store latch tripped / any
                    // write failed - store trouble must not hide behind an empty
                    // census.
                    boolean storeTrouble = ShaderCacheManager.storesFailedTotal() > 0
                        || ShaderCacheManager.storeLatchEngaged();
                    if (noCensus && !storeTrouble) {
                        return;
                    }
                    if (!noCensus) {
                        System.out.println("Pearl diag [shutdown barriers/total]: " + barrierSummary());
                        if (CREATE_CALLS.get() > 0 || SLOTMAP_CALLS.get() > 0) {
                            System.out.println("Pearl diag [shutdown createInfo]: " + createInfoSummary());
                            System.out.println("Pearl diag [shutdown slotMap]: " + slotMapSummary());
                        }
                    }
                    System.out.println("Pearl diag [shutdown store]: " + storeHealthSummary());
                } catch (Throwable ignored) {
                    // a diagnostics shutdown hook must never interfere with exit
                }
            }, "pearl-optimizer-diag-shutdown");
            hook.setDaemon(true);
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Line builders (every derived number carries its formula in the line)
    // ------------------------------------------------------------------

    private static String barrierSummary() {
        long frames = FRAMES.get();
        long total = BARRIERS_TOTAL.get();
        StringBuilder sb = new StringBuilder(192);
        sb.append("frames=").append(frames).append(", barriers=").append(total);
        if (frames > 0) {
            sb.append(String.format(Locale.ROOT, " (avg=%.2f barriers/frame", (double) total / (double) frames));
            long withBarriers = FRAMES_WITH_BARRIERS.get();
            sb.append(String.format(Locale.ROOT, ", framesWithBarriers=%d (%.1f%% of frames)", withBarriers, 100.0d * (double) withBarriers / (double) frames));
            sb.append(')');
        }
        appendSites(sb, TOP_SITES);
        return sb.toString();
    }

    /**
     * Appends the per-callsite breakdown to a census line. The
     * top-N cut is spent on attributed sites only: the masked unknown-caller
     * variants (one bucket per live {@code VkDependencyInfo} mask combination)
     * are aggregated into a single {@code (unknown-caller=N)} bucket that is
     * always printed when non-zero, even beyond the top-N cut - the INSTRUKCIYA
     * drift alarm ("заметный бакет unknown-caller") must stay mechanically
     * visible. Before this change an unknown variant ranked 6th+ was folded
     * into {@code (other sites=…)} and the alarm could not fire from the log
     * alone. With several mask variants the largest one keeps its full label
     * so the recon data (live masks) survives the aggregation. Every barrier
     * still lands in exactly one displayed bucket: a top-N attributed site,
     * the unknown-caller aggregate, or other sites. Line builder only - no
     * counting logic changed, totals stay exact.
     */
    private static void appendSites(StringBuilder sb, int topN) {
        List<Map.Entry<String, LongAdder>> sites = new ArrayList<>(BARRIER_SITES.entrySet());
        if (sites.isEmpty()) {
            return;
        }
        sites.sort(Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> e.getValue().sum()).reversed());
        sb.append(", sites:");
        int printed = 0;
        long others = 0;
        long unknown = 0;
        int unknownVariants = 0;
        long unknownLargestCount = 0;
        String unknownLargestLabel = null;
        for (Map.Entry<String, LongAdder> e : sites) {
            long count = e.getValue().sum();
            if (count == 0) {
                continue;
            }
            if (e.getKey().contains("(unknown-caller)")) {
                unknown += count;
                unknownVariants++;
                if (count > unknownLargestCount) {
                    unknownLargestCount = count;
                    unknownLargestLabel = e.getKey();
                }
            } else if (printed < topN) {
                sb.append(' ').append(e.getKey()).append('=').append(count);
                printed++;
            } else {
                others += count;
            }
        }
        if (unknown > 0) {
            sb.append(" (unknown-caller=").append(unknown);
            if (unknownVariants > 1) {
                sb.append("; largest: ").append(unknownLargestLabel)
                  .append('=').append(unknownLargestCount);
            }
            sb.append(')');
        }
        if (others > 0) {
            sb.append(" (other sites=").append(others).append(')');
        }
    }

    private static String createInfoSummary() {
        long calls = CREATE_CALLS.get();
        double totalMs = CREATE_TOTAL_NANOS.get() / 1_000_000.0d;
        double oursMs = CREATE_OURS_NANOS.get() / 1_000_000.0d;
        double residualMs = (CREATE_TOTAL_NANOS.get() - CREATE_OURS_NANOS.get()) / 1_000_000.0d;
        double maxResidualMs = CREATE_MAX_RESIDUAL_NANOS.get() / 1_000_000.0d;
        return String.format(Locale.ROOT,
            "calls=%d, total=%.1f ms, ours(cache/compile)=%.1f ms, residual=%.1f ms (residual=total-ours), residualMax=%.2f ms",
            calls, totalMs, oursMs, residualMs, maxResidualMs);
    }

    private static String slotMapSummary() {
        long calls = SLOTMAP_CALLS.get();
        double sumMs = SLOTMAP_NANOS.get() / 1_000_000.0d;
        double maxMs = SLOTMAP_MAX_NANOS.get() / 1_000_000.0d;
        return String.format(Locale.ROOT, "calls=%d, sum=%.1f ms, max=%.2f ms", calls, sumMs, maxMs);
    }

    /**
     * One-line store health for the standard diag report -
     * the chronic-failure latch of the disk writer is visible without log
     * forensics. Read-only view of the {@link ShaderCacheManager} counters;
     * healthy = {@code failedTotal=0, failureStreak=0, latch=ok}.
     */
    private static String storeHealthSummary() {
        return "failedTotal=" + ShaderCacheManager.storesFailedTotal()
            + ", failureStreak=" + ShaderCacheManager.storesFailureStreak()
            + ", latch=" + (ShaderCacheManager.storeLatchEngaged()
                ? "engaged (disk writes off until next launch)" : "ok");
    }
}
