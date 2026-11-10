package dev.zai.pearloptimizer.mixin;

import dev.zai.pearloptimizer.Diagnostics;
import dev.zai.pearloptimizer.ShaderCacheManager;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every resource reload re-reads all shader include files from the resource
 * packs. Resetting the include fingerprint at the start of
 * {@code ShaderManager.reload} guarantees the fingerprint always describes
 * exactly the include set of the most recent reload, before any pipeline of
 * the new reload can be compiled (compiles happen after the prepare barrier).
 *
 * <p>Since the diagnostics build (module b100be3) the same HEAD point also
 * dumps the finished compile phase of {@code generateBackendCreateInfo}
 * (reflection timing) before the next phase starts; the handler uses
 * {@code require = 0} and swallows throwables inside {@link Diagnostics}.</p>
 */
@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {

    // ShaderManager.reload returns CompletableFuture<Void>, so the handler must
    // take a CallbackInfoReturnable, not a plain CallbackInfo.
    //
    // The two former separate @At("HEAD") injects (include
    // fingerprint reset + diagnostics reload-boundary dump) are merged into
    // this one handler: same order (fingerprint reset first, diagnostics
    // second), one injection point. Diagnostics swallows its own throwables.
    @Inject(method = "reload", at = @At("HEAD"))
    private void pearloptimizer$onShaderReload(CallbackInfoReturnable<Void> cir) {
        ShaderCacheManager.beginShaderReload();
        Diagnostics.onReloadStart();
    }
}
