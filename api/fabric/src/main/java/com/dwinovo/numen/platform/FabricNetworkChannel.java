package com.dwinovo.numen.platform;

import com.dwinovo.numen.platform.services.INetworkChannel;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;
import com.dwinovo.numen.network.NumenPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Fabric implementation of {@link INetworkChannel} for MC 1.20.4. Uses the
 * raw {@code ResourceLocation}+{@code FriendlyByteBuf} networking API
 * ({@code ServerPlayNetworking} / {@code ClientPlayNetworking}) — the
 * {@code NumenPayload}-typed {@code PayloadTypeRegistry} is a 1.20.5+
 * addition. The payload serialises itself through {@code NumenPayload.write}
 * and is rebuilt by the per-registration {@code decoder}.
 *
 * <h2>Lazy client-class loading</h2>
 * {@code ClientPlayNetworking} is a client-only Fabric class. We guard the
 * reference behind a {@link FabricLoader} environment check and isolate the
 * actual call in a separate static method, so the JVM only loads it on a client.
 *
 * <h2>Threading</h2>
 * The buffer is decoded on the network thread (before Netty frees it), then the
 * handler is re-scheduled onto the receiving side's main thread.
 */
public final class FabricNetworkChannel implements INetworkChannel {

    @Override
    public <T extends NumenPayload> void registerClientToServer(
            ResourceLocation id,
            Function<FriendlyByteBuf, T> decoder,
            BiConsumer<T, ServerPlayer> handler) {
        ServerPlayNetworking.registerGlobalReceiver(id, (server, player, listener, buf, responseSender) -> {
            T payload = decoder.apply(buf);
            server.execute(() -> handler.accept(payload, player));
        });
    }

    @Override
    public void sendToServer(NumenPayload payload) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        payload.write(buf);
        // Lazy class-load: ClientPlayNetworking is client-only. Server JVM never reaches this.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(payload.id(), buf);
    }

    @Override
    public <T extends NumenPayload> void registerServerToClient(
            ResourceLocation id,
            Function<FriendlyByteBuf, T> decoder,
            Consumer<T> handler) {
        // Only the receiving (client) side needs a hookup; the server just sends by channel id.
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            registerClientReceiverImpl(id, decoder, handler);
        }
    }

    /** Isolated for lazy class-load — runs only on a client environment. */
    private static <T extends NumenPayload> void registerClientReceiverImpl(
            ResourceLocation id, Function<FriendlyByteBuf, T> decoder, Consumer<T> handler) {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                id, (client, listener, buf, responseSender) -> {
                    T payload = decoder.apply(buf);
                    client.execute(() -> handler.accept(payload));
                });
    }

    @Override
    public void sendToPlayer(ServerPlayer player, NumenPayload payload) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        payload.write(buf);
        ServerPlayNetworking.send(player, payload.id(), buf);
    }
}
