package dev.zai.pearloptimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import dev.zai.pearloptimizer.Diagnostics;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostics hooks (module b47b92e) on the swapchain surface: the two blit
 * barriers of {@code blitFromTexture} and the frame boundary.
 *
 * <p>Recon (javap, jar 26.3, 2026-11-05): {@code blitFromTexture} allocates a
 * transient command buffer, records one {@code VkImageMemoryBarrier2}
 * (oldLayout UNDEFINED -&gt; newLayout TRANSFER_DST, dstStage/dstAccess 0x1000),
 * then one {@code vkCmdBlitImage}, then one global {@code VkMemoryBarrier2}
 * (srcStage 0x1000/srcAccess 0x800 -&gt; dstStage 0x10000/dstAccess 0x18000) -
 * both barriers go through exactly two {@code vkCmdPipelineBarrier2KHR}
 * INVOKE sites inside this one method; both are recorded into a command
 * buffer (intra-CB). {@code present()} contains only {@code vkQueuePresentKHR}
 * and no barriers, so its HEAD is a clean per-frame boundary hook.</p>
 *
 * <p>The barrier handler is a passthrough (untouched arguments into
 * {@code original.call}, one counted increment); the target method is an
 * instance method, therefore the handler is an instance method. The frame
 * handler uses {@code require = 0}: if the target ever moves, Mixin logs a
 * warning and the game continues without frame statistics instead of
 * crashing at startup.</p>
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceBarrierMixin {

    @WrapOperation(
        require = 0, // diagnostics only: a moved/renamed site must log a warning, not crash startup
        method = "blitFromTexture",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkCmdPipelineBarrier2KHR(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/vulkan/VkDependencyInfo;)V"
        )
    )
    private void pearloptimizer$diagBlitBarrier(
        VkCommandBuffer commandBuffer,
        VkDependencyInfo dependencyInfo,
        Operation<Void> original
    ) {
        Diagnostics.onBarrier("VulkanGpuSurface.blitFromTexture");
        original.call(commandBuffer, dependencyInfo);
    }

    @Inject(method = "present", at = @At("HEAD"), require = 0)
    private void pearloptimizer$diagFrameBoundary(CallbackInfo ci) {
        Diagnostics.onFrame();
    }
}
