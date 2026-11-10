package dev.zai.pearloptimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import dev.zai.pearloptimizer.Diagnostics;
import dev.zai.pearloptimizer.ShaderCacheManager;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.client.renderer.ShaderDefines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla 26.3 compiles every shader from GLSL to SPIR-V with shaderc on
 * every launch. This is the single {@code GlslCompiler.compileToSpv} call
 * site in the whole game (inside {@code PipelineBuilder.generateBackendCreateInfo}).
 *
 * <p>The wrap consults the persistent {@link ShaderCacheManager} first: on a
 * cache hit the shader is reconstructed from the stored SPIR-V binary without
 * running shaderc at all; on a miss the vanilla compile runs and the result
 * is stored for the next launch. Every failure falls back to the original
 * compile inside the manager, so a broken cache can never break rendering.</p>
 *
 * <p>Since the diagnostics build (module b100be3) the same mixin also times
 * the phases of {@code generateBackendCreateInfo} (recon: private instance
 * method, return type {@code BackendRenderPipeline$CreateInfo}) and
 * {@code generateSlotMap} (recon: private static, return type
 * {@code Int2ObjectMap<PipelineBuilder$InterfaceVariableInfo>}, the spvc
 * reflection consumer). Both targets are non-void, therefore EVERY handler -
 * HEAD and RETURN alike - must take a wildcard {@code CallbackInfoReturnable<?>}:
 * Mixin validates the handler's callback type against the target's return type
 * at apply time and a plain {@code CallbackInfo} on a non-void target is a hard
 * apply error ({@code InvalidInjectionException: CallbackInfoReturnable is
 * required}) that {@code require = 0} does NOT soften - it only forgives a
 * missing target. HEAD/RETURN injects bracket each call; the existing wrap
 * reports its own (cache/compile) part, and the RETURN hook computes
 * residual = total - ours - the game-side reflection and assembly cost. All
 * four injects keep {@code require = 0}: if a target ever moves, Mixin logs a
 * warning and the game continues without timing statistics.</p>
 */
@Mixin(PipelineBuilder.class)
public abstract class PipelineBuilderMixin {

    @WrapOperation(
        method = "generateBackendCreateInfo",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/frontend/shaders/GlslCompiler;compileToSpv(Ljava/lang/String;Ljava/lang/String;Lcom/mojang/renderpearl/api/pipeline/ShaderType;Lnet/minecraft/client/renderer/ShaderDefines;Lcom/mojang/renderpearl/api/pipeline/ShaderSource;)Lcom/mojang/renderpearl/backend/api/SpvModule;"
        )
    )
    private SpvModule pearloptimizer$cachedCompileToSpv(
        // Parameter names follow the real vanilla compileToSpv order:
        // argument 2 is the shader name, argument 3 is the GLSL source. Mixin
        // binds handler parameters by position, so these names cannot change
        // behavior; the positional PearlBench contract is likewise type-based,
        // not name-based.
        GlslCompiler compiler,
        String shaderName,
        String glsl,
        ShaderType type,
        ShaderDefines defines,
        ShaderSource source,
        Operation<SpvModule> operation
    ) {
        long oursStart = Diagnostics.onCreateInfoOursBegin();
        SpvModule module = ShaderCacheManager.loadOrCompile(compiler, shaderName, glsl, type, defines, source, operation);
        Diagnostics.onCreateInfoOursEnd(oursStart);
        return module;
    }

    @Inject(method = "generateBackendCreateInfo", at = @At("HEAD"), require = 0)
    private void pearloptimizer$diagCreateInfoBegin(CallbackInfoReturnable<?> cir) {
        Diagnostics.onCreateInfoBegin();
    }

    @Inject(method = "generateBackendCreateInfo", at = @At("RETURN"), require = 0)
    private void pearloptimizer$diagCreateInfoEnd(CallbackInfoReturnable<?> cir) {
        Diagnostics.onCreateInfoEnd();
    }

    @Inject(method = "generateSlotMap", at = @At("HEAD"), require = 0)
    private static void pearloptimizer$diagSlotMapBegin(CallbackInfoReturnable<?> cir) {
        Diagnostics.onSlotMapBegin();
    }

    @Inject(method = "generateSlotMap", at = @At("RETURN"), require = 0)
    private static void pearloptimizer$diagSlotMapEnd(CallbackInfoReturnable<Int2ObjectMap<?>> cir) {
        Diagnostics.onSlotMapEnd();
    }
}
