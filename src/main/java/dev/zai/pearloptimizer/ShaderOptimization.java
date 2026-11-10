package dev.zai.pearloptimizer;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Guards the shaderc optimization-level override with a one-time runtime probe.
 *
 * <p>The game reads <em>interface variable names</em> from the SPIR-V debug
 * info (spvc reflection) and builds {@code vertexShaderInputsByName} in
 * {@code PipelineBuilder.generateBackendCreateInfo} via
 * {@code Collectors.toMap(name, identity())}. If the SPIR-V loses its
 * {@code OpName} instructions, every name is empty and the second vertex
 * input of any pipeline crashes the game with
 * {@code IllegalStateException: Duplicate key ""}. Disabling shaderc
 * debug info reproduces this exact crash.</p>
 *
 * <p>Vanilla compiles with optimization level 0 <b>and</b> debug info. The
 * optimization override may only raise the level while keeping debug info
 * enabled, and even then only if the probe proves that the optimized profile
 * keeps the same reflected interface as the vanilla profile. The probe
 * compiles one fixture vertex shader under BOTH profiles and compares their
 * reflected vertex-stage interfaces (names, locations, storage class) parsed
 * structurally from the SPIR-V instruction stream. Any failure (compile
 * error, missing or changed reflection, unexpected throwable, no shaderc)
 * marks the optimized profile unsafe and the vanilla profile is used for the
 * whole session.</p>
 *
 * <p>The elimination-only verdict no longer needs a manual
 * opt-in: with the default {@code shaderOptimizationAllowElimination = AUTO}
 * (config key absent) the verdict is accepted automatically, and every
 * <b>real</b> compiled module is then verified by the interface guard -
 * the same structured reflection the probe uses, applied to the actual
 * SPIR-V before it reaches any cache layer or the game. The guard checks
 * exactly the invariants the game's slot-map construction relies on: every
 * vertex-stage input must carry a non-empty {@code OpName}, input names must
 * be pairwise distinct (the historical {@code Duplicate key ""} crash), and
 * Location decorations must be pairwise distinct. A violated module is
 * recompiled in the exact vanilla profile (forced through a thread-local so
 * the mixin leaves the vanilla level untouched for that one compile) and
 * never reaches the memory layer or the disk; three violations disarm the
 * whole optimized profile for the session with one WARN. This closes the
 * last trust gap of the probe: the probe validates one fixture, the guard
 * validates every real shader.</p>
 *
 * <p>The probe never searches the raw SPIR-V bytes
 * for name substrings: with debug info enabled the GLSL source travels inside
 * the module via {@code OpSource}, so a substring scan could "see" the input
 * names even when the reflected {@code OpName} instructions were stripped -
 * exactly the false positive that would later crash the game. The reflection
 * here walks the structured instruction stream ({@code OpName}, {@code
 * OpVariable} with storage class {@code Input}, {@code OpDecorate Location})
 * and compares the two stages' interface sets, mirroring what spvc reflection
 * feeds into {@code generateSlotMap}. The file name is also encoded with a
 * null terminator now: the previous {@code memUTF8(name, false)} call throws
 * IllegalArgumentException on LWJGL 3.4.3, which silently disabled the whole
 * module (the "probe failed" line every stand session saw).</p>
 */
public final class ShaderOptimization {
    private static volatile Boolean nameSafe;
    /** Human-readable reason of the last probe outcome (for verbose logging). */
    private static volatile String probeVerdict = "not run";

    /**
     * PASSED - the optimized stage reproduces the vanilla interface exactly.
     * ELIMINATION_ONLY - the optimized stage only REMOVED unused interface
     * variables (subset with preserved names and locations). Vanilla builds
     * the slot map from the actual module's reflection (location-keyed,
     * bytecode-verified in {@code generateSlotMap}), so removals are
     * structurally safe there; accepted when
     * {@code shaderOptimizationAllowElimination} is not explicitly false -
     * that includes AUTO (key absent, the default), which turns
     * the per-module interface guard on.
     * FAILED - anything else (compile error, reflection failure, added or
     * renamed variables).
     */
    private enum ProbeOutcome { PASSED, ELIMINATION_ONLY, FAILED }

    private ShaderOptimization() {
    }

    /**
     * Outcome name for the profile line, lowercased with hyphens
     * ("elimination-only"), matching the wording the javadoc and the verdict
     * text already use. The raw enum spelling must not leak through as
     * "elimination_only".
     */
    private static String probeLabel(ProbeOutcome outcome) {
        return outcome.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** True when shaders may be compiled at shaderc optimization level PERFORMANCE. */
    public static boolean isActive() {
        // The guard's vanilla fallback (thread-local, set only for the one
        // recompile) and the session disarm both short-circuit before any
        // probe bookkeeping: once disarmed, the vanilla profile is final for
        // the session even if a racing probe published a fresh verdict.
        if (VANILLA_FORCED.get() == Boolean.TRUE || GUARD_DISARMED.get()) {
            return false;
        }
        if (!PearlConfig.get().shaderOptimization) {
            return false;
        }
        Boolean cached = nameSafe;
        if (cached == null) {
            synchronized (ShaderOptimization.class) {
                cached = nameSafe;
                if (cached == null) {
                    ProbeOutcome outcome = probe();
                    // Tri-state: null = AUTO - elimination-only is accepted
                    // automatically with the per-module guard enabled; explicit
                    // true/false keep the explicit choice.
                    cached = switch (outcome) {
                        case PASSED -> Boolean.TRUE;
                        case ELIMINATION_ONLY ->
                            PearlConfig.get().shaderOptimizationAllowElimination != Boolean.FALSE;
                        case FAILED -> Boolean.FALSE;
                    };
                    if (GUARD_DISARMED.get()) {
                        cached = Boolean.FALSE; // disarmed mid-probe: do not republish an armed verdict
                    }
                    nameSafe = cached;
                    if (PearlConfig.get().verboseLog) {
                        String autoTag = outcome == ProbeOutcome.ELIMINATION_ONLY
                            && PearlConfig.get().shaderOptimizationAllowElimination == null ? " [auto]" : "";
                        PearlOptimizer.LOGGER.info("Shaderc profile: {}",
                            cached.booleanValue()
                                ? "PERFORMANCE + debug info (probe: " + probeLabel(outcome) + autoTag
                                    + ", per-module guard on)"
                                : "vanilla level 0 + debug info (" + probeLabel(outcome) + ": " + probeVerdict + ")");
                    }
                }
            }
        }
        return cached.booleanValue();
    }

    private static ProbeOutcome probe() {
        long compiler = 0L;
        long options = 0L;
        long vanillaResult = 0L;
        long perfResult = 0L;
        ByteBuffer fileName = null;
        ByteBuffer source = null;
        ByteBuffer entry = null;
        try {
            // pearlIn1 is deliberately unused: if optimization dead-eliminates
            // it (or strips OpName entirely), the optimized profile is unsafe.
            String glsl = "#version 450\n"
                + "layout(location = 0) in vec4 pearlIn0;\n"
                + "layout(location = 1) in vec4 pearlIn1;\n"
                + "void main() { gl_Position = pearlIn0; }\n";
            compiler = Shaderc.shaderc_compiler_initialize();
            options = Shaderc.shaderc_compile_options_initialize();
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
                Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_generate_debug_info(options);
            // Vanilla createBaseShaderOptions also preserves bindings
            // (set_preserve_bindings(true), bytecode-verified). Without it the
            // PERFORMANCE stage may dead-eliminate unused interface variables,
            // a difference real compiles never produce.
            Shaderc.shaderc_compile_options_set_preserve_bindings(options, true);
            // Null-terminated one-arg overload: memUTF8(name, false) throws
            // IllegalArgumentException on LWJGL 3.4.3, which used to
            // kill the probe before it compiled anything.
            fileName = MemoryUtil.memUTF8("pearl-optimizer-probe.vsh");
            source = MemoryUtil.memUTF8(glsl);
            // shaderc_compile_into_spv takes an EXPLICIT source length; the
            // LWJGL ByteBuffer overload passes remaining(). memUTF8 appends a
            // NUL terminator, and glslang fails on the trailing byte with
            // "unexpected token" at EOF (status 2). Exclude the terminator.
            // The file name and entry point have no explicit length in the C
            // API - those must stay null-terminated.
            source.limit(source.limit() - 1);
            entry = MemoryUtil.memUTF8("main");
            // LWJGL signature: (compiler, source_text, shader_kind,
            // input_file_name, entry_point_name, options). The
            // source text and the input file name were swapped here.
            // Stage 1: the vanilla profile (level 0 + debug info) defines the
            // reflection baseline the game itself relies on.
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_zero);
            vanillaResult = Shaderc.shaderc_compile_into_spv(compiler, source, Shaderc.shaderc_vertex_shader, fileName, entry, options);
            int vanillaStatus = Shaderc.shaderc_result_get_compilation_status(vanillaResult);
            if (vanillaStatus != 0) {
                probeVerdict = "compile failed at vanilla stage (shaderc status " + vanillaStatus + ")";
                return ProbeOutcome.FAILED;
            }
            TreeSet<InputVar> vanillaInterface = reflectVertexInputs(vanillaResult);
            // Stage 2: the optimized profile. The reflection of both stages
            // must be identical, and the baseline must actually contain both
            // probe inputs at their distinct locations - otherwise the
            // baseline itself is degenerate (e.g. debug info refused) and
            // says nothing about the optimized profile.
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
            perfResult = Shaderc.shaderc_compile_into_spv(compiler, source, Shaderc.shaderc_vertex_shader, fileName, entry, options);
            int perfStatus = Shaderc.shaderc_result_get_compilation_status(perfResult);
            if (perfStatus != 0) {
                probeVerdict = "compile failed at optimized stage (shaderc status " + perfStatus + ")";
                return ProbeOutcome.FAILED;
            }
            TreeSet<InputVar> perfInterface = reflectVertexInputs(perfResult);
            if (vanillaInterface == null) {
                probeVerdict = "reflection failed on vanilla module (truncated or corrupt stream)";
                return ProbeOutcome.FAILED;
            }
            if (perfInterface == null) {
                probeVerdict = "reflection failed on optimized module (truncated or corrupt stream)";
                return ProbeOutcome.FAILED;
            }
            if (!vanillaInterface.contains(new InputVar("pearlIn0", 0))
                || !vanillaInterface.contains(new InputVar("pearlIn1", 1))) {
                probeVerdict = "degenerate vanilla baseline (probe inputs missing from reflection)";
                return ProbeOutcome.FAILED;
            }
            if (vanillaInterface.equals(perfInterface)) {
                return ProbeOutcome.PASSED;
            }
            if (!perfInterface.isEmpty() && vanillaInterface.containsAll(perfInterface)) {
                // Pure removal of unused interface variables: names and
                // locations of every surviving variable are identical, so the
                // name map cannot produce duplicate keys and the location slot
                // map reflects the actual module. Opt-in via config.
                // Verdict text must not repeat the outcome name: isActive()
                // prefixes it as "elimination-only: <verdict>".
                probeVerdict = (vanillaInterface.size() - perfInterface.size())
                    + " unused input(s) removed, names and locations preserved";
                return ProbeOutcome.ELIMINATION_ONLY;
            }
            probeVerdict = "optimized interface differs from vanilla: vanilla=" + vanillaInterface
                + " optimized=" + perfInterface;
            return ProbeOutcome.FAILED;
        } catch (Throwable t) {
            probeVerdict = "probe threw " + t.getClass().getSimpleName() + ": " + t.getMessage();
            return ProbeOutcome.FAILED;
        } finally {
            if (vanillaResult != 0L) {
                Shaderc.shaderc_result_release(vanillaResult);
            }
            if (perfResult != 0L) {
                Shaderc.shaderc_result_release(perfResult);
            }
            if (options != 0L) {
                Shaderc.shaderc_compile_options_release(options);
            }
            if (compiler != 0L) {
                Shaderc.shaderc_compiler_release(compiler);
            }
            if (fileName != null) {
                MemoryUtil.memFree(fileName);
            }
            if (source != null) {
                MemoryUtil.memFree(source);
            }
            if (entry != null) {
                MemoryUtil.memFree(entry);
            }
        }
    }

    /** One reflected vertex-stage input: exact OpName string + Location decoration. */
    private record InputVar(String name, int location) implements Comparable<InputVar> {
        @Override
        public int compareTo(InputVar o) {
            int byLocation = Integer.compare(location, o.location);
            return byLocation != 0 ? byLocation : name.compareTo(o.name);
        }
    }

    /**
     * Result of parsing one SPIR-V module: the probe's comparison set (Input
     * variables that carry both an {@code OpName} and a {@code Location},
     * exactly what spvc reflection feeds into {@code generateSlotMap}) plus
     * the interface guard's verdict ({@code null} = the input interface is
     * structurally safe for the game's reflection-based name/slot maps;
     * non-null = the reason it is not). The set is ALWAYS complete - the
     * guard violations are computed in a second pass so a violating module
     * can never truncate the probe's comparison set.
     */
    record ModuleScan(TreeSet<InputVar> interfaceSet, String guardViolation) {
    }

    // ------------------------------------------------------------------
    // Interface guard: per-module verification of REAL compiles
    // ------------------------------------------------------------------

    /** Guard violations of freshly compiled optimized modules this session (report counter + disarm trigger). */
    private static final AtomicInteger GUARD_VIOLATIONS = new AtomicInteger();
    /** Session disarm latch: once set, isActive() is permanently false (the probe verdict is never republished over it). */
    private static final AtomicBoolean GUARD_DISARMED = new AtomicBoolean(false);
    /** WARN-once latch for the disarm message. */
    private static final AtomicBoolean GUARD_DISARM_WARNED = new AtomicBoolean(false);
    /**
     * Violations tolerated before the whole optimized profile disarms for the
     * session. Three = survive an isolated anomaly per module while still
     * stopping a systematic reflection regression (a shaderc/LWJGL update that
     * strips names from every module) after the third shader. Package-private
     * so the smoke suite can shrink it; not a user flag.
     */
    static volatile int GUARD_DISARM_THRESHOLD = 3;

    /**
     * Thread-local compile override for the guard's vanilla fallback: while
     * set on the compiling thread, {@link #isActive()} is false, so the
     * {@code ShaderOptimizationMixin} leaves the vanilla optimization level
     * untouched for exactly that one recompile. Never set across an await or
     * a thread handoff (the fallback recompile is synchronous on the caller).
     */
    private static final ThreadLocal<Boolean> VANILLA_FORCED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Forces the vanilla profile for compiles on the current thread (guard fallback; must be paired with {@link #endVanillaCompile()} in finally). */
    static void beginVanillaCompile() {
        VANILLA_FORCED.set(Boolean.TRUE);
    }

    /** Clears the vanilla-compile override (finally pairing of {@link #beginVanillaCompile()}). */
    static void endVanillaCompile() {
        VANILLA_FORCED.set(Boolean.FALSE);
    }

    /** Session guard violation count (report surface for Diagnostics/tests). */
    static int guardViolations() {
        return GUARD_VIOLATIONS.get();
    }

    /** Session disarm state (report surface for Diagnostics/tests). */
    static boolean guardDisarmed() {
        return GUARD_DISARMED.get();
    }

    /**
     * Counts one interface-guard violation of a freshly compiled optimized
     * module (the caller already recompiled the shader in the vanilla
     * profile). Reaching {@link #GUARD_DISARM_THRESHOLD} disarms the whole
     * optimized profile for the session with one WARN: the vanilla profile
     * is the game's own bytes and cannot be improved on safely when the
     * reflection contract itself is broken.
     */
    static void noteGuardViolation(String reason) {
        int count = GUARD_VIOLATIONS.incrementAndGet();
        if (count >= GUARD_DISARM_THRESHOLD) {
            GUARD_DISARMED.set(true);
            nameSafe = Boolean.FALSE; // belt: even a racing isActive() re-reads GUARD_DISARMED first
            if (GUARD_DISARM_WARNED.compareAndSet(false, true)) {
                PearlOptimizer.LOGGER.warn("Shader optimization disarmed for this session after {} interface guard "
                    + "violations (last reason: {}); every shader compiles in the vanilla profile "
                    + "(level 0 + debug info) until the next launch", count, reason);
            }
        }
    }

    /**
     * Interface guard verdict for one module's SPIR-V bytes: {@code null} =
     * the input interface satisfies the invariants the game's reflection-based
     * name/slot maps rely on (every Input variable named with a non-empty,
     * pairwise-distinct {@code OpName}; Location decorations pairwise
     * distinct); non-null = the reason it does not. Structurally corrupt or
     * non-SPIR-V bytes are a violation too - a module the guard cannot parse
     * is a module the game's reflection could choke on. Used for freshly
     * compiled optimized modules AND for stale optimized disk entries (the
     * pre-guard sessions could persist one).
     */
    static String guardModuleInterface(byte[] spv) {
        if (spv == null || spv.length < 20 || (spv.length & 3) != 0) {
            return "not a well-formed SPIR-V module (size " + (spv == null ? -1 : spv.length) + ")";
        }
        // parseModule performs the magic check and returns null for any
        // structural surprise, including bad magic.
        ModuleScan scan = parseModule(spv);
        if (scan == null) {
            return "module not parseable (truncated, corrupt, or bad magic)";
        }
        return scan.guardViolation();
    }

    /** Session reset for the smoke harnesses (never called in production). */
    static void resetForTest() {
        nameSafe = null;
        probeVerdict = "not run";
        GUARD_VIOLATIONS.set(0);
        GUARD_DISARMED.set(false);
        GUARD_DISARM_WARNED.set(false);
        GUARD_DISARM_THRESHOLD = 3;
        VANILLA_FORCED.remove();
    }

    // SPIR-V opcodes/enums used by the scan (Spec constants from the SPIR-V spec).
    private static final int OP_NAME = 5;
    private static final int OP_DECORATE = 71;
    private static final int OP_VARIABLE = 59;
    private static final int DECORATION_LOCATION = 30;
    private static final int STORAGE_CLASS_INPUT = 1;
    // The magic word itself lives in PearlUtil -
    // one copy shared with the cache readers (see isSpirvMagic).

    /**
     * Structured reflection of the vertex-stage interface of a shaderc
     * result: snapshots the module bytes and parses them with
     * {@link #parseModule}. Returns the probe comparison set, or {@code
     * null} on any structural surprise (bad header, truncated instruction,
     * out-of-range reference); the caller treats {@code null} as "optimized
     * profile unsafe".
     */
    static TreeSet<InputVar> reflectVertexInputs(long result) {
        try {
            ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
            if (bytes == null) {
                return null;
            }
            byte[] raw = new byte[bytes.remaining()];
            bytes.duplicate().get(raw);
            ModuleScan scan = parseModule(raw);
            return scan == null ? null : scan.interfaceSet();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Parses one SPIR-V module (never a substring scan): walks the
     * structured instruction stream collecting {@code OpName}, {@code
     * OpVariable} with storage class {@code Input} and {@code OpDecorate
     * Location} by result-id - the same picture spvc reflection feeds into
     * {@code PipelineBuilder.generateSlotMap}. Returns {@code null} on any
     * structural surprise; a parseable module returns the complete probe
     * comparison set plus the guard verdict (see {@link ModuleScan}).
     */
    static ModuleScan parseModule(byte[] spv) {
        try {
            if (spv == null || spv.length < 20 || (spv.length & 3) != 0) {
                return null;
            }
            IntBuffer words = ByteBuffer.wrap(spv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            if (!PearlUtil.isSpirvMagic(words.get(0))) {
                return null;
            }
            // Header: magic, version, generator, bound, schema (5 words).
            Map<Integer, String> names = new HashMap<>();
            Map<Integer, Integer> locations = new HashMap<>();
            TreeSet<Integer> inputVariables = new TreeSet<>();
            int i = 5;
            while (i < words.limit()) {
                int instruction = words.get(i);
                int wordCount = instruction >>> 16;
                int opcode = instruction & 0xFFFF;
                if (wordCount < 1 || i + wordCount > words.limit()) {
                    return null; // truncated stream - structurally unusable
                }
                if (opcode == OP_NAME) {
                    // Known opcode with a fixed minimum size: fewer words means
                    // a corrupt module, not an unknown instruction (unknown
                    // opcodes are what the skip below is for).
                    if (wordCount < 3) {
                        return null;
                    }
                    int target = words.get(i + 1);
                    String name = literalString(words, i + 2, i + wordCount);
                    if (name == null) {
                        return null;
                    }
                    names.put(target, name);
                } else if (opcode == OP_DECORATE) {
                    // Spec minimum is 3 words: decorations without a literal
                    // operand (e.g. "OpDecorate %gl_PerVertex Block", present
                    // in every real vertex module) are valid and were wrongly
                    // treated as corruption - the scanner never reflected a
                    // real module, only synthetic test fixtures.
                    if (wordCount < 3) {
                        return null;
                    }
                    if (wordCount >= 4 && words.get(i + 2) == DECORATION_LOCATION) {
                        locations.put(words.get(i + 1), words.get(i + 3));
                    }
                } else if (opcode == OP_VARIABLE) {
                    if (wordCount < 4) {
                        return null;
                    }
                    if (words.get(i + 3) == STORAGE_CLASS_INPUT) {
                        inputVariables.add(words.get(i + 2));
                    }
                }
                i += wordCount;
            }
            // Pass 1 (probe comparison set): Input
            // variables that carry both an OpName and a Location.
            TreeSet<InputVar> reflected = new TreeSet<>();
            for (int variable : inputVariables) {
                String name = names.get(variable);
                Integer location = locations.get(variable);
                if (name != null && location != null) {
                    reflected.add(new InputVar(name, location));
                }
            }
            // Pass 2 (guard): the invariants the game's name/slot maps rely on.
            // Locations are NOT required on every input (built-in-style variables
            // legitimately lack one and the game never maps them by location),
            // but every input - located or not - feeds the name map, so a
            // missing/empty or duplicate OpName is exactly the historical
            // "Duplicate key """ crash surface.
            Set<String> seenNames = new HashSet<>();
            Set<Integer> seenLocations = new HashSet<>();
            for (int variable : inputVariables) {
                String name = names.get(variable);
                if (name == null || name.isEmpty()) {
                    return new ModuleScan(reflected, "input variable id=" + variable
                        + " has no OpName (debug info lost or unnamed input)");
                }
                if (!seenNames.add(name)) {
                    return new ModuleScan(reflected, "duplicate input name \"" + name
                        + "\" (Collectors.toMap duplicate key)");
                }
                Integer location = locations.get(variable);
                if (location != null && !seenLocations.add(location)) {
                    return new ModuleScan(reflected, "duplicate input location " + location
                        + " (slot map collision)");
                }
            }
            return new ModuleScan(reflected, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Reads the null-terminated literal string starting at word {@code from}
     * and ending before word {@code endExclusive} (SPIR-V pads literals with
     * zero bytes to the next word boundary; a string may span several words).
     * Returns {@code null} when no terminator exists inside the range.
     */
    private static String literalString(IntBuffer words, int from, int endExclusive) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean terminated = false;
        for (int w = from; w < endExclusive && !terminated; w++) {
            int word = words.get(w);
            for (int b = 0; b < 4; b++) {
                int value = (word >>> (b * 8)) & 0xFF;
                if (value == 0) {
                    terminated = true;
                    break;
                }
                out.write(value);
            }
        }
        if (!terminated) {
            return null;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
