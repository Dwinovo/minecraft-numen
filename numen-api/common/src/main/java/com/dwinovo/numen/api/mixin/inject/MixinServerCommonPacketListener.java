package com.dwinovo.numen.api.mixin.inject;

import com.dwinovo.numen.api.brain.Presence;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.FakeConnection;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.text.ServerLang;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drop every outbound packet aimed at a companion {@link FakeConnection}, at the
 * <em>packet-listener</em> level — one layer above {@link FakeConnection#send} — after
 * offering it to the body's {@link com.dwinovo.numen.api.entity.FakeClient}, which answers the
 * handshakes the server would otherwise wait on forever and hands what the server says to her
 * (system chat and the action bar) to the model.
 *
 * <h2>Why this is needed on top of {@code FakeConnection.send} being a no-op</h2>
 * NeoForge inserts its custom-payload channel validation
 * ({@code NetworkRegistry.checkPacket}) <em>inside</em>
 * {@link ServerCommonPacketListenerImpl#send}, which runs <strong>before</strong> the
 * call reaches {@link Connection#send}. So a companion's no-op {@code Connection.send}
 * never gets the chance to discard a modded clientbound payload — the validation throws
 * first:
 * <pre>UnsupportedOperationException: Payload &lt;mod:foo&gt; may not be sent to the client!</pre>
 * A fake player never negotiated any custom channels (it skips the configuration phase),
 * so the channel set is empty and <em>any</em> mod custom payload trips the check and
 * crashes the server tick. This happens for any mod that pushes a clientbound payload at
 * the body — an item's {@code use()} opening a screen (chiikawa's music box), a menu's
 * per-tick {@code broadcastChanges} (AE2 terminals), etc.
 *
 * <p>Cancelling at {@code HEAD} of the 2-arg {@code send(Packet, PacketSendListener)} short-circuits
 * before its {@code checkPacket}. Every send goes through that overload — the 1-arg {@code send(Packet)}
 * only forwards to it, and callers that want a delivery listener (a system chat message's
 * "not delivered" fallback) call it directly — so it is the one exit, and the only place the fake client
 * sees every packet. It is a strict superset of what {@link FakeConnection#send} already did (drop on the
 * floor — there is no client), so there is no behavioural regression: vanilla position/chunk/keep-alive
 * packets were already discarded.
 *
 * <h2>没装我们客户端的玩家:系统聊天与动作栏在这里翻好</h2>
 * 同一个出口也是发给真玩家的话的最后一站。装着我们的客户端的玩家照旧收带语言键的消息,由他的客户端翻;没装的(原版客户端)
 * 不认识 {@code numen.*} 的键,所以这里按他客户端报上来的语言把 Numen 的键翻成字再放行({@link ServerLang})。
 * 指令的回话、她的话、征询、动作栏都是系统聊天包,在这一处一并解决,发话的地方不必各自管语言。
 *
 * <h2>外面把她断开 = 她的身体被移除</h2>
 * 假连接把断开吞了({@link FakeConnection#disconnect}),所以服主的 {@code /kick}、别的模组踢人在她身上本来什么也不发生。
 * 这里是断开落到她的假连接上的最窄一处:调 {@link Companions#removedFromOutside},再由我们把身体移出玩家列表
 * (原版真玩家是断开后由连接层回调 {@code onDisconnect} 来做这一步,她的假连接不回调)。关服时 {@code removeAll} 也会
 * 对每个玩家断开,那是关服不是外面的人踢她,不走这条。
 *
 * <p>Common (all environments): the integrated server hits this path in singleplayer too,
 * so it must NOT be a {@code server}-only (dedicated) mixin.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class MixinServerCommonPacketListener {

    @Shadow
    @Final
    protected Connection connection;

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"), cancellable = true)
    private void numen$dropOutboundForFakeConnection(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        if (!(this.connection instanceof FakeConnection)) {
            return;
        }
        // 丢之前先给"客户端"看一眼:原版有几处是发完就等对面回话的(传送编号),
        // 回执不来那边就永远悬着;对她说的话(系统聊天与动作栏)也在这里,要交给模型。
        // 这里是玩家连接唯一的下行出口,所以也是唯一该看的地方。
        if ((Object) this instanceof ServerGamePacketListenerImpl game
                && game.getPlayer() instanceof NumenPlayer companion) {
            companion.fakeClient().onOutbound(packet);
        }
        ci.cancel();
    }

    @Shadow
    @Final
    protected MinecraftServer server;

    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
    private void numen$bodyRemovedFromOutside(DisconnectionDetails details, CallbackInfo ci) {
        if (this.connection instanceof FakeConnection && !this.server.isStopped()
                && (Object) this instanceof ServerGamePacketListenerImpl game
                && game.getPlayer() instanceof NumenPlayer companion) {
            Companions.removedFromOutside(companion, details.reason());
            this.server.getPlayerList().remove(companion);
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"), cancellable = true)
    private void numen$translateForClientsWithoutNumen(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        if (this.connection instanceof FakeConnection || !(packet instanceof ClientboundSystemChatPacket chat)
                || !((Object) this instanceof ServerGamePacketListenerImpl game)
                || Presence.hasClient(game.getPlayer().getUUID())) {
            return;
        }
        Component translated = ServerLang.forPlayer(game.getPlayer(), chat.content());
        if (translated != chat.content()) {
            // 翻好的包里没有 Numen 的键了,重进这里时原样放行
            game.send(new ClientboundSystemChatPacket(translated, chat.overlay()), listener);
            ci.cancel();
        }
    }
}
