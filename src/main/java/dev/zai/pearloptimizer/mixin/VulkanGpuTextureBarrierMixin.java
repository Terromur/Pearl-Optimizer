package dev.zai.pearloptimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import dev.zai.pearloptimizer.Diagnostics;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Diagnostics hook (module b47b92e) for the legacy (non-Synchronization2)
 * barrier in the {@link VulkanGpuTexture} constructor.
 *
 * <p>Recon (javap, jar 26.3, 2026-11-05): the constructor records exactly one
 * {@code VK12.vkCmdPipelineBarrier} through the LWJGL count-less convenience
 * overload (descriptor below, verbatim from the recon constant pool), an
 * image layout transition recorded into an init command buffer (intra-CB).
 * The three int arguments are the runtime mask values, so the site label
 * carries them verbatim in hex instead of a decoded guess.</p>
 *
 * <p>Passthrough only; the target (a constructor) is an instance context,
 * therefore the handler is an instance method.</p>
 */
@Mixin(VulkanGpuTexture.class)
public abstract class VulkanGpuTextureBarrierMixin {

    @WrapOperation(
        require = 0, // diagnostics only: a moved/renamed site must log a warning, not crash startup
        method = "<init>(Lcom/mojang/renderpearl/backend/vulkan/VulkanDevice;ILjava/lang/String;Lcom/mojang/renderpearl/api/GpuFormat;IIII)V",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCmdPipelineBarrier(Lorg/lwjgl/vulkan/VkCommandBuffer;IIILorg/lwjgl/vulkan/VkMemoryBarrier$Buffer;Lorg/lwjgl/vulkan/VkBufferMemoryBarrier$Buffer;Lorg/lwjgl/vulkan/VkImageMemoryBarrier$Buffer;)V"
        )
    )
    private void pearloptimizer$diagInitBarrier(
        VkCommandBuffer commandBuffer,
        int srcStageMask,
        int dstStageMask,
        int dependencyFlags,
        VkMemoryBarrier.Buffer memoryBarriers,
        VkBufferMemoryBarrier.Buffer bufferMemoryBarriers,
        VkImageMemoryBarrier.Buffer imageMemoryBarriers,
        Operation<Void> original
    ) {
        // Gate-checked overload: the site label (string concat + two hex
        // renderings) is built only when the instrument is actually enabled;
        // with verboseLog=false this call is two volatile reads.
        Diagnostics.onBarrierMasks(srcStageMask, dstStageMask);
        original.call(commandBuffer, srcStageMask, dstStageMask, dependencyFlags,
            memoryBarriers, bufferMemoryBarriers, imageMemoryBarriers);
    }
}
