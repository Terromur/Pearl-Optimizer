package dev.zai.pearloptimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanTransientMemory;
import dev.zai.pearloptimizer.Diagnostics;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Caller attribution: the one non-encoder caller of the
 * {@code VulkanCommandEncoder.memoryBarrier} funnel. Diagnostics only - the
 * handler forwards the untouched arguments to {@code original.call}.
 *
 * <p>Recon (javap, jar 26.3, 2026-11-08): besides the eleven encoder methods,
 * the static funnel is called from exactly one more place in the engine -
 * {@code VulkanTransientMemory.endSubmit()} (INVOKESTATIC inside the instance
 * method, followed by the queue submit). These barriers are tagged and
 * reported as their own bucket, so the per-frame barrier budget of the
 * transient upload path becomes visible for the first time.</p>
 *
 * <p>Cost: census off (default) - the handler just forwards; census on - one
 * ThreadLocal set/get/remove per barrier.</p>
 */
@Mixin(VulkanTransientMemory.class)
public abstract class VulkanTransientMemoryBarrierMixin {

    @WrapOperation(
        require = 0, // diagnostics only: a moved/renamed site must degrade to the unknown bucket, not crash startup
        method = "endSubmit()V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/system/MemoryStack;)V"
        )
    )
    private void pearloptimizer$siteEndSubmit(VkCommandBuffer commandBuffer, MemoryStack stack, Operation<Void> original) {
        if (Diagnostics.censusEnabled()) {
            Diagnostics.beginBarrierSite("VulkanTransientMemory.endSubmit");
            try {
                original.call(commandBuffer, stack);
            } finally {
                Diagnostics.endBarrierSite();
            }
        } else {
            original.call(commandBuffer, stack);
        }
    }
}
