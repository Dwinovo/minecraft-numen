package com.dwinovo.numen.api.platform.services;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Cross-loader networking surface. Wraps Fabric's
 * {@code PayloadTypeRegistry} / {@code ServerPlayNetworking} /
 * {@code ClientPlayNetworking} and NeoForge's
 * {@code RegisterPayloadHandlersEvent} / {@code PacketDistributor} so feature
 * code in {@code common} can declare a payload, its codec, and its handler
 * once and have it work on both loaders.
 *
 * <h2>Payload lifecycle (C→S)</h2>
 * <ol>
 *   <li>Define a record implementing {@link CustomPacketPayload} with a
 *       public {@code Type<T>} and {@code StreamCodec}.</li>
 *   <li>Call {@link #registerClientToServer} once from {@code NumenNetwork.register}.</li>
 *   <li>Client sends via {@code NumenNetwork.sendToServer}, which measures the
 *       payload ({@code Wire}) and then calls {@link #sendToServer}; the handler
 *       runs on the thread its {@link Execution} names.</li>
 * </ol>
 *
 * <h2>Payload lifecycle (S→C)</h2>
 * <ol>
 *   <li>Same payload definition.</li>
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
     * @param type    payload type with a stable identifier
     * @param codec   stream codec for {@code RegistryFriendlyByteBuf}
     * @param handler   invoked for each received payload, on the thread {@code execution} names
     * @param execution the thread the handler runs on
     */
    <T extends CustomPacketPayload> void registerClientToServer(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, ServerPlayer> handler,
            Execution execution);

    /**
     * Send a registered payload from the client to the server. Client-only —
     * calling on the dedicated server throws.
     */
    void sendToServer(CustomPacketPayload payload);

    /**
     * Register a payload the server can send to clients. The {@code handler}
     * is invoked only on the client side — on a dedicated server JVM it is
     * never called, but the payload type still must be registered so the
     * server can serialise outbound packets. Implementations guard the
     * client-side handler hookup behind a side check, so the handler
     * lambda may reference client-only classes safely (they are lazy-loaded).
     *
     * @param type    payload type with a stable identifier
     * @param codec   stream codec for {@code RegistryFriendlyByteBuf}
     * @param handler invoked on the client main thread for each received payload
     */
    <T extends CustomPacketPayload> void registerServerToClient(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            Consumer<T> handler);

    /**
     * Send a registered payload from the server to a specific player.
     * Server-only — call from server-thread code.
     */
    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /**
     * 这位玩家的连接上有没有这种包的频道。我们的频道都是可选的(原版客户端没装我们也进得来),发给没有频道的连接:
     * NeoForge 在发送时就抛异常,Fabric 的客户端收到不认识的包也无从处理——所以发之前先问。
     */
    boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type);
}
