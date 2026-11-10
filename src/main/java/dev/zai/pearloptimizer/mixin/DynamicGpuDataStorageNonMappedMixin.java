package dev.zai.pearloptimizer.mixin;

import dev.zai.pearloptimizer.CapacityBumps;
import net.minecraft.client.renderer.DynamicGpuDataStorageNonMapped;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The non-mapped storage variant hosts Chunk Sections Instanced. See
 * {@link DynamicGpuDataStorageMappedMixin} for the rationale.
 */
@Mixin(DynamicGpuDataStorageNonMapped.class)
public abstract class DynamicGpuDataStorageNonMappedMixin {

    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/Mth;smallestEncompassingPowerOfTwo(I)I"
        )
    )
    private int pearloptimizer$initialCapacity(int original) {
        return CapacityBumps.apply("non-mapped", original);
    }
}
