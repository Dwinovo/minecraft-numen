package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.fix.NumenRuntimeFixes;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompanionTickDispatcher.class)
public abstract class CompanionTickDispatcherMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private static void numen$restoreAfterOwnerReady(
        MinecraftServer server,
        CallbackInfo callback
    ) {
        NumenRuntimeFixes.tick(server);
    }
}
