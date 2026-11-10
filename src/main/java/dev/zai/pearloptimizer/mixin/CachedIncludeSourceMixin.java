package dev.zai.pearloptimizer.mixin;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import dev.zai.pearloptimizer.ShaderCacheManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code CachedIncludeSource.create} is the factory the game calls while
 * loading every shader include file during a resource reload - with the
 * plain include content as a {@code String}. Hashing it here gives the
 * shader cache a content-based fingerprint of all includes without touching
 * native memory or duplicating the include resolution logic.
 *
 * <p>If any include file changes (resource pack edit, mod update, pack
 * switch), the fingerprint changes and every affected cache entry misses -
 * exactly the safe direction. Error includes are produced by a separate
 * {@code createError} factory and are not tracked.</p>
 */
@Mixin(ShaderSource.CachedIncludeSource.class)
public abstract class CachedIncludeSourceMixin {

    @Inject(method = "create", at = @At("HEAD"))
    private static void pearloptimizer$trackInclude(Identifier id, String content, CallbackInfoReturnable<?> cir) {
        ShaderCacheManager.recordInclude(id, content);
    }
}
