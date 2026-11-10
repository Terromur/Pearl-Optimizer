package dev.zai.pearloptimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.zai.pearloptimizer.Diagnostics;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Caller attribution: per-callsite tags for every vanilla
 * caller of the {@code VulkanCommandEncoder.memoryBarrier} funnel. Diagnostics
 * only - the handlers forward the untouched arguments to {@code original.call};
 * nothing in rendering, Vulkan calls or game behaviour changes.
 *
 * <p>Recon (javap, jar 26.3, 2026-11-08): the static funnel
 * {@code memoryBarrier(VkCommandBuffer, MemoryStack)} is reached from exactly
 * eleven encoder methods - the init-command-buffer flush
 * {@code endCommandBuffer()} calls the static directly, and ten instance
 * methods go through the private {@code memoryBarrier(MemoryStack)} overload:
 * {@code submitRenderPass}, {@code clearColorTexture},
 * {@code clearColorAndDepthTextures} (the 4-parameter overload; the 9-parameter
 * one contains no barrier INVOKE), {@code clearDepthTexture},
 * {@code writeToBuffer}, {@code copyToBuffer}, {@code writeToTexture},
 * {@code copyBufferToTexture}, {@code copyTextureToBuffer} (the 9-parameter
 * overload; the 5-parameter one delegates and contains no barrier INVOKE) and
 * {@code copyTextureToTexture}. Each {@code @WrapOperation} wraps exactly the
 * INVOKE of the funnel inside its method and tags the thread with the site
 * label around it, so the funnel wrap (VulkanCommandEncoderBarrierMixin) counts
 * the barrier under the attributed bucket. A drifted/renamed method (require=0)
 * simply stops attributing: its barriers resurface in the masked
 * unknown-caller bucket and the total stays exact.</p>
 *
 * <p>Handler signature rule: the ten instance-site handlers
 * wrap an INVOKE of an INSTANCE method (the private
 * {@code memoryBarrier(MemoryStack)} overload), so MixinExtras expects the
 * receiver as the first handler parameter - {@code (VulkanCommandEncoder self,
 * MemoryStack, Operation)} - and {@code original.call} must be forwarded the
 * receiver too. {@code require = 0} covers only target drift (zero matched
 * injection points); a handler-shape mismatch is validated by
 * {@code Injector.validateParams} and is fatal at apply time regardless of
 * {@code require} - a wrong shape would crash the game. The static
 * funnel INVOKE in {@code endCommandBuffer()} keeps the receiverless signature
 * {@code (VkCommandBuffer, MemoryStack, Operation)}.</p>
 *
 * <p>Cost: census off (default) - the handlers just forward, one volatile-free
 * pass; census on - one ThreadLocal set/get/remove per barrier (barriers never
 * nest, so a single ThreadLocal slot suffices).</p>
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanEncoderBarrierSitesMixin {

    /**
     * Shared body: tag the thread, forward, untag in finally. The varargs
     * forwarding matches {@code Operation.call(Object...)}; for the instance
     * wraps the array carries the receiver first ({@code self, stack}), for the
     * static wrap - the funnel arguments ({@code commandBuffer, stack}); the
     * arrays are tiny (1-2 elements) and the path only runs when the census
     * is on.
     */
    private void pearloptimizer$attributed(String site, Operation<Void> original, Object... instructionArgs) {
        if (Diagnostics.censusEnabled()) {
            Diagnostics.beginBarrierSite(site);
            try {
                original.call(instructionArgs);
            } finally {
                Diagnostics.endBarrierSite();
            }
        } else {
            original.call(instructionArgs);
        }
    }

    // ------------------------------------------------------------------
    // Init command buffer flush (calls the static funnel directly)
    // ------------------------------------------------------------------

    @WrapOperation(
        require = 0,
        method = "endCommandBuffer()V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/system/MemoryStack;)V"
        )
    )
    private void pearloptimizer$siteEndCommandBuffer(VkCommandBuffer commandBuffer, MemoryStack stack, Operation<Void> original) {
        this.pearloptimizer$attributed("VulkanCommandEncoder.endCommandBuffer(init)", original, commandBuffer, stack);
    }

    // ------------------------------------------------------------------
    // Render pass boundary (the expected dominant site)
    // ------------------------------------------------------------------

    @WrapOperation(
        require = 0,
        method = "submitRenderPass()V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"
        )
    )
    private void pearloptimizer$siteSubmitRenderPass(VulkanCommandEncoder self, MemoryStack stack, Operation<Void> original) {
        this.pearloptimizer$attributed("VulkanCommandEncoder.submitRenderPass", original, self, stack);
    }

    // ------------------------------------------------------------------
    // Clear paths
    // ------------------------------------------------------------------

    @WrapOperation(
        require = 0,
        method = "clearColorTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"
        )
    )
    private void pearloptimizer$siteClearColorTexture(VulkanCommandEncoder self, MemoryStack stack, Operation<Void> original) {
        this.pearloptimizer$attributed("VulkanCommandEncoder.clearColorTexture", original, self, stack);
    }

    @WrapOperation(
        require = 0,
        method = "clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"
        )
    )
    private void pearloptimizer$siteClearColorAndDepthTextures(VulkanCommandEncoder self, MemoryStack stack, Operation<Void> original) {
        this.pearloptimizer$attributed("VulkanCommandEncoder.clearColorAndDepthTextures", original, self, stack);
    }

    @WrapOperation(
        require = 0,
        method = "clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"
        )
    )
    private void pearloptimizer$siteClearDepthTexture(VulkanCommandEncoder self, MemoryStack stack, Operation<Void> original) {
        this.pearloptimizer$attributed("VulkanCommandEncoder.clearDepthTexture", original, self, stack);
    }

    // ------------------------------------------------------------------
    // Buffer and texture transfer paths
    // ------------------------------------------------------------------

    @WrapOperation(
        require = 0,
        method = "writeToBuffer(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Ljava/nio/ByteBuffer;)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"
        )
    )
    private void pearloptimizer$siteWriteToBuffer(VulkanCommandEncoder self, MemoryStack stack, Operation<Void> original) {
        this.pearloptimizer$attributed("VulkanCommandEncoder.writeToBuffer", original, self, stack);
    }

    @WrapOperation(
        require = 0,
        method = "copyToBuffer(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"
        )
    )
    private void pearloptimizer$siteCopyToBuffer(VulkanCommandEncoder self, MemoryStack stack, Operation<Void> original) {
        this.pearloptimizer$attributed("VulkanCommandEncoder.copyToBuffer", original, self, stack);
    }

    @WrapOperation(
        require = 0,
        method = "writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"
        )
    )
    private void pearloptimizer$siteWriteToTexture(VulkanCommandEncoder self, MemoryStack stack, Operation<Void> original) {
        this.pearloptimizer$attributed("VulkanCommandEncoder.writeToTexture", original, self, stack);
    }

    @WrapOperation(
        require = 0,
        method = "copyBufferToTexture(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;IIIILcom/mojang/renderpearl/api/textures/GpuTexture;IIIIII)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"
        )
    )
    private void pearloptimizer$siteCopyBufferToTexture(VulkanCommandEncoder self, MemoryStack stack, Operation<Void> original) {
        this.pearloptimizer$attributed("VulkanCommandEncoder.copyBufferToTexture", original, self, stack);
    }

    @WrapOperation(
        require = 0,
        method = "copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"
        )
    )
    private void pearloptimizer$siteCopyTextureToBuffer(VulkanCommandEncoder self, MemoryStack stack, Operation<Void> original) {
        this.pearloptimizer$attributed("VulkanCommandEncoder.copyTextureToBuffer", original, self, stack);
    }

    @WrapOperation(
        require = 0,
        method = "copyTextureToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/textures/GpuTexture;IIIIIII)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"
        )
    )
    private void pearloptimizer$siteCopyTextureToTexture(VulkanCommandEncoder self, MemoryStack stack, Operation<Void> original) {
        this.pearloptimizer$attributed("VulkanCommandEncoder.copyTextureToTexture", original, self, stack);
    }
}
