package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.NumenMod;
import com.dwinovo.numen.fix.NumenRuntimeFixes;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(NumenMod.class)
public abstract class NumenModLoginMixin {
    @Redirect(
        method = "onPlayerLoggedIn",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dwinovo/numen/entity/Companions;respawnAllOwnedBy(Lnet/minecraft/server/MinecraftServer;Ljava/util/UUID;)V"
        )
    )
    private static void numen$queueRestoreUntilOwnerReady(
        MinecraftServer server,
        UUID ownerUuid
    ) {
        NumenRuntimeFixes.enqueueOwnerRestore(server, ownerUuid);
    }
}
