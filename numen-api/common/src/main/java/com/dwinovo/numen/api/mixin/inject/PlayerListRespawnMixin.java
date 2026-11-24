package com.dwinovo.numen.api.mixin.inject;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 原版重生({@code PlayerList.respawn})要造一个新的 {@code ServerPlayer} 顶替死了的那个;死的是她,造出来的也得是 {@link NumenPlayer}。
 * 她的重生由 {@code FakeClient#respawn} 代发 {@code PERFORM_RESPAWN} 触发,后面的一切(重生点规则、掉落与经验、发包)都是原版的。
 *
 * <p>只管她自己的重生,而且是包住这一处构造、不独占它:别的模组也在这里换自己的身体(Carpet 的假人用的是 {@code @Redirect},
 * 一处只容得下一个),死的不是她就原样交回去,由它们或原版来造。
 */
@Mixin(PlayerList.class)
public abstract class PlayerListRespawnMixin {

    @WrapOperation(method = "respawn", at = @At(value = "NEW",
            target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;"
                    + "Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)"
                    + "Lnet/minecraft/server/level/ServerPlayer;"))
    private ServerPlayer numen$respawnedBody(MinecraftServer server, ServerLevel level, GameProfile profile,
                                             ClientInformation information, Operation<ServerPlayer> original,
                                             @Local(argsOnly = true) ServerPlayer died) {
        return died instanceof NumenPlayer ? new NumenPlayer(server, level, profile, information)
                : original.call(server, level, profile, information);
    }
}
