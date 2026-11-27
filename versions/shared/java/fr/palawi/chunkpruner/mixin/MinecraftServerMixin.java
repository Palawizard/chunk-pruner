package fr.palawi.chunkpruner.mixin;

import fr.palawi.chunkpruner.mc.StartupHook;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * E1: runs before the server creates its levels. {@code loadLevel} is where dedicated and
 * integrated servers build their levels, after the world storage and its session lock are open and
 * before any chunk is read (same method on 1.20.1, 1.21.1 and 26.3).
 */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
    @Inject(method = "loadLevel", at = @At("HEAD"))
    private void chunkpruner$beforeLevels(CallbackInfo ci) {
        StartupHook.beforeLevels((MinecraftServer) (Object) this);
    }
}
