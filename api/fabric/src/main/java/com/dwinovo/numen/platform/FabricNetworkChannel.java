package com.dwinovo.numen.platform;

import com.dwinovo.numen.platform.services.INetworkChannel;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Fabric implementation of {@link INetworkChannel}. Both C→S and S→C use
 * Fabric's {@code PayloadTypeRegistry}; the handler registration is split
 * across {@code ServerPlayNetworking} (server-side, available everywhere)
 * and {@code ClientPlayNetworking} (client-side only, lazy-loaded via a
 * side check).
 *
 * <h2>Lazy client-class loading</h2>
 * {@code ClientPlayNetworking} is a {@code @ClientOnly}-marked Fabric class.
 * Referencing it from common code on a dedicated server would throw at
 * class-load time. We guard the reference behind a {@link FabricLoader}
 * environment check and put the actual call in a separate static method,
 * so the JVM only loads {@code ClientPlayNetworking} when the runtime is
 * a client.
 */
public final class FabricNetworkChannel implements INetworkChannel {

    @Override
    public <T extends CustomPacketPayload> void registerClientToServer(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, ServerPlayer> handler) {
        PayloadTypeRegistry.serverboundPlay().register(type, codec);
        ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) -> {
            MinecraftServer server = context.server();
            ServerPlayer player = context.player();
            server.execute(() -> handler.accept(payload, player));
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
            com.dwinovo.numen.Constants.LOG.warn("[numen] 无连接,丢弃 C2S 包 {}", payload.type().id());
            return;
        }
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(payload);
    }

    @Override
    public <T extends CustomPacketPayload> void registerServerToClient(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            Consumer<T> handler) {
        PayloadTypeRegistry.clientboundPlay().register(type, codec);
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            registerClientReceiverImpl(type, handler);
        }
    }

    /** Isolated for lazy class-load — runs only on a client environment. */
    private static <T extends CustomPacketPayload> void registerClientReceiverImpl(
            CustomPacketPayload.Type<T> type, Consumer<T> handler) {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                type, (payload, context) -> {
                    var client = context.client();
                    client.execute(() -> handler.accept(payload));
                });
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }
}
