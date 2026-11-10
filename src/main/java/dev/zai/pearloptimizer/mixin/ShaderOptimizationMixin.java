package dev.zai.pearloptimizer.mixin;

import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import dev.zai.pearloptimizer.ShaderOptimization;
import org.lwjgl.util.shaderc.Shaderc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Vanilla 26.3 feeds shaderc with {@code shaderc_optimization_level_zero}
 * (no SPIR-V optimization) plus debug info, both set in the private
 * {@code createBaseShaderOptions} method. This module raises the level to
 * {@code PERFORMANCE} (2) - but <b>only</b> after the runtime probe in
 * {@link ShaderOptimization} verified that SPIR-V debug names survive.
 *
 * <p>Debug info itself is intentionally left vanilla: the game reads the
 * {@code OpName} debug instructions back via spvc reflection to build
 * {@code vertexShaderInputsByName}; without them every interface variable
 * is nameless and pipeline compilation crashes with
 * {@code IllegalStateException: Duplicate key ""} in {@code Collectors.toMap}
 * (disabling debug info does exactly this).</p>
 *
 * <p>Disable entirely with {@code shaderOptimization=false} (or
 * {@code -Dpearloptimizer.shadopt=false}) to keep the exact vanilla bytes.</p>
 */
@Mixin(GlslCompiler.class)
public abstract class ShaderOptimizationMixin {

    @ModifyArg(
        method = "createBaseShaderOptions",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/util/shaderc/Shaderc;shaderc_compile_options_set_optimization_level(JI)V"
        ),
        index = 1
    )
    private int pearloptimizer$optimizationLevel(int vanillaLevel) {
        if (ShaderOptimization.isActive()) {
            return Shaderc.shaderc_optimization_level_performance;
        }
        return vanillaLevel;
    }
}
