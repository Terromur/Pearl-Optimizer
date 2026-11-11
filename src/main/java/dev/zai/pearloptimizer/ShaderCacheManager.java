package dev.zai.pearloptimizer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import dev.zai.pearloptimizer.mixin.GlslCompilerAccessor;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import net.fabricmc.loader.api.FabricLoader;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Persistent disk cache for the GLSL -&gt; SPIR-V compilation step.
 *
 * <p>Vanilla 26.3 compiles every renderpearl shader from GLSL through shaderc
 * on every single launch (inside {@code PipelineBuilder.generateBackendCreateInfo}).
 * That is pure CPU work with a bit-identical result as long as the inputs did
 * not change. This class persists the resulting SPIR-V binary under
 * {@code cache/pearl-optimizer/shader-cache/} and feeds it straight into a
 * {@link SPIRVModule} on the next launch.</p>
 *
 * <p>Cache key = SHA-256 over: the GLSL source, the shader name, {@code #define}
 * directives, shader type, the three device-dependent compiler flags
 * ({@code isZeroToOne}, {@code shaderDrawParameters},
 * {@code isExplicitDepthRequired()}), the
 * effective shaderc profile (optimization level + debug info), the game
 * version, a cache format version and a fingerprint of every include file
 * seen since the last resource reload. Any change to any component produces a
 * different key, so a stale hit is impossible by construction; on top of that
 * every hit is integrity-checked against the stored SHA-256 and the SPIR-V
 * magic number. The cache is additionally only reachable for the vanilla
 * shader resolver: a foreign {@link ShaderSource} always compiles vanilla.</p>
 *
 * <p>The cache also enforces the SPIR-V interface guard of
 * {@link ShaderOptimization}: every freshly compiled optimized module is
 * verified before it reaches any cache layer or the game (a violated module
 * is recompiled in the exact vanilla profile and stored under the vanilla
 * key), and every optimized disk/prefetch entry is re-verified on load -
 * a stale optimized entry persisted by a pre-guard session can never be
 * served. Vanilla-profile entries are the game's own bytes and are never
 * guarded (vanilla is safe by definition).</p>
 *
 * <p>Every failure mode degrades to the vanilla path: a corrupt or missing
 * file is a cache miss, and any unexpected throwable disables the cache for
 * the rest of the session and replays the original compile.</p>
 *
 * <p>Flag {@code spirvMemoryCache} (requires {@code shaderCache}): the
 * compiled SPIR-V is additionally deduplicated in memory under the same
 * key: a hit rebuilds the module from an independent LWJGL buffer without
 * touching the disk or shaderc. The map is capped at 128 MB; when the cap
 * would be exceeded, the least-recently-accessed entries are evicted
 * individually (no "cold window" from a whole-map clear), so the entries
 * that are actually being re-read stay warm. The whole map is still dropped after the
 * {@code BROKEN} latch, when the session ends, and by the test reset. It is
 * deliberately not cleared on resource reloads: changed resources simply
 * produce different keys, so stale entries are never read again and LRU
 * evicts them once the budget pressure arrives.</p>
 */
public final class ShaderCacheManager {
    /**
     * Bump on any change to the key inputs. Entries written by an older
     * format miss by key and are purged as garbage.
     */
    private static final int CACHE_FORMAT = 4;

    /** Include id -&gt; SHA-256(content), filled during resource reloads. */
    private static final ConcurrentHashMap<Identifier, byte[]> INCLUDE_HASHES = new ConcurrentHashMap<>();
    /** Disabled permanently after an unexpected error (not after plain misses). */
    private static final AtomicBoolean BROKEN = new AtomicBoolean(false);
    /** One warn per session. */
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();

    // ------------------------------------------------------------------
    // In-memory SPIR-V deduplication (flag: spirvMemoryCache)
    // ------------------------------------------------------------------

    /** Total budget of the in-memory map; on exceed the least-recently-accessed entries are evicted. */
    private static final long MEMORY_LIMIT_BYTES = 128L * 1024L * 1024L;
    /** Session-scoped key -&gt; independent SPIR-V snapshot; values are never mutated and never exposed. */
    private static final ConcurrentHashMap<String, byte[]> MEMORY_SPIRV = new ConcurrentHashMap<>();
    /**
     * LRU access stamp per key (nanoTime): refreshed on every memory hit and
     * every insert; the overflow eviction removes the smallest stamps first. Advisory only - a stamp racing a concurrent eviction can be
     * recreated for an already-removed key (bounded by one stale entry per
     * race, never read, cleaned up by the next session/latch clear).
     */
    private static final ConcurrentHashMap<String, AtomicLong> MEMORY_LAST_ACCESS = new ConcurrentHashMap<>();
    /** Sum of the stored snapshot sizes; grows only for a won putIfAbsent race. */
    private static final AtomicLong MEMORY_TOTAL_BYTES = new AtomicLong();
    /** RAM hits (log marker "(memory)"); HITS stays disk-only, MISSES stays shaderc-only. */
    private static final AtomicLong MEMORY_HITS = new AtomicLong();
    /** Exactly one start line per session. */
    private static final AtomicBoolean MEMORY_START_REPORTED = new AtomicBoolean(false);

    // ------------------------------------------------------------------
    // Purge throttle (flag: purgeThrottle)
    // ------------------------------------------------------------------

    /** Bytes fully persisted to disk since the last purge walk (see store()). */
    private static final AtomicLong PURGE_BYTES_SINCE = new AtomicLong();
    /** False until the unconditional first walk of the session has been taken. */
    private static final AtomicBoolean PURGE_FIRST_DONE = new AtomicBoolean(false);
    /** Single-flight flag: at most one walk runs at a time. */
    private static final AtomicBoolean PURGE_IN_FLIGHT = new AtomicBoolean(false);
    /** Report counters: walks executed / throttle skips. Never printed to the log. */
    private static final AtomicLong PURGE_WALKS = new AtomicLong();
    private static final AtomicLong PURGE_SKIPPED = new AtomicLong();

    // ------------------------------------------------------------------
    // In-flight compilation single-flight (flag: inflightDedup)
    // ------------------------------------------------------------------

    /**
     * Waiter wait bound, sized far above the slowest observed compile (43 ms). Package-private so the smoke suite can shrink it; not a user flag.
     */
    static volatile long INFLIGHT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
    /**
     * Key -&gt; in-flight claim. The map NEVER stores results: an entry exists only
     * while the winner compiles and is removed (identity) right after the future
     * settles and the memory layer is fed, so a late caller always lands in the
     * ordinary cache layers and can never observe a half-written state or a
     * re-ownable key.
     */
    private static final ConcurrentHashMap<String, InflightClaim> IN_FLIGHT = new ConcurrentHashMap<>();
    /** Report counters: waiters served from the winner's compile / waiter detachments. Never printed to the log. */
    private static final AtomicLong DEDUP_HITS = new AtomicLong();
    private static final AtomicLong DEDUP_TIMEOUTS = new AtomicLong();

    // ------------------------------------------------------------------
    // Disk-load single-flight + warm prefetch (flags: inflightDedup,
    // spirvMemoryCache)
    // ------------------------------------------------------------------

    /**
     * Key -&gt; in-flight disk load, same contract as {@link #IN_FLIGHT} but for
     * reads: an entry exists only while one thread reads a SPIR-V entry from
     * disk, waiters receive the SAME byte[] snapshot (immutable, never
     * mutated, never exposed) and rebuild their own module from it - exactly
     * what the disk-hit path already did with its private copy. Removes
     * duplicate disk reads of one key and the per-waiter read latency.
     */
    private static final ConcurrentHashMap<String, CompletableFuture<DiskEntry>> LOADS_IN_FLIGHT =
        new ConcurrentHashMap<>();
    /** Report counter: waiters served from a shared disk load. Never printed to the log. */
    private static final AtomicLong LOAD_DEDUP_HITS = new AtomicLong();

    /**
     * Stale optimized entries rejected by the interface guard this session
     * (disk loads + prefetch; fresh-compile violations are counted in
     * {@link ShaderOptimization#guardViolations()}). Report counter for
     * Diagnostics/tests, never printed per entry; the per-entry load-path
     * rejection WARNs once with the key.
     */
    private static final AtomicLong GUARD_STALE_REJECTED = new AtomicLong();

    /** Exactly one warm prefetch per session. */
    private static final AtomicBoolean PREFETCH_KICKED = new AtomicBoolean(false);

    // ------------------------------------------------------------------
    // Async store bookkeeping (flag: asyncStore)
    // ------------------------------------------------------------------

    /** Tasks accepted by the background writer (async dispatches only). */
    private static final AtomicLong STORES_QUEUED = new AtomicLong();
    /** Entries fully persisted (both atomic moves completed), sync path included. */
    private static final AtomicLong STORES_WRITTEN = new AtomicLong();
    /** Entries whose disk write threw (sync path included); invisible next launch (plain miss). */
    private static final AtomicLong STORES_FAILED = new AtomicLong();
    /** The JVM-exit drain hook is registered once, on the first async dispatch. */
    private static final AtomicBoolean STORE_DRAIN_HOOK_INSTALLED = new AtomicBoolean(false);

    // ------------------------------------------------------------------
    // Store health: drain feedback + chronic-failure latch
    // ------------------------------------------------------------------

    /**
     * Consecutive failed writes tolerated before the store latch trips.
     * Three = survive a one-off hiccup (any successful write resets the
     * streak), but stop hammering a degraded target (full disk, read-only
     * mount) after the third failure in a row. Package-private so the smoke
     * suite can shrink it; not a user flag.
     */
    static volatile int STORE_LATCH_THRESHOLD = 3;
    /** Failure latch: once set, {@code store()} is a no-op for the rest of the session; loads and the memory layer are untouched. */
    private static final AtomicBoolean STORE_DISABLED = new AtomicBoolean(false);
    /** Consecutive failed writes (any success resets to zero); reaching the threshold trips {@link #STORE_DISABLED}. */
    private static final AtomicLong STORE_FAILURE_STREAK = new AtomicLong();
    /** First failed key of the session, for the one drain WARN. */
    private static final AtomicReference<String> FIRST_FAILED_KEY = new AtomicReference<>();
    /** Exception class of the first failed write, for the one drain WARN. */
    private static final AtomicReference<String> FIRST_FAILURE_CAUSE = new AtomicReference<>();

    // ------------------------------------------------------------------
    // Prefetch observability counters
    // ------------------------------------------------------------------

    /** Report counter: prefetch entries rejected by the integrity check (damaged / foreign format / unreadable). Printed in the prefetch line; healthy = 0. */
    private static final AtomicLong PREFETCH_INVALID = new AtomicLong();
    /** Report counter: prefetch entries never examined because the budget guard or a broken session stopped the loop. Printed in the prefetch line; healthy = 0. */
    private static final AtomicLong PREFETCH_TRUNCATED = new AtomicLong();
    /**
     * Report counter: optimized prefetch entries skipped because the session's optimized profile is
     * definitively off (config disabled, or the guard disarmed) - such entries can never be served
     * (their cache keys embed the optimized profile, which isActive() no longer produces), so warming
     * them would only fill the RAM budget with un-hittable entries and evict servable vanilla ones.
     * Printed in the prefetch line ONLY when non-zero, so a healthy session's line stays byte-identical
     * to the historical format the acceptance greps assert on.
     */
    private static final AtomicLong PREFETCH_PROFILE_SKIPPED = new AtomicLong();

    /** One in-flight compilation: the shared SPIR-V snapshot future plus the owning thread (reentrancy guard). */
    private record InflightClaim(CompletableFuture<byte[]> future, Thread owner) {
    }

    /**
     * Marker for a waiter rethrowing the in-flight winner's failure. The outer
     * error contract must NOT apply the vanilla {@code original.call} retry to
     * it: waiters never launch a second compilation, they only rethrow the
     * winner's failure.
     */
    private static final class WaiterFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        WaiterFailure(Throwable cause) {
            super(cause);
        }
    }

    private ShaderCacheManager() {
    }

    // ------------------------------------------------------------------
    // SPIR-V interface guard
    // ------------------------------------------------------------------

    /**
     * Disk load result: the SPIR-V bytes plus the profile flag from the meta
     * ({@code "optimized"}: true = written under the PERFORMANCE profile).
     * Only optimized entries are guard-checked on load - vanilla-profile
     * entries are the game's own bytes and safe by definition.
     */
    private record DiskEntry(byte[] bytes, boolean optimized) {
    }

    /**
     * The post-guard compile outcome: the module to serve/store plus the
     * store coordinates. Identity (same key/paths, optimized=true) when the
     * guard passed; the vanilla module under the vanilla key (optimized=
     * false) after a guard fallback, so the optimized bytes never reach any
     * cache layer and the next session's vanilla key already has an entry.
     */
    private record Guarded(SpvModule module, String key, Path spvFile, Path metaFile, boolean optimized) {
    }

    /**
     * Runs the interface guard on one freshly compiled optimized module and
     * returns the vanilla fallback module, or {@code null} when the optimized
     * module is safe. The fallback recompile runs with the vanilla profile
     * forced through the {@link ShaderOptimization} thread-local (the mixin
     * then leaves the vanilla level untouched for exactly this compile),
     * counts the violation (three violations disarm the optimized profile for
     * the session) and WARNs with the shader name, the reason and the timing.
     *
     * @throws Throwable the vanilla recompile failure - the caller's existing
     *                   owner-failure contract handles it (same as any compile)
     */
    private static SpvModule recompileVanillaIfUnsafe(
        GlslCompiler compiler, String shaderName, String glsl, ShaderType type,
        ShaderDefines defines, ShaderSource source, Operation<SpvModule> original,
        SpvModule optimizedModule
    ) {
        byte[] spv = spirvSnapshot(optimizedModule);
        String violation = spv == null
            ? "SPIR-V snapshot unavailable"
            : ShaderOptimization.guardModuleInterface(spv);
        if (violation == null) {
            return null; // interface verified safe for the game's reflection
        }
        ShaderOptimization.noteGuardViolation(violation);
        long t0 = System.nanoTime();
        ShaderOptimization.beginVanillaCompile();
        try {
            SpvModule vanilla = original.call(compiler, shaderName, glsl, type, defines, source);
            PearlOptimizer.LOGGER.warn("Shader optimization guard: {} {} violates the SPIR-V input interface ({}); "
                + "recompiled in the vanilla profile in {} ms",
                type, shaderName, violation, (System.nanoTime() - t0) / 1_000_000);
            return vanilla;
        } finally {
            ShaderOptimization.endVanillaCompile();
        }
    }

    /**
     * Guard gate for the cache-managed compile paths. The decision
     * flag is the one captured with the KEY (not a fresh isActive() read): a
     * mid-flight disarm must never let an optimized-compiled module skip the
     * guard and be stored under a vanilla label.
     */
    private static Guarded guardOrVanilla(
        GlslCompiler compiler, String shaderName, String glsl, ShaderType type,
        ShaderDefines defines, ShaderSource source, Operation<SpvModule> original,
        SpvModule module, String key, Path spvFile, Path metaFile,
        String definesText, boolean zeroToOne, boolean drawParams, boolean compiledOptimized
    ) {
        if (!compiledOptimized) {
            return new Guarded(module, key, spvFile, metaFile, false);
        }
        SpvModule vanilla = recompileVanillaIfUnsafe(compiler, shaderName, glsl, type, defines, source, original, module);
        if (vanilla == null) {
            return new Guarded(module, key, spvFile, metaFile, true);
        }
        String vanillaKey = computeKey(shaderName, glsl, type, definesText, zeroToOne, drawParams, false);
        return new Guarded(vanilla, vanillaKey,
            fileForOrNull(vanillaKey, ".spv"), fileForOrNull(vanillaKey, ".meta.json"), false);
    }

    /**
     * Guard wrapper for the UNCACHED compile paths (cache disabled by config
     * or latched {@code BROKEN}, foreign resolver): the optimized profile is
     * decided here and the module is verified before it is returned, so the
     * per-module guard covers the whole game even when no cache layer is
     * involved. Nothing is stored or counted beyond the guard counters.
     */
    private static SpvModule guardedVanillaBypass(
        GlslCompiler compiler, String shaderName, String glsl, ShaderType type,
        ShaderDefines defines, ShaderSource source, Operation<SpvModule> original
    ) {
        boolean optimized = ShaderOptimization.isActive();
        SpvModule module = original.call(compiler, shaderName, glsl, type, defines, source);
        if (!optimized) {
            return module;
        }
        SpvModule vanilla = recompileVanillaIfUnsafe(compiler, shaderName, glsl, type, defines, source, original, module);
        return vanilla == null ? module : vanilla;
    }

    // ------------------------------------------------------------------
    // Include tracking (fed by CachedIncludeSourceMixin + ShaderManagerMixin)
    // ------------------------------------------------------------------

    /** Called from the resource reload thread for every include file it loads. */
    public static void recordInclude(Identifier id, String content) {
        if (id == null || content == null) {
            return;
        }
        try {
            INCLUDE_HASHES.put(id, PearlUtil.sha256(content));
            INCLUDE_FINGERPRINT = null; // the include set changed - recompute on next use
        } catch (Throwable ignored) {
        }
    }

    /** Called at the start of every resource reload: includes are re-read from packs. */
    public static void beginShaderReload() {
        INCLUDE_HASHES.clear();
        INCLUDE_FINGERPRINT = null;
    }

    /**
     * Memoized {@link #computeFingerprint()} value; {@code null} when the
     * include set changed since the last computation. Building the value
     * costs a full TreeMap walk + string render + SHA-256 per call, so both
     * call sites (one per shader compile, one per store) work off this single
     * volatile read on the warm path.
     */
    private static volatile String INCLUDE_FINGERPRINT;

    /** Deterministic fingerprint over all includes currently known. */
    public static String includeFingerprint() {
        String cached = INCLUDE_FINGERPRINT;
        if (cached != null) {
            return cached;
        }
        String computed = computeFingerprint();
        INCLUDE_FINGERPRINT = computed;
        return computed;
    }

    private static String computeFingerprint() {
        if (INCLUDE_HASHES.isEmpty()) {
            return "none";
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<Identifier, byte[]> e : INCLUDE_HASHES.entrySet()) {
            sorted.put(e.getKey().toString(), PearlUtil.hex(e.getValue()));
        }
        StringBuilder sb = new StringBuilder(sorted.size() * 96);
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        return PearlUtil.hex(PearlUtil.sha256(sb.toString()));
    }

    // ------------------------------------------------------------------
    // Vanilla resolver policy
    // ------------------------------------------------------------------

    /**
     * Accepted resolver classes, resolved once through reflection so this class
     * keeps no compile-time dependency on {@code ShaderManager} (the smoke
     * harnesses compile this file verbatim against stubs). An empty array means
     * the vanilla resolver could not be identified: every non-null source is
     * then treated as foreign (vanilla compile, cache bypassed) - the fail-safe
     * direction, visible as a warm-session shaderc regression on the stand.
     */
    private static volatile Class<?>[] VANILLA_RESOLVER_CLASSES;
    /** One warn per session for the unidentified-resolver case. */
    private static final AtomicBoolean RESOLVER_WARNED = new AtomicBoolean(false);

    /** {@code true} when {@code source} belongs to the vanilla resolver class set. Never throws. */
    private static boolean isVanillaShaderResolver(ShaderSource source) {
        try {
            Class<?>[] accepted = VANILLA_RESOLVER_CLASSES;
            if (accepted == null) {
                accepted = resolveVanillaResolverClasses();
                VANILLA_RESOLVER_CLASSES = accepted;
            }
            for (Class<?> candidate : accepted) {
                if (candidate.isInstance(source)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false; // fail-open: vanilla compile, cache bypassed
        }
    }

    /**
     * Finds the vanilla {@code ShaderSource} implementation(s) nested in
     * {@code ShaderManager} (the immutable {@code Configs} record).
     * Class.forName + getDeclaredClasses only - no compile-time reference.
     */
    private static Class<?>[] resolveVanillaResolverClasses() {
        try {
            Class<?> manager = Class.forName("net.minecraft.client.renderer.ShaderManager");
            List<Class<?>> found = new ArrayList<>();
            for (Class<?> nested : manager.getDeclaredClasses()) {
                if (ShaderSource.class.isAssignableFrom(nested)) {
                    found.add(nested);
                }
            }
            if (!found.isEmpty()) {
                if (PearlConfig.get().verboseLog) {
                    StringBuilder names = new StringBuilder();
                    for (Class<?> c : found) {
                        if (names.length() > 0) {
                            names.append(", ");
                        }
                        names.append(c.getName());
                    }
                    PearlOptimizer.LOGGER.info("Shader cache: include-source binding accepts vanilla resolver(s): {}", names);
                }
                return found.toArray(new Class<?>[0]);
            }
            // Fallback: the contract name (ShaderManager.Configs).
            Class<?> byName = Class.forName("net.minecraft.client.renderer.ShaderManager$Configs");
            if (ShaderSource.class.isAssignableFrom(byName)) {
                return new Class<?>[]{byName};
            }
        } catch (Throwable ignored) {
            // fall through to the warn + empty policy
        }
        if (RESOLVER_WARNED.compareAndSet(false, true)) {
            PearlOptimizer.LOGGER.warn(
                "Shader cache: could not identify the vanilla shader resolver (ShaderManager.Configs); "
                    + "every non-null ShaderSource compiles vanilla, so warm shader-cache hits stay "
                    + "disabled until the resolver class list is fixed");
        }
        return new Class<?>[0];
    }

    // ------------------------------------------------------------------
    // The hook used by PipelineBuilderMixin
    // ------------------------------------------------------------------

    public static SpvModule loadOrCompile(
        GlslCompiler compiler,
        // Positional contract with PearlBench (ShaderCheck calls reflectively):
        // argument 2 is the shader NAME, argument 3 is the GLSL source - exactly
        // the vanilla compileToSpv(shaderName, glsl, ...) order. Renaming these
        // parameters is safe; changing types, order or the class name is not
        // (the test stand then skips the auto-check).
        String shaderName,
        String glsl,
        ShaderType type,
        ShaderDefines defines,
        ShaderSource source,
        Operation<SpvModule> original
    ) {
        PearlConfig config = PearlConfig.get();
        if (!config.shaderCache || BROKEN.get()) {
            // the interface guard covers the uncached path as well -
            // the optimized profile is decided and re-verified here, not only
            // in the cache-managed path below.
            return guardedVanillaBypass(compiler, shaderName, glsl, type, defines, source, original);
        }
        // With shaderOptimization the vanilla compile itself is adjusted upstream
        // (ShaderOptimizationMixin); here we only care about caching the result.
        try {
            reportStart(config);
            // the include fingerprint is a global, content-addressed
            // table fed by CachedIncludeSource.create. For vanilla compiles the
            // recorder (the resource reload) and the consumer (the immutable
            // ShaderManager.Configs) are the same party, so the fingerprint
            // describes the include set this compilation actually resolves
            // through. A FOREIGN ShaderSource (another mod, a test harness) can
            // present different content for the same include id while the table
            // still holds someone else's content - such a compile must never
            // touch the cache, otherwise it can be served a foreign SPIR-V
            // (PearlBench resolver_collision, exit code 2). A null source has no
            // resolver at all: vanilla always passes one, so the only null-source
            // callers are include-free stubs - caching those stays sound.
            if (source != null && !isVanillaShaderResolver(source)) {
                // Foreign compile: not cached, but still guarded (the mixin
                // applies the optimized profile to these compiles too).
                return guardedVanillaBypass(compiler, shaderName, glsl, type, defines, source, original);
            }
            boolean zeroToOne = compiler != null && ((GlslCompilerAccessor) (Object) compiler).pearl$isZeroToOne();
            boolean drawParams = compiler != null && ((GlslCompilerAccessor) (Object) compiler).pearl$shaderDrawParameters();
            DeviceMarkersAudit.reportOnce(zeroToOne, drawParams); // diagnostic only
            String definesText;
            try {
                definesText = defines == null ? "" : defines.asSourceDirectives();
            } catch (Throwable t) {
                definesText = String.valueOf(defines);
            }
            // Captured ONCE: the key, the compile and the guard must agree on
            // the profile even if the guard disarms mid-flight.
            boolean optimizedProfile = ShaderOptimization.isActive();
            String key = computeKey(shaderName, glsl, type, definesText, zeroToOne, drawParams, optimizedProfile);

            // Module "spirvMemoryCache": first chance for the in-memory layer.
            // Same key as the disk cache; a hit skips fileFor/disk/shaderc and
            // rebuilds the module from an independent LWJGL buffer.
            long t0 = System.nanoTime();
            if (config.spirvMemoryCache) {
                byte[] mem = MEMORY_SPIRV.get(key);
                if (mem != null) {
                    touchMemoryKey(key); // 5.3: refresh the LRU access stamp
                    SpvModule module = rebuildModule(mem, type);
                    if (module != null) {
                        MEMORY_HITS.incrementAndGet();
                        if (config.verboseLog) {
                            PearlOptimizer.LOGGER.info("Shader cache HIT {} {} ({} KB, loaded in {} ms) (memory)",
                                type, shortKey(key), Math.max(1, mem.length / 1024), (System.nanoTime() - t0) / 1_000_000);
                        }
                        return module;
                    }
                    // Reconstruction sanity check failed: fall through to disk and
                    // the vanilla compile; the memory entry is deliberately kept.
                }
            }

            Path spvFile = fileForOrNull(key, ".spv");
            Path metaFile = fileForOrNull(key, ".meta.json");

            DiskEntry entry = config.inflightDedup
                ? loadShared(key, spvFile, metaFile)
                : loadEntryIfValid(spvFile, metaFile);
            if (entry != null) {
                byte[] cached = entry.bytes();
                // optimized entries are re-verified on load. A stale
                // optimized entry (a pre-guard session could persist one) is
                // never served; it is NOT deleted (the purge machinery owns
                // deletion) and is NOT counted as a fresh-compile violation
                // (it says nothing about the current compiler) - the fresh
                // compile below overwrites it whenever the profile is active.
                String loadViolation = entry.optimized()
                    ? ShaderOptimization.guardModuleInterface(cached)
                    : null;
                if (loadViolation == null) {
                    SpvModule module = rebuildModule(cached, type);
                    if (module != null) {
                        long loadNanos = System.nanoTime() - t0;
                        HITS.incrementAndGet();
                        if (config.verboseLog) {
                            PearlOptimizer.LOGGER.info("Shader cache HIT {} {} ({} KB, loaded in {} ms)",
                                type, shortKey(key), Math.max(1, cached.length / 1024), loadNanos / 1_000_000);
                        }
                        // Feed the in-memory layer from disk loads as well: after a
                        // restart shaderc never runs, so without this the RAM map
                        // would stay empty and memory hits could never happen.
                        if (config.spirvMemoryCache) {
                            rememberSpirv(key, cached);
                        }
                        return module;
                    }
                } else {
                    GUARD_STALE_REJECTED.incrementAndGet();
                    PearlOptimizer.LOGGER.warn("Shader cache guard: stale optimized entry {} rejected ({}); recompiling",
                        shortKey(key), loadViolation);
                }
                // Reconstruction sanity check or guard failed: fall through to
                // the compile so rendering can never be affected.
            }

            return compileAndShare(config, compiler, shaderName, glsl, type, defines, source, original,
                key, spvFile, metaFile, definesText, zeroToOne, drawParams, optimizedProfile);
        } catch (Throwable t) {
            if (t instanceof WaiterFailure w) {
                // Module 12: the in-flight winner's failure rethrown by a waiter. The
                // contract below is applied exactly once for every thread; the only
                // difference is the vanilla retry - waiters never recompile (owner
                // decision).
                Throwable cause = w.getCause() != null ? w.getCause() : w;
                breakCacheSession(cause);
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                if (cause instanceof Error err) {
                    throw err;
                }
                // Operation.call cannot produce checked throwables; defensive belt.
                throw new IllegalStateException(cause);
            }
            breakCacheSession(t);
            return original.call(compiler, shaderName, glsl, type, defines, source);
        }
    }

    /**
     * Disables the cached path for the rest of the session and drops both cache
     * layers. IN_FLIGHT is cleared as well - safe, because every claim settles
     * (success or failure) before its owner leaves the compile phase, so the
     * clear can never leave a waiter hanging on an unsettled future.
     */
    private static void breakCacheSession(Throwable logged) {
        if (WARNED.compareAndSet(false, true)) {
            PearlOptimizer.LOGGER.warn("Shader cache disabled for this session after unexpected error", logged);
        }
        BROKEN.set(true);
        // The in-memory layer is never read again this session - drop it now.
        MEMORY_SPIRV.clear();
        MEMORY_LAST_ACCESS.clear();
        MEMORY_TOTAL_BYTES.set(0);
        IN_FLIGHT.clear();
    }

    // ------------------------------------------------------------------
    // In-flight single-flight (module "inflightDedup")
    // ------------------------------------------------------------------

    /**
     * The miss path, with optional single-flight sharing of one compilation.
     *
     * <p>Without {@code inflightDedup} this is {@link #compileOwn} - the
     * vanilla-shaped body. With the flag enabled, parallel MISS on the
     * same key share the winner's compilation. The owner is the thread whose
     * {@code putIfAbsent} returned {@code null} (deliberately never
     * {@code computeIfAbsent}: the mapping function would run under the bin
     * lock for the whole compilation and would not reveal the owner identity).
     * The compilation itself runs strictly OUTSIDE the map, and the claim is a
     * {@link CompletableFuture} of the SPIR-V <em>snapshot</em> (an independent
     * {@code byte[]}, never the module or its native buffer), so every waiter
     * rebuilds its own module exactly like an accepted memory hit.</p>
     */
    private static SpvModule compileAndShare(
        PearlConfig config, GlslCompiler compiler, String shaderName, String glsl,
        ShaderType type, ShaderDefines defines, ShaderSource source,
        Operation<SpvModule> original, String key, Path spvFile, Path metaFile,
        String definesText, boolean zeroToOne, boolean drawParams, boolean compiledOptimized
    ) throws Throwable {
        if (!config.inflightDedup) {
            // Rollback path: byte-identical pre-module behaviour.
            return compileOwn(config, compiler, shaderName, glsl, type, defines, source, original,
                key, spvFile, metaFile, definesText, zeroToOne, drawParams, compiledOptimized);
        }
        CompletableFuture<byte[]> mine = new CompletableFuture<>();
        InflightClaim mineClaim = new InflightClaim(mine, Thread.currentThread());
        InflightClaim claim = IN_FLIGHT.putIfAbsent(key, mineClaim);
        if (claim == null) {
            // ------------------------------------------------------------ owner
            SpvModule module;
            Guarded guarded;
            byte[] snapshot;
            long t1 = System.nanoTime();
            try {
                module = original.call(compiler, shaderName, glsl, type, defines, source);
                // the interface guard runs BEFORE the claim settles -
                // waiters must never receive unverified optimized bytes. A
                // violation recompiles vanilla; waiters then get the vanilla
                // bytes (safe), the claim key stays the original one.
                guarded = guardOrVanilla(compiler, shaderName, glsl, type, defines, source, original,
                    module, key, spvFile, metaFile, definesText, zeroToOne, drawParams, compiledOptimized);
                module = guarded.module();
                // The claim settles BEFORE any fallible bookkeeping: a waiter can
                // never observe an owner that compiled but never settled.
                snapshot = spirvSnapshot(module);
                mine.complete(snapshot);
                // Feed the memory layer while the claim is still held. A
                // latecomer that probed the memory layer before this store now
                // lands on the settled claim (waiter path, instant bytes), and a
                // latecomer arriving after the remove below finds the memory
                // layer already warm. Without this ordering the remove -> remember gap
                // (with the verbose MISS log call inside) let such a latecomer
                // re-own the key and compile a duplicate (game run pair,
                // VERTEX 187ee36f: uniq -d non-empty in the dedup-enabled run).
                if (config.spirvMemoryCache) {
                    rememberSpirv(guarded.key(), snapshot);
                }
            } catch (Throwable t) {
                // Owner refinement 2: never flip an already settled future. For a
                // plain compile failure the claim is still fresh here; if this
                // catch ever ran after a successful complete() (rememberSpirv
                // swallows its own throwables, so this is defensive only), the
                // isDone guard keeps the bytes already handed to waiters usable
                // and the Throwable propagates to the outer contract unchanged.
                if (!mine.isDone()) {
                    mine.completeExceptionally(t);
                }
                throw t;
            } finally {
                // Identity remove: the claim leaves the map only after settlement
                // AND after the memory layer is fed - the state
                // "claim gone but memory not yet warm" no longer exists. The
                // remove always uses the ORIGINAL claim key (the guard may have
                // switched the store key to the vanilla one).
                IN_FLIGHT.remove(key, mineClaim);
            }
            // Post-completion bookkeeping: the claim is already
            // successful. If anything below ever threw, the owner goes through the
            // outer contract while the waiters keep their bytes; the settled future
            // is never touched again.
            long missNanos = System.nanoTime() - t1;
            MISSES.incrementAndGet();
            logMiss(config, type, key, missNanos);
            store(guarded.spvFile(), guarded.metaFile(), guarded.key(), snapshot, shaderName, glsl, type, definesText, zeroToOne, drawParams, guarded.optimized());
            maybePurge(config);
            return module;
        }
        if (claim.owner() == Thread.currentThread()) {
            // Reentrancy guard: impossible today (shaderc never re-enters the hook),
            // but waiting on our own claim would deadlock - compile our own copy,
            // exactly what the vanilla path would have done for this call.
            return compileOwn(config, compiler, shaderName, glsl, type, defines, source, original,
                key, spvFile, metaFile, definesText, zeroToOne, drawParams, compiledOptimized);
        }
        // ------------------------------------------------------------ waiter
        byte[] shared;
        long tW = System.nanoTime();
        try {
            shared = claim.future().get(INFLIGHT_TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
        } catch (ExecutionException ee) {
            // The winner's failure reaches every waiter as the SAME Throwable, and
            // a waiter never launches a second compilation: the outer contract
            // applies warn-once/BROKEN/cleanup WITHOUT the vanilla retry.
            throw new WaiterFailure(ee.getCause() != null ? ee.getCause() : ee);
        } catch (TimeoutException te) {
            shared = settleDetachedWaiter(key, claim);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            DEDUP_TIMEOUTS.incrementAndGet();
            return compileOwn(config, compiler, shaderName, glsl, type, defines, source, original,
                key, spvFile, metaFile, definesText, zeroToOne, drawParams, compiledOptimized);
        }
        if (shared == null) {
            // Detached by timeout (or the winner produced an unusable snapshot):
            // degrade to the plain miss path. This was previously
            // invisible (DEDUP_TIMEOUTS was a silent counter) - print one
            // verboseLog-gated line per detachment; detachment is a rare
            // timeout-scale event, never a per-frame one.
            if (config.verboseLog) {
                PearlOptimizer.LOGGER.info("Shader cache single-flight: waiter detached on {} {} after {} ms; compiling independently",
                    type, shortKey(key), (System.nanoTime() - tW) / 1_000_000);
            }
            return compileOwn(config, compiler, shaderName, glsl, type, defines, source, original,
                key, spvFile, metaFile, definesText, zeroToOne, drawParams, compiledOptimized);
        }
        SpvModule module = rebuildModule(shared, type);
        if (module == null) {
            // Same sanity-failure fallback as the accepted memory-hit path.
            return compileOwn(config, compiler, shaderName, glsl, type, defines, source, original,
                key, spvFile, metaFile, definesText, zeroToOne, drawParams, compiledOptimized);
        }
        DEDUP_HITS.incrementAndGet();
        if (config.verboseLog) {
            PearlOptimizer.LOGGER.info("Shader cache HIT {} {} ({} KB, waited {} ms) (dedup)",
                type, shortKey(key), Math.max(1, shared.length / 1024), (System.nanoTime() - tW) / 1_000_000);
        }
        return module;
    }

    /**
     * Timeout detachment for a waiter. Returns the
     * winner's settled snapshot when one exists - including when it appeared
     * between our timeout and the remove - and {@code null} when this waiter
     * must compile its own copy. A waiter never recompiles while a usable
     * result is already on the table: that duplicate is exactly what this
     * module exists to prevent.
     */
    private static byte[] settleDetachedWaiter(String key, InflightClaim claim) throws WaiterFailure {
        boolean detached = false;
        if (!claim.future().isDone()) {
            // The second isDone(): the winner may have settled the claim between our
            // timeout and this remove - then the settled result wins over detaching.
            detached = IN_FLIGHT.remove(key, claim) && !claim.future().isDone();
        }
        if (detached) {
            DEDUP_TIMEOUTS.incrementAndGet();
            return null;
        }
        // Either the claim already settled, or remove() lost the race because the
        // winner settled (and removed) it first: take the ready result, do not
        // recompile. A claim detached by ANOTHER waiter while still flying lands
        // here with a not-done future - get(0) reports the timeout immediately.
        try {
            byte[] shared = claim.future().get(0, TimeUnit.NANOSECONDS);
            if (shared != null) {
                return shared;
            }
        } catch (ExecutionException ee) {
            // The winner failed in the meantime: same contract, no second compile.
            throw new WaiterFailure(ee.getCause() != null ? ee.getCause() : ee);
        } catch (TimeoutException notDone) {
            // Covered by the comment above.
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        DEDUP_TIMEOUTS.incrementAndGet();
        return null;
    }

    /**
     * The plain miss body (compile, count, log, store, remember,
     * purge) - shared by the rollback path, the reentrancy guard, the timeout
     * detach and the sanity-failure fallback, so every degradation compiles and
     * stores exactly like the pre-module code did.
     */
    private static SpvModule compileOwn(
        PearlConfig config, GlslCompiler compiler, String shaderName, String glsl,
        ShaderType type, ShaderDefines defines, ShaderSource source,
        Operation<SpvModule> original, String key, Path spvFile, Path metaFile,
        String definesText, boolean zeroToOne, boolean drawParams, boolean compiledOptimized
    ) throws Throwable {
        long t1 = System.nanoTime();
        SpvModule module = original.call(compiler, shaderName, glsl, type, defines, source);
        long missNanos = System.nanoTime() - t1;
        MISSES.incrementAndGet();
        logMiss(config, type, key, missNanos);
        Guarded guarded = guardOrVanilla(compiler, shaderName, glsl, type, defines, source, original,
            module, key, spvFile, metaFile, definesText, zeroToOne, drawParams, compiledOptimized);
        module = guarded.module();
        byte[] snapshot = spirvSnapshot(module);
        store(guarded.spvFile(), guarded.metaFile(), guarded.key(), snapshot, shaderName, glsl, type, definesText, zeroToOne, drawParams, guarded.optimized());
        if (config.spirvMemoryCache) {
            rememberSpirv(guarded.key(), snapshot);
        }
        maybePurge(config);
        return module;
    }

    // ------------------------------------------------------------------
    // In-memory SPIR-V helpers
    // ------------------------------------------------------------------

    /** One start line per session; the start line never prints counters. */
    private static void reportStart(PearlConfig config) {
        // kickPrefetch used
        // to sit behind the verboseLog gate below, so with default config
        // (log off) the prefetch never ran at all and warm starts silently
        // lost the whole module - the warm log had no prefetch line although
        // 240 entries were on disk. Prefetch is a performance feature, not
        // diagnostics: kick it first; PREFETCH_KICKED keeps it once per
        // session and testCacheRoot/BROKEN still exclude smoke harnesses.
        kickPrefetch(config);
        if (!config.verboseLog || !MEMORY_START_REPORTED.compareAndSet(false, true)) {
            return;
        }
        PearlOptimizer.LOGGER.info(config.spirvMemoryCache
            ? "SPIR-V memory cache: enabled"
            : "SPIR-V memory cache: disabled by config");
        if (config.purgeThrottle) {
            PearlOptimizer.LOGGER.info("Shader cache purge throttle: enabled (first store walks, then at most once per {} KB of newly persisted entries)",
                purgeThresholdBytes(config) / 1024);
        } else {
            PearlOptimizer.LOGGER.info("Shader cache purge throttle: disabled by config (every store walks)");
        }
        if (config.inflightDedup) {
            PearlOptimizer.LOGGER.info("Shader compile single-flight: enabled (parallel MISS on one key share one compilation)");
        } else {
            PearlOptimizer.LOGGER.info("Shader compile single-flight: disabled by config (parallel MISS compile independently)");
        }
    }

    /**
     * Warm prefetch of the disk SPIR-V layer: on the first shader
     * request of the session the background store thread reads every valid
     * entry into the in-memory layer, so worker threads that request shaders
     * a moment later hit RAM instead of the filesystem. Removes the disk-wait
     * tail of warm starts (up to tens of ms summed over hundreds of
     * loads, spikes to 12 ms on one entry) from the request path entirely.
     *
     * <p>Safety: never runs for smoke harnesses ({@code testCacheRoot} set -
     * their assertions are synchronous by design), never logs per entry, and
     * logs a summary line only when it actually warmed something, so a cold
     * session's log stays comparable with a non-prefetched run. Loading
     * goes through {@link #rememberSpirv}, which enforces the RAM budget;
     * the loop additionally stops at half the budget so the prefetch can
     * never push the session into LRU eviction on a pathologically large
     * cache (5.3 replaced the old whole-map clear - a prefetch that would
     * evict what it just warmed would be pure churn).
     * The summary line carries {@code invalid=} (entries
     * rejected by the integrity check) and {@code truncated=} (entries left
     * lazy by the budget guard / a broken session); both are zero on a
     * healthy cache and the acceptance asserts exactly that.</p>
     */
    private static void kickPrefetch(PearlConfig config) {
        if (!config.spirvMemoryCache || !PREFETCH_KICKED.compareAndSet(false, true)) {
            return;
        }
        if (testCacheRoot != null || BROKEN.get()) {
            return;
        }
        try {
            PersistExecutor.INSTANCE.execute(() -> runPrefetch());
        } catch (Throwable ignored) {
            // Executor unavailable: warm starts fall back to lazy loading, as before.
        }
    }

    private static void runPrefetch() {
        Path root = cacheRoot();
        if (root == null || BROKEN.get() || !Files.isDirectory(root)) {
            return;
        }
        long t0 = System.nanoTime();
        List<Path> spvFiles = new ArrayList<>();
        try (Stream<Path> buckets = Files.list(root)) {
            for (Path bucket : buckets.filter(Files::isDirectory).toList()) {
                try (Stream<Path> files = Files.list(bucket)) {
                    spvFiles.addAll(files.filter(p -> p.getFileName().toString().endsWith(".spv")).toList());
                } catch (Throwable ignored) {
                    // unreadable bucket: its entries just stay lazy (best-effort)
                }
            }
        } catch (Throwable ignored) {
            return; // Prefetch is best-effort: any IO problem just leaves entries lazy.
        }
        // every entry passes through the SAME loadEntryIfValid check
        // as a regular disk load; the two ways an entry can
        // fail to warm are now visible in the summary line:
        //   invalid   - present on disk but rejected by loadEntryIfValid (damaged,
        //               foreign format, unreadable) or, by the
        //               interface guard (a stale optimized entry persisted by a
        //               pre-guard session; also counted in GUARD_STALE_REJECTED).
        //               Never deleted: the entry stays for the purge machinery,
        //               exactly as before.
        //   truncated - never examined because the budget guard or a broken
        //               session stopped the loop; those entries mature lazily
        //               on first request, as before.
        //   profile-skipped - optimized entries of a session whose optimized
        //               profile is definitively off (config disabled or the
        //               guard disarmed): their keys embed the optimized profile
        //               and can never be hit this session, so the payload is
        //               not even read. The two sticky states are checked
        //               instead of isActive() on purpose: isActive() could run
        //               the one-time shaderc probe on this background thread
        //               and would move the profile INFO line ahead of the first
        //               compile (an observable log-contract change). When only
        //               the probe verdict is negative, the entries are still
        //               warmed (as before) - they are simply never hit.
        // The half-budget stop also keeps the prefetch out of the 5.3 LRU
        // eviction's way (see kickPrefetch javadoc).
        boolean serveOptimized = PearlConfig.get().shaderOptimization
            && !ShaderOptimization.guardDisarmed();
        int loaded = 0;
        long bytes = 0;
        int examined = 0;
        for (Path spvFile : spvFiles) {
            if (BROKEN.get() || MEMORY_TOTAL_BYTES.get() > MEMORY_LIMIT_BYTES / 2) {
                // Current entry + everything after it was never examined.
                PREFETCH_TRUNCATED.addAndGet(spvFiles.size() - examined);
                break;
            }
            examined++;
            String name = spvFile.getFileName().toString();
            String key = name.substring(0, name.length() - 4);
            if (MEMORY_SPIRV.containsKey(key)) {
                continue;
            }
            Path metaFile = spvFile.getParent().resolve(key + ".meta.json");
            // Profile skip BEFORE any payload IO: a meta-only peek is enough -
            // an unreadable/mismatching meta just falls through to the ordinary
            // integrity check (and its invalid counter) below.
            if (!serveOptimized && metaOptimizedOf(metaFile)) {
                PREFETCH_PROFILE_SKIPPED.incrementAndGet();
                continue;
            }
            DiskEntry entry = loadEntryIfValid(spvFile, metaFile);
            if (entry == null) {
                PREFETCH_INVALID.incrementAndGet();
                continue;
            }
            byte[] cached = entry.bytes();
            // optimized entries are guard-checked BEFORE they enter the
            // memory layer, so a stale optimized entry can never be served from
            // RAM (the request path would hit memory without any further check).
            if (entry.optimized() && ShaderOptimization.guardModuleInterface(cached) != null) {
                PREFETCH_INVALID.incrementAndGet();
                GUARD_STALE_REJECTED.incrementAndGet();
                continue;
            }
            rememberSpirv(key, cached);
            loaded++;
            bytes += cached.length;
        }
        if (loaded > 0) {
            // The historical two-counter line is kept byte-identical for the
            // healthy case (the acceptance greps assert the exact substring
            // "invalid=0, truncated=0"); the profile-skip suffix appears only
            // in sessions where optimized entries were actually skipped.
            if (PREFETCH_PROFILE_SKIPPED.get() > 0) {
                PearlOptimizer.LOGGER.info("Shader cache prefetch: {} entries ({} KB) warmed in {} ms, invalid={}, truncated={}, profile-skipped={}",
                    loaded, Math.max(1, bytes / 1024), (System.nanoTime() - t0) / 1_000_000,
                    PREFETCH_INVALID.get(), PREFETCH_TRUNCATED.get(), PREFETCH_PROFILE_SKIPPED.get());
            } else {
                PearlOptimizer.LOGGER.info("Shader cache prefetch: {} entries ({} KB) warmed in {} ms, invalid={}, truncated={}",
                    loaded, Math.max(1, bytes / 1024), (System.nanoTime() - t0) / 1_000_000,
                    PREFETCH_INVALID.get(), PREFETCH_TRUNCATED.get());
            }
        }
    }

    /**
     * Meta-only peek of the {@code "optimized"} flag for the prefetch profile
     * skip: {@code true} only when the meta is readable, well-formed and says
     * {@code optimized=true}. Any problem reads as {@code false} so the entry
     * falls through to the ordinary integrity check (and its invalid counter).
     */
    private static boolean metaOptimizedOf(Path metaFile) {
        try {
            return metaOptimized(Files.readString(metaFile, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Disk load with single-flight sharing: the first requester owns
     * the read, parallel requesters for the same key wait on its future and
     * receive the same immutable snapshot. On timeout or any failure a waiter
     * falls back to the ordinary path (its own load, then compile) - the load
     * is best-effort and can never affect rendering. The owner removes its
     * claim (identity, like {@link #IN_FLIGHT}) right after settlement, so a
     * latecomer always lands in the ordinary cache layers.
     */
    private static DiskEntry loadShared(String key, Path spvFile, Path metaFile) {
        if (spvFile == null || metaFile == null) {
            return null;
        }
        CompletableFuture<DiskEntry> mine = new CompletableFuture<>();
        CompletableFuture<DiskEntry> claim = LOADS_IN_FLIGHT.putIfAbsent(key, mine);
        if (claim == null) {
            try {
                DiskEntry entry = loadEntryIfValid(spvFile, metaFile);
                mine.complete(entry);
                return entry;
            } catch (Throwable t) {
                if (!mine.isDone()) {
                    mine.completeExceptionally(t);
                }
                return null; // defensive belt: loadEntryIfValid already swallows everything
            } finally {
                LOADS_IN_FLIGHT.remove(key, mine);
            }
        }
        try {
            DiskEntry shared = claim.get(INFLIGHT_TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
            LOAD_DEDUP_HITS.incrementAndGet();
            return shared;
        } catch (InterruptedException ie) {
            // Restore the interrupt flag before degrading to the own-read path:
            // swallowing it here would silently cancel the caller's next wait.
            Thread.currentThread().interrupt();
            return null; // detached: fall back to own read / compile
        } catch (Throwable t) {
            return null; // detached: fall back to own read / compile
        }
    }

    /**
     * Inserts an independent SPIR-V snapshot into the in-memory map under the
     * disk-cache key. Fully self-contained: any throwable is swallowed, a
     * memory-cache problem must never trip the outer {@code BROKEN} latch
     * (that would needlessly disable the disk cache and replay the compile).
     *
     * <p>Limit handling: a single snapshot larger
     * than the whole budget is never inserted (and never triggers eviction);
     * when the budget would be exceeded, the least-recently-accessed entries
     * are evicted individually until the new snapshot fits, so the entries
     * that are actually being re-read stay warm (the old whole-map clear
     * opened a "cold window" on large caches). {@code putIfAbsent} keeps the
     * map duplicate-free under parallel compiles; the byte counter grows only
     * for the won race, and the counter drift of a lost eviction race is
     * bounded by one entry - the same contract the whole-map clear had.</p>
     */
    private static void rememberSpirv(String key, byte[] snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            long size = snapshot.length;
            if (size > MEMORY_LIMIT_BYTES) {
                return;
            }
            evictMemoryLru(size);
            byte[] prev = MEMORY_SPIRV.putIfAbsent(key, snapshot);
            touchMemoryKey(key); // LRU stamp for the fresh insert (or a refresh on the lost race)
            if (prev == null) {
                MEMORY_TOTAL_BYTES.addAndGet(size);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Refreshes the LRU access stamp of {@code key} (best-effort, never throws). */
    private static void touchMemoryKey(String key) {
        try {
            AtomicLong stamp = MEMORY_LAST_ACCESS.get(key);
            if (stamp == null) {
                stamp = MEMORY_LAST_ACCESS.computeIfAbsent(key, k -> new AtomicLong());
            }
            stamp.set(System.nanoTime());
        } catch (Throwable ignored) {
        }
    }

    /**
     * LRU eviction for the in-memory budget: removes the
     * least-recently-accessed entries one by one until {@code neededBytes}
     * fit again. Best-effort under concurrency: a removal that lost its race
     * (another thread removed the victim first) just triggers a rescan, and
     * the byte counter drift stays bounded by one entry per race - the same
     * contract the replaced whole-map clear had. A map drained to empty (only
     * reachable if the counter drifted high) stops the loop instead of
     * spinning; the next successful insert re-anchors the counter.
     */
    private static void evictMemoryLru(long neededBytes) {
        while (MEMORY_TOTAL_BYTES.get() + neededBytes > MEMORY_LIMIT_BYTES) {
            Map.Entry<String, byte[]> victim = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, byte[]> e : MEMORY_SPIRV.entrySet()) {
                AtomicLong stamp = MEMORY_LAST_ACCESS.get(e.getKey());
                long t = stamp == null ? 0L : stamp.get(); // no stamp = oldest (defensive)
                if (t < oldest) {
                    oldest = t;
                    victim = e;
                }
            }
            if (victim == null) {
                return; // map empty - nothing left to evict
            }
            byte[] removed = MEMORY_SPIRV.remove(victim.getKey());
            if (removed != null) {
                MEMORY_LAST_ACCESS.remove(victim.getKey());
                MEMORY_TOTAL_BYTES.addAndGet(-removed.length);
            }
            // removed == null: another thread evicted this victim first - rescan.
        }
    }

    /** Independent copy of the module's SPIR-V bytes; {@code null} if unavailable. */
    private static byte[] spirvSnapshot(SpvModule module) {
        try {
            ByteBuffer spvBuf = module.spv();
            if (spvBuf == null || spvBuf.remaining() < 4) {
                return null;
            }
            byte[] bytes = new byte[spvBuf.remaining()];
            spvBuf.duplicate().get(bytes);
            return bytes;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Key computation
    // ------------------------------------------------------------------

    /**
     * Key over every input the vanilla {@code compileToSpv(shaderName, glsl, ...)}
     * compile can observe: the GLSL text, the shader name, the defines, the
     * shader type, the effective shaderc profile, the include fingerprint and
     * ALL THREE device-dependent flags - zeroToOne, drawParams and the
     * explicit-depth invariance hint.
     *
     * <p>The same key string indexes the RAM layer, the disk layer and the
     * single-flight map, so one change here covers all three.
     * {@code explicitDepthFlag()} is read through the exact chain vanilla uses
     * in {@code createBaseShaderOptions}; "unknown" is a distinct component,
     * never conflated with {@code true}/{@code false}.</p>
     */
    public static String computeKey(
        String shaderName, String glsl, ShaderType type, String definesText,
        boolean zeroToOne, boolean drawParams, boolean optimized
    ) {
        MessageDigest md = PearlUtil.sha256();
        feed(md, "pearl-shader-cache-v" + CACHE_FORMAT);
        feed(md, "game=" + gameVersion());
        feed(md, "type=" + (type == null ? "?" : type.name()));
        feed(md, "shader=" + shaderName);
        feed(md, "zeroToOne=" + zeroToOne);
        feed(md, "drawParams=" + drawParams);
        feed(md, "explicitDepth=" + explicitDepthFlag());
        feed(md, "opt=" + (optimized ? "performance+debug" : "vanilla0+debug"));
        feed(md, "includes=" + includeFingerprint());
        feed(md, "defines=" + definesText);
        feed(md, "source=" + glsl);
        return PearlUtil.hex(md.digest());
    }

    /**
     * Third device-dependent compiler flag: vanilla injects the
     * {@code RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE} macro through the shaderc
     * compile options (NOT through the GLSL text, so it is invisible to the
     * source hash) when {@code HintsAndWorkarounds.isExplicitDepthRequired()}
     * holds. Read through the same chain vanilla uses; any failure (device
     * gone, API drift) yields the distinct "unknown" component instead of a
     * guessed boolean, so no entry is ever shared across flag states.
     */
    private static String explicitDepthFlag() {
        try {
            return String.valueOf(
                RenderSystem.getDevice().getDeviceInfo().hintsAndWorkarounds().isExplicitDepthRequired());
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String gameVersion() {
        String cached = GAME_VERSION;
        if (cached != null) {
            return cached;
        }
        String probed = probeGameVersion();
        GAME_VERSION = probed;
        return probed;
    }

    /** Session-stable mod-container version probe (resolved once per session). */
    private static volatile String GAME_VERSION;

    private static String probeGameVersion() {
        try {
            return FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // ------------------------------------------------------------------
    // Disk IO
    // ------------------------------------------------------------------

    private static DiskEntry loadEntryIfValid(Path spvFile, Path metaFile) {
        if (spvFile == null || metaFile == null) {
            // Disk layer unavailable (see fileForOrNull): plain miss, never an error.
            return null;
        }
        try {
            if (!Files.isRegularFile(spvFile) || !Files.isRegularFile(metaFile)) {
                return null;
            }
            byte[] spv = Files.readAllBytes(spvFile);
            if (spv.length < 4) {
                return null;
            }
            String meta = Files.readString(metaFile, StandardCharsets.UTF_8);
            // a meta outside the on-disk format contract
            // is never served. The cache key already contains CACHE_FORMAT, so
            // this is belt-and-braces against a hand-copied entry with a
            // mismatching meta - it degrades to a plain miss and is never
            // deleted (deletion stays the purge machinery's job).
            if (metaIntField(meta, "format", -1) != CACHE_FORMAT) {
                return null;
            }
            String storedSha = metaField(meta, "spvSha256");
            if (!PearlUtil.hex(PearlUtil.sha256(spv)).equals(storedSha)) {
                return null;
            }
            int magic = ByteBuffer.wrap(spv).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (!PearlUtil.isSpirvMagic(magic)) {
                return null;
            }
            // the profile flag travels with the entry so the load path
            // can guard-check optimized entries without knowing the session
            // profile (a stale optimized entry must be caught even after the
            // guard disarmed and the session keys moved to vanilla).
            return new DiskEntry(spv, metaOptimized(meta));
        } catch (Throwable t) {
            return null; // any IO problem is a miss, never a failure
        }
    }

    /**
     * Reads the boolean {@code "optimized"} field of a flat meta object
     * (written as {@code "optimized": true,}). Missing or unparsable = false
     * (the safe direction: the entry is treated as vanilla-profile and never
     * guard-checked on load; format-4 writers always emit the field).
     */
    private static boolean metaOptimized(String metaJson) {
        int i = metaJson.indexOf("\"optimized\"");
        if (i < 0) {
            return false;
        }
        int colon = metaJson.indexOf(':', i + "\"optimized\"".length());
        if (colon < 0) {
            return false;
        }
        int end = metaJson.indexOf(',', colon + 1);
        String raw = (end < 0 ? metaJson.substring(colon + 1) : metaJson.substring(colon + 1, end)).trim();
        return raw.equals("true");
    }

    /**
     * Persists one compiled SPIR-V entry.
     *
     * <p>The disk part (SHA-256 sums, {@code createDirectories},
     * both atomic writes) runs on a single background daemon thread
     * ({@code asyncStore=true}, default): the compile thread - the render
     * thread during a cold-start world join - only snapshots the SPIR-V bytes
     * into a heap array and returns, so each cold compile hitch loses its
     * filesystem tail. With {@code asyncStore=false} (or when the executor is
     * shutting down or fails for any reason) the write runs synchronously on
     * the calling thread, byte-identical to the pre-async behaviour.</p>
     *
     * <p>Ordering and safety contracts preserved from the synchronous
     * implementation:</p>
     * <ul>
     *   <li>the SPIR-V snapshot is taken by the caller and passed in as a
     *       heap array: the module's {@code spv()} buffer is native memory
     *       that {@code SPIRVModule.close()} frees with {@code memFree}, so
     *       reading it later would be a use-after-free;</li>
     *   <li>the executor is single-threaded, so payload-then-meta ordering is
     *       preserved per entry and across entries (a torn write still leaves
     *       an orphan .spv that the meta-first load rejects);</li>
     *   <li>the purge accounting ({@code PURGE_BYTES_SINCE}) still increments
     *       only after both atomic moves completed, inside the write task;
     *       an entry whose write failed or was dropped at JVM exit stays
     *       uncounted and is covered by the next session's first walk;</li>
     *   <li>a purge walk racing a pending async write is safe: the walk may
     *       just not see the not-yet-written entries (bounded by the pending
     *       queue, a few entries at most), and {@code writeAtomically} makes
     *       a partially visible file impossible.</li>
     * </ul>
     */
    private static void store(
        Path spvFile, Path metaFile, String key, byte[] bytes,
        String shaderName, String glsl, ShaderType type, String definesText,
        boolean zeroToOne, boolean drawParams, boolean optimized
    ) {
        if (STORE_DISABLED.get()) {
            // latch: disk writes are off for the rest of the
            // session after repeated write failures. The memory layer was
            // already fed by the caller, so session caching is unaffected -
            // the same degradation the unavailable-disk case uses, with a
            // visible diagnostic trail (latch INFO + drain WARN).
            return;
        }
        if (spvFile == null || metaFile == null) {
            // Disk layer unavailable (see fileForOrNull): storing is skipped, the
            // memory layer was already fed by the caller. Deliberately silent.
            return;
        }
        if (bytes == null) {
            // Snapshot unavailable on the caller thread (see javadoc): nothing
            // to persist this session. The memory layer (if enabled) was already
            // fed with the same bytes and is equally empty here.
            return;
        }
        if (PearlConfig.get().asyncStore && testCacheRoot == null) {
            // testCacheRoot set => the smoke harness asserts files on disk right
            // after a compile; store synchronously there regardless of the flag.
            try {
                PersistExecutor.INSTANCE.execute(
                    () -> writeEntry(spvFile, metaFile, key, bytes, shaderName, glsl, type,
                        definesText, zeroToOne, drawParams, optimized));
                STORES_QUEUED.incrementAndGet();
                ensureStoreDrainHook();
                return;
            } catch (RejectedExecutionException rejected) {
                // JVM is shutting down (or the executor died): fall through and
                // write synchronously, exactly like the pre-async code.
            } catch (Throwable t) {
                // Any other executor problem: fall through to the sync path.
            }
        }
        writeEntry(spvFile, metaFile, key, bytes, shaderName, glsl, type, definesText,
            zeroToOne, drawParams, optimized);
    }

    /** One background writer for the shader-cache disk entries; daemon, so it never blocks JVM exit. */
    private static final class PersistExecutor {
        static final ExecutorService INSTANCE = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "pearl-optimizer-shader-store");
            t.setDaemon(true);
            return t;
        });

        private PersistExecutor() {
        }
    }

    /**
     * Disk-only part of {@link #store}: SHA-256 sums, meta JSON, directory
     * creation and the two atomic writes. Never throws; never touches the
     * live {@code SpvModule} (it only sees the caller-thread snapshot).
     */
    private static void writeEntry(
        Path spvFile, Path metaFile, String key, byte[] bytes,
        String shaderName, String glsl, ShaderType type, String definesText,
        boolean zeroToOne, boolean drawParams, boolean optimized
    ) {
        try {
            String spvSha = PearlUtil.hex(PearlUtil.sha256(bytes));
            // The shader name is an Identifier string ([a-z0-9_.:-]); the GLSL
            // text is deliberately NOT embedded - its SHA-256 is enough.
            String meta = """
                {
                  "format": %d,
                  "key": "%s",
                  "game": "%s",
                  "type": "%s",
                  "shader": "%s",
                  "zeroToOne": %b,
                  "drawParams": %b,
                  "explicitDepth": "%s",
                  "optimized": %b,
                  "definesSha256": "%s",
                  "sourceSha256": "%s",
                  "includesFingerprint": "%s",
                  "size": %d,
                  "spvSha256": "%s"
                }
                """.formatted(
                    CACHE_FORMAT, key, gameVersion(), type == null ? "?" : type.name(),
                    shaderName, zeroToOne, drawParams, explicitDepthFlag(), optimized,
                    PearlUtil.hex(PearlUtil.sha256(definesText)),
                    PearlUtil.hex(PearlUtil.sha256(glsl)),
                    includeFingerprint(), bytes.length, spvSha
                );
            Files.createDirectories(spvFile.getParent());
            PearlUtil.writeAtomically(spvFile, bytes);
            PearlUtil.writeAtomically(metaFile, meta.getBytes(StandardCharsets.UTF_8));
            // Purge throttle: only a fully persisted entry
            // counts. Both atomic moves have completed at this point; any IO
            // failure above throws past this line, so the increment is skipped.
            // An entry that was never counted is covered by the next session's
            // unconditional first walk.
            PURGE_BYTES_SINCE.addAndGet(bytes.length);
            STORES_WRITTEN.incrementAndGet();
            STORE_FAILURE_STREAK.set(0); // a success ends the failure streak
        } catch (Throwable t) {
            STORES_FAILED.incrementAndGet();
            FIRST_FAILED_KEY.compareAndSet(null, key);
            FIRST_FAILURE_CAUSE.compareAndSet(null, t.getClass().getSimpleName());
            long streak = STORE_FAILURE_STREAK.incrementAndGet();
            if (streak >= STORE_LATCH_THRESHOLD && STORE_DISABLED.compareAndSet(false, true)) {
                // three failed writes in a row - stop attempting
                // disk writes for this session (survives a one-off hiccup, stops
                // the churn on a degraded target like a full disk / read-only
                // mount). Loads and the memory layer keep working; the state is
                // visible in Diagnostics and the drain WARN.
                PearlOptimizer.LOGGER.info("Shader cache store disabled after {} write failures in a row (key {}, cause {}): disk writes are skipped until the next launch, memory cache and loads continue",
                    streak, shortKey(key), t.getClass().getSimpleName());
            }
            // Storing is best-effort; a failed store is invisible next launch (plain miss).
        }
    }

    /**
     * JVM-exit drain for the background writer: registered once on
     * the first async dispatch, never in test mode. Shutdown hooks run while
     * daemon threads are still alive, so {@code shutdown() + awaitTermination}
     * drains the pending write queue (bounded at 2 s) BEFORE the JVM halts -
     * the "entry lost at JVM exit" case becomes practically
     * impossible without making the store thread non-daemon. A late
     * {@code store()} after the drain falls through to the synchronous write
     * via the existing RejectedExecutionException fail-safe, so behaviour is
     * never lost, only degraded. Ends with a one-line summary that also lets
     * a single cold log prove the async path end-to-end.
     */
    private static void ensureStoreDrainHook() {
        if (!STORE_DRAIN_HOOK_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    PersistExecutor.INSTANCE.shutdown();
                    PersistExecutor.INSTANCE.awaitTermination(2, TimeUnit.SECONDS);
                } catch (Throwable ignored) {
                }
                long failed = STORES_FAILED.get();
                if (failed > 0) {
                    // one WARN per drain, independent of the
                    // verbose flag - a silent failed>0 made a full disk look
                    // exactly like a clean start.
                    PearlOptimizer.LOGGER.warn("Shader cache store failures: n={} first_key={} cause={} (affected shaders compile as plain MISS next launch)",
                        failed, FIRST_FAILED_KEY.get(), FIRST_FAILURE_CAUSE.get());
                }
                if (STORES_QUEUED.get() > 0 || STORES_WRITTEN.get() > 0 || failed > 0) {
                    PearlOptimizer.LOGGER.info("Shader cache store summary: queued={} written={} failed={} (drain done)",
                        STORES_QUEUED.get(), STORES_WRITTEN.get(), failed);
                }
            }, "pearl-optimizer-store-drain"));
        } catch (Throwable ignored) {
            // Hook rejected (JVM already shutting down): the fail-safe sync path covers the rest.
        }
    }

    /**
     * Decides whether this store pays for a purge walk. With
     * {@code purgeThrottle=false} this is the unthrottled
     * behaviour: every store walks. With the throttle enabled the walk runs
     * unconditionally once per session (to finish the
     * previous session's leftover) and afterwards only when at least a full
     * threshold of new bytes has been persisted since the last walk.
     */
    private static void maybePurge(PearlConfig config) {
        if (!config.purgeThrottle) {
            purgeNow(config);
            PURGE_WALKS.incrementAndGet();
            return;
        }
        try {
            boolean first = !PURGE_FIRST_DONE.get();
            if (first) {
                if (!PURGE_FIRST_DONE.compareAndSet(false, true)) {
                    // Another thread is taking the unconditional first walk.
                    PURGE_SKIPPED.incrementAndGet();
                    return;
                }
            } else if (PURGE_BYTES_SINCE.get() < purgeThresholdBytes(config)) {
                PURGE_SKIPPED.incrementAndGet();
                return;
            }
            if (!PURGE_IN_FLIGHT.compareAndSet(false, true)) {
                PURGE_SKIPPED.incrementAndGet();
                return;
            }
            try {
                // Owner condition 2 guarantee: the counter is consumed
                // atomically (getAndSet(0)) BEFORE the walk starts. Every
                // increment landing after this point - in particular bytes
                // persisted by concurrent stores WHILE this walk is running -
                // lands after the reset, stays in the counter and belongs to
                // the next window. The walk never zeroes those bytes out.
                PURGE_BYTES_SINCE.getAndSet(0);
                purgeNow(config);
                PURGE_WALKS.incrementAndGet();
            } finally {
                PURGE_IN_FLIGHT.set(false);
            }
        } catch (Throwable ignored) {
            // Throttling must never surface; purgeNow keeps its own guards.
        }
    }

    /**
     * Verbose report wrapper around the shared walk body (review
     * item 5.2): the classification, T-C1 orphan absorption, T-C2 delete
     * guard and the purgeThrottle=false class lock all live in
     * {@link PearlUtil#housekeepingWalk} now - the single test contour for
     * both housekeeping walks. This method only picks the shader-cache file
     * names and prints the T-C3 report line.
     *
     * <p>Report contract (T-C3, unchanged): one verbose INFO line, printed
     * only when the walk completed AND ran over cap AND actually did
     * something (deleted entries, skipped fresh ones, absorbed orphans); a
     * walk that aborted early reports {@code null} and stays silent, exactly
     * like the pre-5.2 body whose report sat behind the over-cap branch.</p>
     */
    private static void purgeNow(PearlConfig config) {
        PearlUtil.WalkStats stats = PearlUtil.housekeepingWalk(
            cacheRoot(), true, "", ".spv", ShaderCacheManager.class,
            effectiveCapMb(config) * 1024L * 1024L);
        if (stats == null || !stats.capExceeded()) {
            return;
        }
        if (config.verboseLog && (stats.deleted() > 0 || stats.freshSkipped() > 0 || stats.orphansAbsorbed() > 0)) {
            PearlOptimizer.LOGGER.info("Shader cache purge: deleted={} entries ({} KB), fresh-skipped={}, "
                    + "orphan-meta-absorbed={} (cap={} MB, on-disk={} KB)",
                stats.deleted(), stats.deletedBytes() / 1024, stats.freshSkipped(), stats.orphansAbsorbed(),
                effectiveCapMb(config), stats.totalBytes() / 1024);
        }
    }

    /**
     * Walk trigger threshold. Base = max(1 MB, cap/8),
     * clamped to cap/2 for every cap value, so the disk usage overshoot
     * between two walks can never exceed half of the configured cap.
     */
    static long purgeThresholdBytes(PearlConfig config) {
        long capBytes = effectiveCapMb(config) * 1024L * 1024L;
        long base = Math.max(1024L * 1024L, capBytes / 8);
        return Math.min(base, capBytes / 2);
    }

    public static int effectiveCapMb(PearlConfig config) {
        return Math.max(8, Math.min(config.shaderCacheMaxSizeMb, 1024));
    }

    /**
     * Disk path for a cache entry, or {@code null} when the disk layer is
     * unavailable (no game directory); that state throws through
     * the outer contract of {@link #loadOrCompile} and latched
     * {@link #BROKEN}, which disabled even the in-memory SPIR-V layer for the
     * rest of the session - needlessly coarse for a purely disk-side problem.
     * With {@code null} paths the caller keeps the memory layer alive:
     * {@link #loadEntryIfValid} is a plain miss and {@link #store} a no-op, while
     * compiles still feed the memory layer and still serve warm hits within
     * the session.
     */
    private static Path fileForOrNull(String key, String suffix) {
        Path root = cacheRoot();
        if (root == null) {
            return null;
        }
        return root.resolve(key.substring(0, 2)).resolve(key + suffix);
    }

    private static Path cacheRoot() {
        Path override = testCacheRoot;
        if (override != null) {
            return override;
        }
        try {
            return FabricLoader.getInstance().getGameDir()
                .resolve("cache").resolve("pearl-optimizer").resolve("shader-cache");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Test hook: redirects the cache directory without a Fabric runtime. */
    private static volatile Path testCacheRoot;

    public static void setCacheRootForTest(Path path) {
        testCacheRoot = path;
    }

    /** Verbose MISS line (compile + store). Never throws. */
    private static void logMiss(PearlConfig config, ShaderType type, String key, long nanos) {
        if (config.verboseLog) {
            PearlOptimizer.LOGGER.info("Shader cache MISS {} {} (compiled in {} ms, storing)",
                type, shortKey(key), nanos / 1_000_000);
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    /**
     * Rebuilds a {@link SpvModule} from cached SPIR-V bytes, replicating the
     * exact buffer construction of vanilla {@code GlslCompiler.compileToSpv}:
     * an LWJGL-allocated buffer ({@code MemoryUtil.memCalloc}) with native
     * byte order.
     *
     * <p>This is critical for two reasons. First, {@code SPIRVModule.close()}
     * releases the buffer with {@code MemoryUtil.memFree}, which must be
     * paired with an LWJGL allocation - feeding it a JDK
     * {@code ByteBuffer.allocateDirect} buffer double-frees the memory
     * (memFree + the JDK cleaner) and aborts the process with glibc
     * {@code double free or corruption}.</p>
     *
     * <p>Second, SPIR-V reflection reads 32-bit words through
     * {@code asIntBuffer()}; a buffer with the wrong byte order returns every
     * location decoration byte-swapped (location {@code k} becomes
     * {@code k << 24}), which showed up as
     * {@code glVertexAttribFormat(attribindex=16777216)} GL errors and a
     * black screen.</p>
     *
     * <p>Returns {@code null} if the reconstructed module does not pass a
     * SPIR-V magic sanity check through the word view; the caller then falls
     * back to the vanilla compile.</p>
     */

    private static SpvModule rebuildModule(byte[] bytes, ShaderType type) {
        ByteBuffer buf = MemoryUtil.memCalloc(bytes.length);
        buf.put(bytes).flip();
        try {
            // Read through the buffer's own byte order - exactly how the game
            // reads location decorations later (this.spv.asIntBuffer()). Note:
            // do NOT duplicate() here - on LWJGL's custom buffer duplicate()
            // drops the native byte order and would falsely reject every hit.
            int firstWord = buf.asIntBuffer().get(0);
            if (!PearlUtil.isSpirvMagic(firstWord)) {
                MemoryUtil.memFree(buf);
                return null;
            }
        } catch (Throwable t) {
            MemoryUtil.memFree(buf);
            return null;
        }
        try {
            return new SPIRVModule(buf, type);
        } catch (Throwable t) {
            // The constructor threw without taking ownership of the buffer:
            // nobody else can free it now (close() belongs to a module that was
            // never returned), so free it here instead of leaking native memory
            // on every such failure.
            MemoryUtil.memFree(buf);
            return null;
        }
    }

    /**
     * Flat-JSON field extractor: returns the raw value as a string for both
     * quoted and numeric fields ({@code "key": "value"} and {@code "key": 4}).
     * Numeric callers go through {@link #metaIntField} which wraps this.
     */
    private static String metaField(String metaJson, String field) {
        int i = metaJson.indexOf("\"" + field + "\"");
        if (i < 0) {
            return null;
        }
        int colon = metaJson.indexOf(':', i + field.length() + 2);
        if (colon < 0) {
            return null;
        }
        int start = colon + 1;
        while (start < metaJson.length() && Character.isWhitespace(metaJson.charAt(start))) {
            start++;
        }
        if (start >= metaJson.length()) {
            return null;
        }
        if (metaJson.charAt(start) == '"') {
            int end = metaJson.indexOf('"', start + 1);
            return end < 0 ? null : metaJson.substring(start + 1, end);
        }
        int end = metaJson.indexOf(',', start);
        if (end < 0) {
            end = metaJson.indexOf('}', start);
        }
        return end < 0 ? null : metaJson.substring(start, end).trim();
    }

    private static int metaIntField(String metaJson, String field, int fallback) {
        String raw = metaField(metaJson, field);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void feed(MessageDigest md, String s) {
        md.update(s.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
    }

    private static String shortKey(String key) {
        return key.substring(0, 8);
    }

    // ------------------------------------------------------------------
    // Stats (used by PearlOptimizer report / tests)
    // ------------------------------------------------------------------

    public static long hits() {
        return HITS.get();
    }

    public static long misses() {
        return MISSES.get();
    }

    /** RAM hits; separate from {@link #hits()} (disk) and {@link #misses()} (shaderc). */
    public static long memoryHits() {
        return MEMORY_HITS.get();
    }

    /** Purge walks executed this session (report counter, never printed). */
    public static long purgeWalks() {
        return PURGE_WALKS.get();
    }

    /** Purge walks skipped by the throttle this session (report counter, never printed). */
    public static long purgeSkipped() {
        return PURGE_SKIPPED.get();
    }

    /** Waiters served from the winner's compile this session (report counter, never printed). */
    public static long dedupHits() {
        return DEDUP_HITS.get();
    }

    /** Waiters that detached (timeout/interrupt/unusable snapshot) and compiled their own copy (report counter, never printed). */
    public static long dedupTimeouts() {
        return DEDUP_TIMEOUTS.get();
    }

    /** Prefetch entries rejected by the integrity or interface guard this session (report counter, printed in the prefetch line). */
    public static long prefetchInvalid() {
        return PREFETCH_INVALID.get();
    }

    /** Prefetch entries left lazy by the budget guard this session (report counter, printed in the prefetch line). */
    public static long prefetchTruncated() {
        return PREFETCH_TRUNCATED.get();
    }

    /** Optimized prefetch entries skipped because the optimized profile is definitively off this session (report counter, printed in the prefetch line when non-zero). */
    public static long prefetchProfileSkipped() {
        return PREFETCH_PROFILE_SKIPPED.get();
    }

    /** Store latch state: true once disk writes are disabled for the session. Report surface for Diagnostics, never printed here. */
    static boolean storeLatchEngaged() {
        return STORE_DISABLED.get();
    }

    /** Failed disk writes this session (report counter for Diagnostics). */
    static long storesFailedTotal() {
        return STORES_FAILED.get();
    }

    /** Current consecutive-failure streak (report counter for Diagnostics). */
    static long storesFailureStreak() {
        return STORE_FAILURE_STREAK.get();
    }

    /** Stale optimized entries rejected by the interface guard this session (disk loads + prefetch; report counter for Diagnostics/tests). */
    public static long guardStaleRejected() {
        return GUARD_STALE_REJECTED.get();
    }

    /** Async dispatches accepted this session (report counter for the smoke suite). */
    static long storesQueuedTotal() {
        return STORES_QUEUED.get();
    }

    /** Fully persisted entries this session (report counter for the smoke suite). */
    static long storesWrittenTotal() {
        return STORES_WRITTEN.get();
    }

    public static void resetForTest() {
        INCLUDE_HASHES.clear();
        MEMORY_SPIRV.clear();
        MEMORY_LAST_ACCESS.clear();
        MEMORY_TOTAL_BYTES.set(0);
        MEMORY_HITS.set(0);
        MEMORY_START_REPORTED.set(false);
        HITS.set(0);
        MISSES.set(0);
        BROKEN.set(false);
        WARNED.set(false);
        PURGE_BYTES_SINCE.set(0);
        PURGE_FIRST_DONE.set(false);
        PURGE_IN_FLIGHT.set(false);
        PURGE_WALKS.set(0);
        PURGE_SKIPPED.set(0);
        IN_FLIGHT.clear();
        DEDUP_HITS.set(0);
        DEDUP_TIMEOUTS.set(0);
        INFLIGHT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
        LOADS_IN_FLIGHT.clear();
        LOAD_DEDUP_HITS.set(0);
        PREFETCH_KICKED.set(false);
        PREFETCH_INVALID.set(0);
        PREFETCH_TRUNCATED.set(0);
        PREFETCH_PROFILE_SKIPPED.set(0);
        STORES_QUEUED.set(0);
        STORES_WRITTEN.set(0);
        STORES_FAILED.set(0);
        STORE_DRAIN_HOOK_INSTALLED.set(false);
        STORE_DISABLED.set(false);
        STORE_FAILURE_STREAK.set(0);
        FIRST_FAILED_KEY.set(null);
        FIRST_FAILURE_CAUSE.set(null);
        STORE_LATCH_THRESHOLD = 3;
        GUARD_STALE_REJECTED.set(0);
        ShaderOptimization.resetForTest();
    }
}
