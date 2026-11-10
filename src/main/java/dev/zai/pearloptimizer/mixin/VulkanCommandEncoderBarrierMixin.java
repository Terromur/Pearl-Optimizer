package dev.zai.pearloptimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.zai.pearloptimizer.Diagnostics;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Diagnostics funnel (module b47b92e, census v2 caller attribution since
 * for the single central memory barrier of the renderpearl command
 * encoder.
 *
 * <p>Recon (javap, jar 26.3, 2026-11-05 + 2026-11-08): every call of the
 * static {@code VulkanCommandEncoder.memoryBarrier(VkCommandBuffer, MemoryStack)}
 * records ONE {@code KHRSynchronization2.vkCmdPipelineBarrier2KHR} with
 * hardcoded masks (srcStageMask=0x10000, srcAccessMask=0x18000, same for dst -
 * ALL_COMMANDS / MEMORY_READ|WRITE). Three kinds of code reach this static:
 * the private instance overload {@code memoryBarrier(MemoryStack)} (funnel for
 * ten instance methods: submitRenderPass, the four clear paths and the five
 * buffer/texture transfer paths), {@code endCommandBuffer()} on the init
 * command buffer path, and {@code VulkanTransientMemory.endSubmit()} - the
 * real-hardware census (2026-11-08, 7951+7025 frames) showed this funnel
 * carrying ~45 barriers/frame, 82% of the instrumented total. Wrapping the one
 * INVOKE inside the static body observes every one of them.</p>
 *
 * <p>The per-callsite hooks ({@code VulkanEncoderBarrierSitesMixin},
 * {@code VulkanTransientMemoryBarrierMixin}) tag the thread with a site label
 * via {@link Diagnostics#beginBarrierSite(String)} around the call that ends up
 * here. This funnel counts the barrier under that label when present; a run
 * without a label - a caller without a per-callsite hook (method drift in a
 * future Minecraft, or a foreign mod calling the engine) - falls back to the
 * masked unknown-caller bucket built from the very {@link VkDependencyInfo}
 * being recorded (the patch-B self-verifying pattern): masks drifting from the
 * recon show up as a new bucket instead of hiding behind a constant label.
 * Every barrier is counted exactly once - attributed or unknown.</p>
 *
 * <p>Passthrough: the handler forwards the untouched arguments to
 * {@code original.call}. The target method is static, therefore the handler is
 * static as well (same rule as in VulkanRenderPipelineMixin - a non-static
 * handler targeting a static method is rejected by Mixin at apply time).</p>
 *
 * <p>Cost: census off (default) - two volatile reads; census on - one
 * ThreadLocal get per barrier, and the live-mask struct read plus string
 * concatenation now happen ONLY for the unknown-caller fallback (the
 * attributed sites are constant labels), which is also cheaper on
 * the hot path.</p>
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderBarrierMixin {

    @WrapOperation(
        require = 0, // diagnostics only: a moved/renamed site must log a warning, not crash startup
        method = "memoryBarrier(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/system/MemoryStack;)V",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkCmdPipelineBarrier2KHR(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/vulkan/VkDependencyInfo;)V"
        )
    )
    private static void pearloptimizer$diagMemoryBarrier(
        VkCommandBuffer commandBuffer,
        VkDependencyInfo dependencyInfo,
        Operation<Void> original
    ) {
        if (Diagnostics.censusEnabled()) {
            String site = Diagnostics.currentBarrierSite();
            Diagnostics.onBarrier(site != null ? site : siteWithLiveMasks(dependencyInfo));
        }
        original.call(commandBuffer, dependencyInfo);
    }

    /**
     * Reads the first global ({@code VkMemoryBarrier2}) entry of the
     * dependency info and appends its four masks to the unknown-caller base
     * name. Never throws: an unexpected struct shape keeps the maskless
     * label, so the census keeps counting even when the read path drifts.
     */
    private static String siteWithLiveMasks(VkDependencyInfo dependencyInfo) {
        String base = "VulkanCommandEncoder.memoryBarrier(unknown-caller)";
        try {
            if (dependencyInfo != null) {
                VkMemoryBarrier2.Buffer globals = dependencyInfo.pMemoryBarriers();
                if (globals != null && globals.remaining() > 0) {
                    VkMemoryBarrier2 first = globals.get(0);
                    return base
                        + " srcStage=0x" + Long.toHexString(first.srcStageMask())
                        + " srcAccess=0x" + Long.toHexString(first.srcAccessMask())
                        + " dstStage=0x" + Long.toHexString(first.dstStageMask())
                        + " dstAccess=0x" + Long.toHexString(first.dstAccessMask());
                }
            }
        } catch (Throwable ignored) {
            // struct shape drift - the maskless label still identifies the site
        }
        return base;
    }
}
