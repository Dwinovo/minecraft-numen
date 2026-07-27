package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.fix.NumenRuntimeFixes;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Companions.class)
public abstract class CompanionsRespawnMixin {
    @Inject(method = "respawnDead", at = @At("HEAD"), cancellable = true)
    private static void numen$waitForOwnerChunks(
        MinecraftServer server,
        UUID companionUuid,
        CompanionRegistry.Entry entry,
        ServerPlayer owner,
        CallbackInfoReturnable<Boolean> callback
    ) {
        if (!NumenRuntimeFixes.isOwnerReady(owner)) {
            callback.setReturnValue(false);
        }
    }
}
