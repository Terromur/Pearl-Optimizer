package dev.zai.pearloptimizer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Config for Pearl Optimizer, loaded lazily right before the first hooked call.
 * Values can be overridden with JVM system properties:
 *
 * <ul>
 *   <li>{@code -Dpearloptimizer.buffers=false} - disable the capacity bump module</li>
 *   <li>{@code -Dpearloptimizer.capacity=128}  - initial capacity in blocks (0 = vanilla)</li>
 *   <li>{@code -Dpearloptimizer.pipelinecache=false} - disable the shared pipeline cache</li>
 *   <li>{@code -Dpearloptimizer.pipecachedisk=false} - disable the persistent on-disk pipeline cache</li>
 *   <li>{@code -Dpearloptimizer.objcache=false} - disable the create-once Vulkan object cache (shader modules, descriptor set layouts, pipeline layouts)</li>
 *   <li>{@code -Dpearloptimizer.presentmode=mailbox|vanilla} - present mode override</li>
 *   <li>{@code -Dpearloptimizer.shadercache=false} - disable the persistent SPIR-V shader cache</li>
 *   <li>{@code -Dpearloptimizer.spirvmemory=false} - disable the in-memory SPIR-V deduplication layer (requires the shader cache)</li>
 *   <li>{@code -Dpearloptimizer.purgethrottle=false} - every shader-cache store walks the cache directory (pre-throttle behaviour)</li>
 *   <li>{@code -Dpearloptimizer.asyncstore=false} - persist shader-cache entries synchronously on the compile thread (default is async on a background daemon thread)</li>
 *   <li>{@code -Dpearloptimizer.inflightdedup=false} - disable single-flight: parallel MISS on one shader key compile independently (default on)</li>
 *   <li>{@code -Dpearloptimizer.shadopt=false} - keep vanilla shaderc settings (level 0 + debug info)</li>
 *   <li>{@code -Dpearloptimizer.shadoptelim=auto|true|false} - elimination acceptance of the shaderc optimization probe: {@code auto} (default - the verdict is accepted automatically and every real compiled module is verified by the SPIR-V interface guard), {@code true} (explicit opt-in without AUTO, the guard still verifies every real module), {@code false} (reject elimination, exact vanilla profile)</li>
 *   <li>{@code -Dpearloptimizer.cachemaxmb=64} - shader cache size cap in megabytes</li>
 *   <li>{@code -Dpearloptimizer.log=true} - enable the verbose module report (off by default; warnings always print)</li>
 * </ul>
 */
public final class PearlConfig {
    /** Bump the initial capacity of the five DynamicGpuDataStorage instances. */
    public boolean bumpInitialCapacity = true;
    /** Initial capacity in blocks (vanilla starts at 1-2). Clamped to [0, 65536]; 0 = vanilla. */
    public int initialCapacityBlocks = 128;
    /** Compile pipelines through one shared VkPipelineCache per device. */
    public boolean sharedPipelineCache = true;
    /** Persist the shared VkPipelineCache to disk at device close and seed new caches from it via pInitialData. Requires {@link #sharedPipelineCache}; failures fall back to an empty cache (exact vanilla behaviour). */
    public boolean persistentPipelineCache = true;
    /** Deduplicate identical create-once Vulkan objects (VkShaderModule, VkDescriptorSetLayout, VkPipelineLayout) per device with a refcounted registry; any failure falls back to the exact vanilla create/destroy. */
    public boolean objectCache = true;
    /** "vanilla" (default) or "mailbox" (FIFO -&gt; MAILBOX when the surface supports it). */
    public String presentMode = "vanilla";
    /** Persist compiled SPIR-V between launches (GLSL -&gt; SPIR-V runs once, then loads from disk). */
    public boolean shaderCache = true;
    /** Deduplicate the compiled SPIR-V in memory under the same key as the disk cache (hits skip disk and shaderc within one session). Requires {@link #shaderCache}. */
    public boolean spirvMemoryCache = true;
    /** Throttle the shader-cache purge walk: the first walk of a session is unconditional, afterwards a walk runs only once per threshold of newly persisted bytes (threshold = max(1 MB, cap/8), clamped to cap/2). {@code false} restores the pre-throttle behaviour (every store walks). */
    public boolean purgeThrottle = true;
    /** Asynchronous persisting of shader-cache entries: the compile thread only snapshots the SPIR-V bytes into a heap array, the SHA-256 sums, directory creation and both atomic file writes run on one background daemon thread. Cuts the disk tail off every cold-start compile hitch (the render thread no longer waits on filesystem IO). Fail-safe: on any executor problem the store runs synchronously, byte-identical to the pre-async behaviour; a pending write lost at JVM exit is a plain cache miss next launch. */
    public boolean asyncStore = true;
    /** In-flight compilation single-flight: parallel MISS on the same shader key share the winner's compile (waiters rebuild their own module from the shared SPIR-V snapshot). Default {@code true}; requires {@link #shaderCache}. A JSON without the key keeps the default; an explicit JSON {@code false} disables it (explicit value wins); {@code -Dpearloptimizer.inflightdedup=false} restores the pre-module behaviour (every parallel MISS compiles). */
    public Boolean inflightDedup = Boolean.TRUE;
    /** Compile shaders with shaderc optimization level PERFORMANCE, keeping debug info (vanilla: level 0 + debug info). Guarded by a runtime probe: if SPIR-V debug names do not survive, the vanilla profile is used. */
    public boolean shaderOptimization = true;
    /**
     * Tri-state acceptance of the probe's elimination-only verdict.
     *
     * <p>{@code null} - AUTO (new default): when the probe verdict is
     * elimination-only, the optimized profile is accepted automatically, and
     * every real compiled module is verified by the SPIR-V interface guard
     * (every vertex-stage input must have a non-empty unique {@code OpName},
     * locations must be unique). A violated module is recompiled in the exact
     * vanilla profile and never reaches any cache layer; three violations
     * disarm the whole optimized profile for the session. An old config JSON
     * without this key upgrades to AUTO silently; the decision table is in
     * {@link ShaderOptimization}.</p>
     *
     * <p>{@code TRUE} - explicit opt-in: accept elimination
     * (the slot map is built from the actual module reflection, so removals
     * are structurally safe); the per-module guard still verifies every real
     * compile. {@code FALSE} - reject elimination, exact vanilla profile (the
     * default). An explicit JSON {@code false}/{@code true} keeps
     * the owner's choice - only the ABSENT key means AUTO.
     * {@code -Dpearloptimizer.shadoptelim=auto|true|false} overrides the JSON.</p>
     */
    public Boolean shaderOptimizationAllowElimination = null;
    /** Shader cache directory cap in megabytes; oldest entries are purged when exceeded. Clamped to [8, 1024]. */
    public int shaderCacheMaxSizeMb = 64;
    /** Verbose one-line report per applied module (HIT/MISS lines, module start/stop reports, diagnostics). Off by default: the mod stays quiet unless something fails - warnings print regardless of this flag. Enable in the config or with {@code -Dpearloptimizer.log=true}. */
    public boolean verboseLog = false;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Published only after full construction - the single volatile guard of the lazy load. */
    private static volatile PearlConfig instance;

    /**
     * The hot path is one volatile read ({@code instance} is only
     * published after full construction, so the null check is the guard): this
     * method sits at the head of every module hook, several of which run per
     * pipeline compile and per frame. The synchronized block runs exactly once.
     * The mutable static also keeps the smoke harness contract
     * ({@code resetConfigSingleton} reflects over {@code PearlConfig.instance}).
     */
    public static PearlConfig get() {
        PearlConfig snapshot = instance;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (PearlConfig.class) {
            if (instance == null) {
                instance = load();
            }
            return instance;
        }
    }

    public boolean mailboxRequested() {
        return presentMode != null && presentMode.trim().equalsIgnoreCase("mailbox");
    }

    public int effectiveInitialCapacity() {
        return Math.clamp(initialCapacityBlocks, 0, 65536);
    }

    private static PearlConfig load() {
        PearlConfig config = null;
        try {
            Path path = configPath();
            if (Files.exists(path)) {
                config = GSON.fromJson(Files.readString(path), PearlConfig.class);
            } else {
                Files.createDirectories(path.getParent());
                config = new PearlConfig();
                Files.writeString(path, GSON.toJson(config));
            }
        } catch (Throwable t) {
            PearlOptimizer.LOGGER.warn("Failed to read or create config/pearl-optimizer.json, using defaults", t);
            config = null;
        }
        if (config == null) {
            config = new PearlConfig();
        }
        config.normalize();
        applySystemProperties(config);
        return config;
    }

    private void normalize() {
        if (presentMode == null) presentMode = "vanilla";
        if (inflightDedup == null) inflightDedup = Boolean.TRUE;
        // shaderOptimizationAllowElimination: null = AUTO, intentionally left untouched.
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("pearl-optimizer.json");
    }

    private static void applySystemProperties(PearlConfig config) {
        applyBool  ("pearloptimizer.buffers",       v -> config.bumpInitialCapacity = v);
        applyInt   ("pearloptimizer.capacity",      v -> config.initialCapacityBlocks = v);
        applyBool  ("pearloptimizer.pipelinecache", v -> config.sharedPipelineCache = v);
        applyBool  ("pearloptimizer.pipecachedisk", v -> config.persistentPipelineCache = v);
        applyBool  ("pearloptimizer.objcache",      v -> config.objectCache = v);
        applyString("pearloptimizer.presentmode",   v -> config.presentMode = v);
        applyBool  ("pearloptimizer.shadercache",   v -> config.shaderCache = v);
        applyBool  ("pearloptimizer.spirvmemory",   v -> config.spirvMemoryCache = v);
        applyBool  ("pearloptimizer.purgethrottle", v -> config.purgeThrottle = v);
        applyBool  ("pearloptimizer.asyncstore",    v -> config.asyncStore = v);
        applyBool  ("pearloptimizer.inflightdedup", v -> config.inflightDedup = v);
        applyBool  ("pearloptimizer.shadopt",       v -> config.shaderOptimization = v);
        applyInt   ("pearloptimizer.cachemaxmb",    v -> config.shaderCacheMaxSizeMb = v);
        applyBool  ("pearloptimizer.log",           v -> config.verboseLog = v);

        // Tri-state: auto = AUTO (clears even an explicit JSON value); true = explicit
        // opt-in; false AND anything unrecognized resolve to the safe explicit rejection
        // (garbage is never opted in).
        String elim = System.getProperty("pearloptimizer.shadoptelim");
        if (elim != null) {
            String v = elim.trim().toLowerCase(Locale.ROOT);
            if (v.equals("auto"))      config.shaderOptimizationAllowElimination = null;
            else if (v.equals("true")) config.shaderOptimizationAllowElimination = Boolean.TRUE;
            else                       config.shaderOptimizationAllowElimination = Boolean.FALSE;
        }
    }

    private static void applyBool(String key, Consumer<Boolean> setter) {
        String v = System.getProperty(key);
        if (v != null) setter.accept(!"false".equalsIgnoreCase(v.trim()));
    }

    private static void applyInt(String key, IntConsumer setter) {
        String v = System.getProperty(key);
        if (v != null && !v.isBlank()) {
            try { setter.accept(Integer.parseInt(v.trim())); }
            catch (NumberFormatException ignored) { }
        }
    }

    private static void applyString(String key, Consumer<String> setter) {
        String v = System.getProperty(key);
        if (v != null && !v.isBlank()) setter.accept(v.trim());
    }
}
