package dev.zai.pearloptimizer.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.zai.pearloptimizer.Diagnostics;
import dev.zai.pearloptimizer.PearlConfig;
import dev.zai.pearloptimizer.PearlOptimizer;
import dev.zai.pearloptimizer.PersistentPipelineCache;
import dev.zai.pearloptimizer.SharedPipelineCache;
import dev.zai.pearloptimizer.VulkanObjectCache;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one safe save point for the persistent pipeline cache. At the very start
 * of {@code VulkanDevice.close} (called from {@code RenderSystem.shutdownRenderer})
 * the device - and with it the shared {@code VkPipelineCache} - is still alive,
 * so {@code vkGetPipelineCacheData} is valid; the frame loop is long gone, so
 * the serialization never runs inside a frame.
 *
 * <p>The handler swallows every throwable inside
 * {@link PersistentPipelineCache#saveOnDeviceClose}: the vanilla teardown
 * sequence (checkpoint extension, command encoder, VMA, {@code vkDestroyDevice})
 * continues regardless of any IO or Vulkan problem.</p>
 *
 * <p>There is also one flush point <i>after</i> the vanilla teardown work:
 * {@code pearloptimizer$flushRetiredAtDeviceDestroy} runs right before the
 * {@code VK12.vkDestroyDevice} call and explicitly destroys the objects the
 * modules deliberately left to the device teardown (the retired object-cache
 * registry and the shared {@code VkPipelineCache}). Without that flush the
 * validation layer reports them as leaked at {@code vkDestroyDevice}
 * (VUID-vkDestroyDevice-device-05137).</p>
 */
@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin {

    @Shadow
    @Final
    private VkDevice vkDevice;

    /**
     * The single close-HEAD hook. Runs the three modules in their fixed,
     * documented order at the one safe point (device still alive, frame loop
     * long gone - so {@code vkGetPipelineCacheData} is valid and nothing here
     * can race the frame):
     * <ol>
     *   <li>{@link PersistentPipelineCache#saveOnDeviceClose} - serialize the
     *       shared pipeline cache to disk; swallows every throwable so the
     *       vanilla teardown sequence (checkpoint extension, command encoder,
     *       VMA, {@code vkDestroyDevice}) continues regardless of any IO or
     *       Vulkan problem;</li>
     *   <li>{@link VulkanObjectCache#onDeviceClose} - retire the per-device
     *       object-cache registry. Nothing is destroyed here: at close HEAD
     *       the GPU has not been waited on yet, and queued pipeline destroys
     *       still run afterwards inside {@code commandEncoder.destroy()};
     *       those destroys are absorbed against the retired registry, while
     *       the surviving objects are destroyed explicitly by
     *       {@link VulkanObjectCache#flushRetired} right before
     *       {@code vkDestroyDevice} (see the INVOKE hook below);</li>
     *   <li>{@link Diagnostics#dumpAtClose} - final barrier-census and
     *       compile-phase dump (diagnostics only).</li>
     * </ol>
     * The three former separate {@code @At("HEAD")} injects are
     * merged into this one handler: same order, one injection point, and the
     * handler order between same-point injects is no longer load-bearing.
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void pearloptimizer$atCloseHead(CallbackInfo ci) {
        PersistentPipelineCache.saveOnDeviceClose(this.vkDevice);
        VulkanObjectCache.onDeviceClose(this.vkDevice);
        Diagnostics.dumpAtClose();
    }

    /**
     * Explicit flush of everything the modules deliberately left to
     * {@code vkDestroyDevice}: the retired object-cache registry (shader
     * modules, then pipeline layouts, then descriptor set layouts) and the
     * shared {@code VkPipelineCache}. Runs right before the
     * {@code VK12.vkDestroyDevice} call - after
     * {@code commandEncoder.destroy()} drained the queue, so every queued
     * destroy was already absorbed and the GPU is idle. Without this flush
     * the validation layer reports the surviving objects as leaked
     * (VUID-vkDestroyDevice-device-05137).
     *
     * <p>{@code require = 0} keeps the game running even if the injection
     * point ever moves (the objects then die with the device, exactly as
     * vanilla); both flush calls swallow every throwable internally, and any
     * unexpected failure of the handler itself is reported once and
     * swallowed.</p>
     */
    @Inject(method = "close", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkDestroyDevice(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkAllocationCallbacks;)V"), require = 0)
    private void pearloptimizer$flushRetiredAtDeviceDestroy(CallbackInfo ci) {
        try {
            long[] flushed = VulkanObjectCache.flushRetired(this.vkDevice);
            boolean pipelineCache = SharedPipelineCache.destroy(this.vkDevice);
            if (PearlConfig.get().verboseLog) {
                PearlOptimizer.LOGGER.info(
                    "Device close flush: flushed_modules={}, flushed_dsl={}, flushed_pl={}, flushed_pipeline_cache={}",
                    flushed[0], flushed[1], flushed[2], pipelineCache ? 1 : 0);
            }
        } catch (Throwable t) {
            VulkanObjectCache.reportFlushFailure(t);
        }
    }
}
