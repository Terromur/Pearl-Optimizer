package dev.zai.pearloptimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import dev.zai.pearloptimizer.PresentModeOverride;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Set;

/**
 * Inside {@code configure} the game maps the requested
 * {@link GpuSurface.PresentMode} to a Vulkan constant through
 * {@code VulkanConst.toVk}. We wrap exactly that call (the method contains
 * precisely one such invocation) and optionally substitute MAILBOX for FIFO.
 * The surface's own support set - queried by vanilla during construction - is
 * used for the safety check, so an unsupported mode can never reach the driver.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {

    @Shadow
    @Final
    private Set<GpuSurface.PresentMode> supportedPresentModes;

    @WrapOperation(
        method = "configure",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanConst;toVk(Lcom/mojang/renderpearl/api/device/GpuSurface$PresentMode;)I"
        )
    )
    private int pearloptimizer$presentModeOverride(GpuSurface.PresentMode mode, Operation<Integer> operation) {
        return PresentModeOverride.apply(mode, operation.call(mode), supportedPresentModes);
    }
}
