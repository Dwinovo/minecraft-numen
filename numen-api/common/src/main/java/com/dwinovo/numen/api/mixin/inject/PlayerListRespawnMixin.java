package com.dwinovo.numen.api.mixin.inject;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 原版重生({@code PlayerList.respawn})要造一个新的 {@code ServerPlayer} 顶替死了的那个;死的是她,造出来的也得是 {@link NumenPlayer}。
 * 她的重生由 {@code FakeClient#respawn} 代发 {@code PERFORM_RESPAWN} 触发,后面的一切(重生点规则、掉落与经验、发包)都是原版的。
 *
 * <p>只换这一处构造。Paper 的重生沿用原来的对象,不造新的,所以那边不需要这个挂点。
 */
@Mixin(PlayerList.class)
public abstract class PlayerListRespawnMixin {

    @Redirect(method = "respawn", at = @At(value = "NEW",
            target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;"
                    + "Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)"
                    + "Lnet/minecraft/server/level/ServerPlayer;"))
    private ServerPlayer numen$respawnedBody(MinecraftServer server, ServerLevel level, GameProfile profile,
                                             ClientInformation information, ServerPlayer died, boolean keepInventory,
                                             Entity.RemovalReason reason) {
        return died instanceof NumenPlayer ? new NumenPlayer(server, level, profile, information)
                : new ServerPlayer(server, level, profile, information);
    }
}
