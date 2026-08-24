package com.dwinovo.numen.platform.services;

import net.minecraft.network.FriendlyByteBuf;
import com.dwinovo.numen.network.NumenPayload;
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
 * <h2>Payload definition (MC 1.20.4)</h2>
 * A payload is a record implementing {@link NumenPayload} — the 1.20.4
 * shape: a {@code void write(FriendlyByteBuf)} that serialises the record and a
 * {@code ResourceLocation id()} identifying the channel. Its reverse (a static
 * {@code read(FriendlyByteBuf)}) is passed to {@code register*} as the decoder.
 * (This predates the 1.20.5 {@code StreamCodec} / {@code NumenPayload.Type}
 * machinery, so we hand-roll {@code write}/{@code read} per payload.)
 *
 * <h2>Payload lifecycle (C→S)</h2>
 * <ol>
 *   <li>Call {@link #registerClientToServer} once from {@code NumenNetwork.register}.</li>
 *   <li>Client sends via {@link #sendToServer}; handler runs on the server
 *       main thread.</li>
 * </ol>
 *
 * <h2>Payload lifecycle (S→C)</h2>
 * <ol>
 *   <li>Call {@link #registerServerToClient} once from {@code NumenNetwork.register}.</li>
 *   <li>Server sends via {@link #sendToPlayer}; handler runs on the client
 *       main thread.</li>
 * </ol>
 *
 * <h2>Threading guarantee</h2>
 * Handlers (both directions) are dispatched on the receiving side's main
 * thread. Common code doesn't need to schedule.
 */
public interface INetworkChannel {

    /**
     * Register a payload the client can send to the server.
     *
     * @param id      channel identifier (the payload's {@code id()})
     * @param decoder reconstructs the payload from the received buffer
     * @param handler invoked on the server main thread for each received payload
     */
    <T extends NumenPayload> void registerClientToServer(
            ResourceLocation id,
            Function<FriendlyByteBuf, T> decoder,
            BiConsumer<T, ServerPlayer> handler);

    /**
     * Send a registered payload from the client to the server. Client-only —
     * calling on the dedicated server throws.
     */
    void sendToServer(NumenPayload payload);

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
    <T extends NumenPayload> void registerServerToClient(
            ResourceLocation id,
            Function<FriendlyByteBuf, T> decoder,
            Consumer<T> handler);

    /**
     * Send a registered payload from the server to a specific player.
     * Server-only — call from server-thread code.
     */
    void sendToPlayer(ServerPlayer player, NumenPayload payload);
}
