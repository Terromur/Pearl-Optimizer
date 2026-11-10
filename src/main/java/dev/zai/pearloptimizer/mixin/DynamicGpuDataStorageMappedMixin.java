package dev.zai.pearloptimizer.mixin;

import dev.zai.pearloptimizer.CapacityBumps;
import net.minecraft.client.renderer.DynamicGpuDataStorageMapped;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The mapped storage variant hosts Dynamic Transforms UBO, Terrain UBO,
 * Chunk Sections UBO and Chunk Sections Command Buffer. Vanilla constructs
 * them with an initial capacity of 1-2 blocks and re-creates the GPU buffer
 * on every growth step; we raise only the starting point. The value is still
 * rounded to a power of two by the vanilla {@code smallestEncompassingPowerOfTwo}
 * call that follows this injection, so all downstream math is unchanged.
 */
@Mixin(DynamicGpuDataStorageMapped.class)
public abstract class DynamicGpuDataStorageMappedMixin {

    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/Mth;smallestEncompassingPowerOfTwo(I)I"
        )
    )
    private int pearloptimizer$initialCapacity(int original) {
        return CapacityBumps.apply("mapped", original);
    }
}
