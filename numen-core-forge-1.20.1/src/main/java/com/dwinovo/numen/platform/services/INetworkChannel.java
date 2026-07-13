package com.dwinovo.numen.platform.services;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Cross-loader networking surface for Forge 1.20.1. Feature code in
 * {@code common} declares a payload with manual {@code encode(FriendlyByteBuf)}
 * / static {@code decode(FriendlyByteBuf)} plus a handler, and registers once
 * via {@code NumenNetwork.register()}.
 *
 * <h2>Payload lifecycle (C-&gt;S)</h2>
 * <ol>
 *   <li>Define a plain record/class with a static {@code ResourceLocation ID},
 *       instance {@code encode(FriendlyByteBuf)}, static
 *       {@code decode(FriendlyByteBuf)}, and static
 *       {@code handle(Msg, ServerPlayer)}.</li>
 *   <li>Call {@link #registerClientToServer} once from {@code NumenNetwork.register}.</li>
 *   <li>Client sends via {@link #sendToServer}; handler runs on the server
 *       main thread.</li>
 * </ol>
 *
 * <h2>Payload lifecycle (S-&gt;C)</h2>
 * <ol>
 *   <li>Same payload definition, with static {@code handle(Msg)} (no player).</li>
 *   <li>Call {@link #registerServerToClient} once from {@code NumenNetwork.register}.</li>
 *   <li>Server sends via {@link #sendToPlayer}; handler runs on the client
 *       main thread.</li>
 * </ol>
 *
 * <h2>Threading guarantee</h2>
 * Handlers (both directions) are dispatched on the receiving side's main
 * thread. Common code does not need to schedule.
 */
public interface INetworkChannel {

    /**
     * Register a payload the client can send to the server.
     *
     * @param id       stable ResourceLocation identifier for the packet
     * @param type     the message class (used by the underlying channel for
     *                 type-based routing)
     * @param encoder  writes the message fields into a {@code FriendlyByteBuf}
     * @param decoder  reads the message fields from a {@code FriendlyByteBuf}
     * @param handler  invoked on the server main thread with the deserialized
     *                 message and the sender {@link ServerPlayer}
     */
    <MSG> void registerClientToServer(
            ResourceLocation id,
            Class<MSG> type,
            BiConsumer<MSG, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, MSG> decoder,
            BiConsumer<MSG, ServerPlayer> handler);

    /**
     * Send a registered payload from the client to the server. Client-only --
     * calling on the dedicated server throws.
     */
    void sendToServer(ResourceLocation id, Object payload);

    /**
     * Register a payload the server can send to clients. The {@code handler}
     * is invoked only on the client side -- on a dedicated server JVM it is
     * never called, but the payload type still must be registered so the
     * server can serialize outbound packets.
     *
     * @param id       stable ResourceLocation identifier for the packet
     * @param type     the message class
     * @param encoder  writes the message fields into a {@code FriendlyByteBuf}
     * @param decoder  reads the message fields from a {@code FriendlyByteBuf}
     * @param handler  invoked on the client main thread for each received payload
     */
    <MSG> void registerServerToClient(
            ResourceLocation id,
            Class<MSG> type,
            BiConsumer<MSG, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, MSG> decoder,
            Consumer<MSG> handler);

    /**
     * Send a registered payload from the server to a specific player.
     * Server-only -- call from server-thread code.
     */
    void sendToPlayer(ServerPlayer player, ResourceLocation id, Object payload);
}
