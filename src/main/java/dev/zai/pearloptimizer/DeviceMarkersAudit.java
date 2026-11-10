package dev.zai.pearloptimizer;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Diagnostic-only audit of the device-dependent cache-key flags.
 *
 * <p>One verbose-gated, once-per-session line that makes the three vanilla
 * {@code RENDERPEARL_*} shaderc marker conditions visible next to each other.
 * Verified against vanilla 26.3 ({@code GlslCompiler.createBaseShaderOptions}):
 * the macros are added through shaderc compile options (not through the GLSL
 * source), so they are invisible to the cache key's source hash:</p>
 *
 * <ul>
 *   <li>{@code RENDERPEARL_DEPTH_IS_ZERO_TO_ONE} &lt;- {@code DeviceInfo.isZeroToOne()}
 *       - covered by the cache key ({@code GlslCompilerAccessor});</li>
 *   <li>{@code RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE} &lt;-
 *       {@code DeviceFeatures.shaderDrawParameters()} - covered by the cache key;</li>
 *   <li>{@code RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE} &lt;-
 *       {@code HintsAndWorkarounds.isExplicitDepthRequired()} - covered by the
 *       cache key.</li>
 * </ul>
 *
 * <p>Zero behavior change: no state is read beyond the two accessor flags the
 * caller already reads, no exception escapes, the line is printed only with
 * {@code verboseLog=true} and only once per session.</p>
 */
public final class DeviceMarkersAudit {
    /** Exactly one report per session. */
    private static final AtomicBoolean REPORTED = new AtomicBoolean(false);

    private DeviceMarkersAudit() {
    }

    /**
     * Called from {@code ShaderCacheManager.loadOrCompile} right after the two
     * accessor flags are read; reuses those values, queries the explicit-depth
     * hint defensively (device may be gone during teardown). No exception
     * escapes: a config or logging backend failure must never reach the
     * hooked call.
     */
    public static void reportOnce(boolean zeroToOne, boolean drawParams) {
        try {
            PearlConfig config = PearlConfig.get();
            if (config == null || !config.verboseLog) {
                return;
            }
            if (!REPORTED.compareAndSet(false, true)) {
                return;
            }
            logMarkers(zeroToOne, drawParams);
        } catch (Throwable ignored) {
            // diagnostics only - never surface into the compile hook
        }
    }

    private static void logMarkers(boolean zeroToOne, boolean drawParams) {
        String explicitDepth;
        try {
            explicitDepth = String.valueOf(
                RenderSystem.getDevice().getDeviceInfo().hintsAndWorkarounds().isExplicitDepthRequired());
        } catch (Throwable t) {
            // Throwable, not Exception: LinkageError from a half-torn-down
            // device stack must degrade to the "unknown" marker, not escape.
            explicitDepth = "unknown (device unavailable)";
        }
        PearlOptimizer.LOGGER.info(
            "Device marker audit: RENDERPEARL_DEPTH_IS_ZERO_TO_ONE={}, "
                + "RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE={}, "
                + "RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE={} "
                + "(cache key fingerprints zeroToOne+drawParams+explicitDepth)",
            zeroToOne, explicitDepth, drawParams);
    }
}
