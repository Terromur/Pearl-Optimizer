package dev.zai.pearloptimizer.mixin;

import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the two private, device-dependent flags of {@link GlslCompiler}
 * that change shader compilation output. They are part of the shader cache
 * key: a driver that needs zero-to-one depth compiles different shaders than
 * one that does not, and reusing SPIR-V across that boundary would be wrong.
 */
@Mixin(GlslCompiler.class)
public interface GlslCompilerAccessor {
    @Accessor("isZeroToOne")
    boolean pearl$isZeroToOne();

    @Accessor("shaderDrawParameters")
    boolean pearl$shaderDrawParameters();
}
