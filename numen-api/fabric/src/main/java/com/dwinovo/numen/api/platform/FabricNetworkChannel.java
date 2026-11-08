package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.INetworkChannel;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Fabric implementation of {@link INetworkChannel} for MC 1.20.2. Uses the
 * raw {@code ResourceLocation}+{@code FriendlyByteBuf} networking API
 * ({@code ServerPlayNetworking} / {@code ClientPlayNetworking}) — the
 * {@code CustomPacketPayload}-typed {@code PayloadTypeRegistry} is a 1.20.5+
 * addition. The payload serialises itself through {@code CustomPacketPayload.write}
 * and is rebuilt by the per-registration {@code decoder}.
 *
 * <h2>Lazy client-class loading</h2>
 * {@code ClientPlayNetworking} is a client-only Fabric class. We guard the
 * reference behind a {@link FabricLoader} environment check and isolate the
 * actual call in a separate static method, so the JVM only loads it on a client.
 *
 * <h2>Threading</h2>
 * This API calls receivers on the network thread. The buffer is decoded there (before Netty frees it); downlink
 * handlers and uplink handlers registered {@link Execution#MAIN} are then re-scheduled onto the receiving side's main
 * thread, uplink handlers registered {@link Execution#NETWORK} run right there.
 */
public final class FabricNetworkChannel implements INetworkChannel {

    @Override
    public <T extends CustomPacketPayload> void registerClientToServer(
            ResourceLocation id,
            Function<FriendlyByteBuf, T> decoder,
            BiConsumer<T, ServerPlayer> handler,
            Execution execution) {
        ServerPlayNetworking.registerGlobalReceiver(id, (server, player, listener, buf, responseSender) -> {
            T payload = decoder.apply(buf);
            if (execution == Execution.NETWORK || server.isSameThread()) {
                handler.accept(payload, player);
            } else {
                server.execute(() -> handler.accept(payload, player));
            }
        });
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        // Lazy class-load: ClientPlayNetworking is client-only.
        // Server JVM never reaches this call site.
        // 没连接就发不出去，而发包往往夹在一串清理中间（登出钩子），一报错就把
        // 后面的清理全截断了。警告并丢包：丢一个本来就发不出去的包无害，截断清理有害。
        // 不静默：真有人在错的时机发包，日志里看得到。
        if (net.minecraft.client.Minecraft.getInstance().getConnection() == null) {
            com.dwinovo.numen.api.Constants.LOG.warn("[numen] 无连接,丢弃 C2S 包 {}", payload.id());
            return;
        }
        FriendlyByteBuf buf = PacketByteBufs.create();
        payload.write(buf);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(payload.id(), buf);
    }

    @Override
    public <T extends CustomPacketPayload> void registerServerToClient(
            ResourceLocation id,
            Function<FriendlyByteBuf, T> decoder,
            Consumer<T> handler) {
        // Only the receiving (client) side needs a hookup; the server just sends by channel id.
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            registerClientReceiverImpl(id, decoder, handler);
        }
    }

    /** Isolated for lazy class-load — runs only on a client environment. */
    private static <T extends CustomPacketPayload> void registerClientReceiverImpl(
            ResourceLocation id, Function<FriendlyByteBuf, T> decoder, Consumer<T> handler) {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                id, (client, listener, buf, responseSender) -> {
                    T payload = decoder.apply(buf);
                    client.execute(() -> handler.accept(payload));
                });
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        payload.write(buf);
        ServerPlayNetworking.send(player, payload.id(), buf);
    }

    @Override
    public boolean canSend(ServerPlayer player, ResourceLocation id) {
        return ServerPlayNetworking.canSend(player, id);
    }
}
