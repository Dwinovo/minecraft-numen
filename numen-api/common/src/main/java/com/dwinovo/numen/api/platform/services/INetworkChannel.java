package com.dwinovo.numen.api.platform.services;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Cross-loader networking surface. Wraps Fabric's
 * {@code ServerPlayNetworking} / {@code ClientPlayNetworking} and Forge's
 * {@code SimpleChannel} so feature code in {@code common} can declare a payload,
 * its decoder, and its handler once and have it work on both loaders.
 *
 * <h2>Payload definition (MC 1.20.2)</h2>
 * A payload is a record implementing {@link CustomPacketPayload}: a {@code void write(FriendlyByteBuf)} that serialises the
 * record and a {@code ResourceLocation id()} identifying the channel. Its reverse (a static
 * {@code read(FriendlyByteBuf)}) is passed to {@code register*} as the decoder.
 * (1.20.2 predates the 1.20.5 {@code StreamCodec} machinery and payload types, so each payload hand-rolls
 * {@code write}/{@code read}.)
 *
 * <h2>Payload lifecycle (C→S)</h2>
 * <ol>
 *   <li>Call {@link #registerClientToServer} once from {@code NumenNetwork.register}.</li>
 *   <li>Client sends via {@code NumenNetwork.sendToServer}, which measures the
 *       payload ({@code Wire}) and then calls {@link #sendToServer}; the handler
 *       runs on the thread its {@link Execution} names.</li>
 * </ol>
 *
 * <h2>Payload lifecycle (S→C)</h2>
 * <ol>
 *   <li>Call {@link #registerServerToClient} once from {@code NumenNetwork.register}.</li>
 *   <li>Server sends via {@code NumenNetwork.sendToPlayer}, which measures the
 *       payload ({@code Wire}) and then calls {@link #sendToPlayer}; handler
 *       runs on the client main thread.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * Downlink handlers are dispatched on the client main thread. Uplink handlers name their {@link Execution}:
 * {@link Execution#MAIN} is the server main thread, so common code doesn't need to schedule;
 * {@link Execution#NETWORK} is for handlers that are cheap and thread-safe (reassembling fragments, putting a
 * message in a mailbox) and should not wait for the server tick.
 *
 * <h2>Why a single combined interface</h2>
 * Versus separate {@code IClientChannel} / {@code IServerChannel}: feature
 * code in common often needs to register both directions for one feature
 * (request + reply). Single channel lets {@code NumenNetwork.register}
 * stay a flat list of registrations.
 */
public interface INetworkChannel {

    /**
     * Which thread an uplink handler runs on. {@code NETWORK} asks for the connection's own thread; a loader whose
     * networking API always hands the handler to the server thread (Fabric's does) honours it by calling the handler
     * there, so a {@code NETWORK} handler must be correct on either thread.
     */
    enum Execution { NETWORK, MAIN }

    /**
     * Register a payload the client can send to the server. Idempotent per
     * type id — duplicate registrations are loader-defined error behaviour.
     *
     * @param id        channel identifier (the payload's {@code id()})
     * @param decoder   reconstructs the payload from the received buffer
     * @param handler   invoked for each received payload, on the thread {@code execution} names
     * @param execution the thread the handler runs on
     */
    <T extends CustomPacketPayload> void registerClientToServer(
            ResourceLocation id,
            Function<FriendlyByteBuf, T> decoder,
            BiConsumer<T, ServerPlayer> handler,
            Execution execution);

    /**
     * Send a registered payload from the client to the server. Client-only —
     * calling on the dedicated server throws.
     */
    void sendToServer(CustomPacketPayload payload);

    /**
     * Register a payload the server can send to clients. The {@code handler}
     * is invoked only on the client side. Implementations guard the
     * client-side handler hookup behind a side check, so the handler
     * lambda may reference client-only classes safely (they are lazy-loaded).
     *
     * @param id      channel identifier (the payload's {@code id()})
     * @param decoder reconstructs the payload from the received buffer
     * @param handler invoked on the client main thread for each received payload
     */
    <T extends CustomPacketPayload> void registerServerToClient(
            ResourceLocation id,
            Function<FriendlyByteBuf, T> decoder,
            Consumer<T> handler);

    /**
     * Send a registered payload from the server to a specific player.
     * Server-only — call from server-thread code.
     */
    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /**
     * 这位玩家的连接上有没有这种包的频道。我们的频道都是可选的(原版客户端没装我们也进得来),发给没有频道的连接:
     * 对端不认识的包无从处理——所以发之前先问。
     */
    boolean canSend(ServerPlayer player, ResourceLocation id);
}
