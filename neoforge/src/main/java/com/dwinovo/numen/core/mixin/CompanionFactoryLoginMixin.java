package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.fix.NumenRuntimeFixes;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CompanionFactory.class)
public abstract class CompanionFactoryLoginMixin {
    @Shadow
    private static void loadPlayerData(MinecraftServer server, NumenPlayer player) {
        throw new AssertionError();
    }

    @Inject(
        method = "spawn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V"
        ),
        require = 1
    )
    private static void numen$prepareBeforeJoin(
        MinecraftServer server,
        UUID companionUuid,
        String name,
        UUID ownerUuid,
        ServerLevel level,
        Vec3 targetPosition,
        CallbackInfoReturnable<NumenPlayer> callback,
        @Local NumenPlayer companion
    ) {
        loadPlayerData(server, companion);
        NumenRuntimeFixes.prepareBeforeJoin(companion, ownerUuid, targetPosition);
    }

    @Redirect(
        method = "spawn",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dwinovo/numen/entity/CompanionFactory;loadPlayerData(Lnet/minecraft/server/MinecraftServer;Lcom/dwinovo/numen/entity/NumenPlayer;)V"
        ),
        require = 1
    )
    private static void numen$skipPostJoinDataLoad(
        MinecraftServer server,
        NumenPlayer companion
    ) {
    }

    @Redirect(
        method = "spawn",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dwinovo/numen/entity/NumenPlayer;teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z"
        ),
        require = 1
    )
    private static boolean numen$skipPostJoinTeleport(
        NumenPlayer companion,
        ServerLevel level,
        double x,
        double y,
        double z,
        Set<Relative> relatives,
        float yRot,
        float xRot,
        boolean resetCamera
    ) {
        return true;
    }
}
