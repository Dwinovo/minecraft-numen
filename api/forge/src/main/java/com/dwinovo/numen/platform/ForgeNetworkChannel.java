package com.dwinovo.numen.platform;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.platform.services.INetworkChannel;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Forge 1.20.2 implementation of {@link INetworkChannel}.
 *
 * <h2>Why an envelope instead of one Forge message per payload</h2>
 * Forge 1.20.2 routes on a {@link SimpleChannel} keyed by the message's runtime
 * {@code Class}. The cross-loader interface registers payloads by
 * {@code ResourceLocation}, so this channel registers a <em>single</em> Forge
 * message — an {@link Envelope} carrying {@code (payload id, serialised bytes)} —
 * and multiplexes every payload through it, looking the decoder/handler up by id
 * on both the send and receive sides.
 *
 * <p>Registration is eager (Forge's {@code SimpleChannel} accepts message
 * registration during construction), so there is no deferred "flush on an event"
 * step like NeoForge needs.
 *
 * <h2>Threading</h2>
 * {@code consumerMainThread} hands the envelope to the consumer on the receiving
 * side's main thread (server-main for C→S, client-main for S→C), so common
 * handlers don't need to reschedule — matching the interface contract.
 */
public final class ForgeNetworkChannel implements INetworkChannel {

    private static final int PROTOCOL_VERSION = 1;

    private static final SimpleChannel CHANNEL = ChannelBuilder
            .named(new ResourceLocation(Constants.MOD_ID, "main"))
            .networkProtocolVersion(PROTOCOL_VERSION)
            .acceptedVersions((status, version) -> true)
            .simpleChannel();

    /** A single opaque message multiplexing every payload: id + serialised bytes. */
    private record Envelope(ResourceLocation id, byte[] data) {}

    private record C2S<T extends CustomPacketPayload>(
            Function<FriendlyByteBuf, T> decoder,
            BiConsumer<T, ServerPlayer> handler) {}

    private record S2C<T extends CustomPacketPayload>(
            Function<FriendlyByteBuf, T> decoder,
            Consumer<T> handler) {}

    private final Map<ResourceLocation, C2S<?>> c2s = new HashMap<>();
    private final Map<ResourceLocation, S2C<?>> s2c = new HashMap<>();

    /** Default constructor used by {@code ServiceLoader}; wires the single envelope message. */
    public ForgeNetworkChannel() {
        CHANNEL.messageBuilder(Envelope.class, 0)
                .encoder(ForgeNetworkChannel::encodeEnvelope)
                .decoder(ForgeNetworkChannel::decodeEnvelope)
                .consumerMainThread((env, ctx) -> {
                    ServerPlayer sender = ctx.getSender();
                    if (sender != null) {
                        receiveC2S(env, sender);   // a C→S packet arrived on the server
                    } else {
                        receiveS2C(env);           // an S→C packet arrived on the client
                    }
                    ctx.setPacketHandled(true);
                })
                .add();
    }

    // ---- registration ----

    @Override
    public <T extends CustomPacketPayload> void registerClientToServer(
            ResourceLocation id,
            Function<FriendlyByteBuf, T> decoder,
            BiConsumer<T, ServerPlayer> handler) {
        c2s.put(id, new C2S<>(decoder, handler));
    }

    @Override
    public <T extends CustomPacketPayload> void registerServerToClient(
            ResourceLocation id,
            Function<FriendlyByteBuf, T> decoder,
            Consumer<T> handler) {
        s2c.put(id, new S2C<>(decoder, handler));
    }

    // ---- sending ----

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        CHANNEL.send(new Envelope(payload.id(), serialise(payload)), PacketDistributor.SERVER.noArg());
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        CHANNEL.send(new Envelope(payload.id(), serialise(payload)), PacketDistributor.PLAYER.with(player));
    }

    // ---- receiving (already on the main thread) ----

    private void receiveC2S(Envelope env, ServerPlayer sender) {
        C2S<?> reg = c2s.get(env.id());
        if (reg == null) {
            Constants.LOG.warn("[numen-net] dropped unknown C→S payload {}", env.id());
            return;
        }
        dispatchC2S(reg, env.data(), sender);
    }

    private void receiveS2C(Envelope env) {
        S2C<?> reg = s2c.get(env.id());
        if (reg == null) {
            Constants.LOG.warn("[numen-net] dropped unknown S→C payload {}", env.id());
            return;
        }
        dispatchS2C(reg, env.data());
    }

    // Wildcard-capture helpers: decoder.apply(buf) yields exactly the captured payload type the handler wants.
    private static <T extends CustomPacketPayload> void dispatchC2S(C2S<T> reg, byte[] data, ServerPlayer sender) {
        reg.handler().accept(reg.decoder().apply(reader(data)), sender);
    }

    private static <T extends CustomPacketPayload> void dispatchS2C(S2C<T> reg, byte[] data) {
        reg.handler().accept(reg.decoder().apply(reader(data)));
    }

    // ---- (de)serialisation ----

    private static byte[] serialise(CustomPacketPayload payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        payload.write(buf);
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }

    private static FriendlyByteBuf reader(byte[] data) {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
    }

    private static void encodeEnvelope(Envelope env, FriendlyByteBuf buf) {
        buf.writeResourceLocation(env.id());
        buf.writeByteArray(env.data());
    }

    private static Envelope decodeEnvelope(FriendlyByteBuf buf) {
        return new Envelope(buf.readResourceLocation(), buf.readByteArray());
    }
}
