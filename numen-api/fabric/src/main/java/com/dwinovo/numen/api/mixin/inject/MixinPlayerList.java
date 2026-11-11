package com.dwinovo.numen.api.mixin.inject;

import com.dwinovo.numen.api.platform.PlayerSessions;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 玩家登入——<b>Fabric 专属</b>。{@link PlayerSessions#joined} 要的是"他已经在玩家表里、在世界里"的那一刻:
 * NeoForge 的 {@code PlayerLoggedInEvent}、Paper 的 {@code PlayerJoinEvent} 都发在原版 {@code placeNewPlayer}
 * 把玩家登记完之后。Fabric 的 {@code ServerPlayConnectionEvents.JOIN} 发在登记之前(发能力包那一句),那一刻开场发不出去,
 * 所以这里挂在 {@code placeNewPlayer} 的末尾,与另两个平台同一个时机。
 */
@Mixin(PlayerList.class)
public class MixinPlayerList {

    @Inject(method = "placeNewPlayer", at = @At("RETURN"))
    private void numen$joined(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        PlayerSessions.joined(player);
    }
}
