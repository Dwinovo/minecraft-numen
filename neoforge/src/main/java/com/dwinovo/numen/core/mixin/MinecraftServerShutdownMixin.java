package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.fix.NumenRuntimeFixes;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerShutdownMixin {
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void numen$despawnCompanionsBeforeSave(CallbackInfo callback) {
        NumenRuntimeFixes.shutdown((MinecraftServer) (Object) this);
    }
}
