package dev.zai.pearloptimizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pearl Optimizer - a client-side Fabric mod for Minecraft 26.3.
 *
 * <p>Reduces CPU overhead and hitching in the "renderpearl" Vulkan renderer:</p>
 * <ul>
 *   <li>dynamic GPU data storages start with a sane capacity instead of 1-2 blocks,
 *       eliminating the create-buffer/destroy-buffer cascade during world load;</li>
 *   <li>graphics pipelines are compiled through one shared {@code VkPipelineCache}
 *       per device instead of {@code VK_NULL_HANDLE} (vanilla 26.3 passes NULL);
 *       the same cache is persistent: its contents are serialized to
 *       disk at device close and fed back through {@code pInitialData} on the
 *       next launch, so the driver does not redo the pipeline preparation every
 *       run;</li>
 *   <li>identical create-once Vulkan objects - {@code VkShaderModule},
 *       {@code VkDescriptorSetLayout}, {@code VkPipelineLayout} - are
 *       deduplicated per device through a refcounted registry keyed by content
 *       (SPIR-V SHA-256, binding lists, push constant ranges), so repeated
 *       pipeline compiles (F3+T, resource reloads) reuse live GPU objects
 *       instead of recreating and re-destroying them;</li>
 *   <li>the GLSL -&gt; SPIR-V compilation is persisted to disk, so shaders are
 *       compiled by shaderc only once per source change instead of on every launch;</li>
 *   <li>shaders are compiled with shaderc optimization level PERFORMANCE while
 *       keeping the debug info (vanilla 26.3 uses level 0 with debug info); the
 *       override is guarded by a one-time runtime probe that verifies SPIR-V
 *       debug names survive, and falls back to the exact vanilla profile if
 *       they do not;</li>
 *   <li>optional present mode override (FIFO -&gt; MAILBOX) for lower input latency.</li>
 * </ul>
 *
 * <p>All modules are individually toggleable and fail-safe: on any error the mod
 * falls back to exact vanilla behaviour. Compatible with Sodium - Sodium does not
 * mixin into the renderpearl Vulkan backend or into the dynamic data storages,
 * it consumes the same public API and therefore inherits these optimizations.</p>
 */
public final class PearlOptimizer {
    public static final String MOD_ID = "pearl-optimizer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private PearlOptimizer() {
    }
}
