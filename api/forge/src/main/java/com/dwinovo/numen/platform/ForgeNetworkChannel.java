package com.dwinovo.numen.platform;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.platform.services.INetworkChannel;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import com.dwinovo.numen.network.NumenPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Forge 1.20.1 implementation of {@link INetworkChannel}.
 *
 * <h2>Why an envelope instead of one Forge message per payload</h2>
 * Forge 1.20.1 routes on a classic {@link SimpleChannel} keyed by the message's
 * runtime {@code Class}. The cross-loader interface registers payloads by
 * {@code ResourceLocation}, so this channel registers a <em>single</em> Forge
 * message — an {@link Envelope} carrying {@code (payload id, serialised bytes)} —
 * and multiplexes every payload through it, looking the decoder/handler up by id
 * on both the send and receive sides.
 *
 * <p>Registration is eager (Forge's {@code SimpleChannel} accepts message
 * registration during construction). Forge 47.x predates the {@code ChannelBuilder}
 * API, so the channel is built with {@link NetworkRegistry#newSimpleChannel} and the
 * message registered via {@code registerMessage} with {@link Optional#empty()} so the
 * single envelope class travels both directions.
 *
 * <h2>Threading</h2>
 * The classic API dispatches the consumer on the network thread; we hop to the
 * receiving side's main thread with {@link NetworkEvent.Context#enqueueWork}
 * (server-main for C→S, client-main for S→C), matching the interface contract.
 */
public final class ForgeNetworkChannel implements INetworkChannel {

    private static final String PROTOCOL_VERSION = "1";

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Constants.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    /** A single opaque message multiplexing every payload: id + serialised bytes. */
    private record Envelope(ResourceLocation id, byte[] data) {}

    private record C2S<T extends NumenPayload>(
            Function<FriendlyByteBuf, T> decoder,
            BiConsumer<T, ServerPlayer> handler) {}

    private record S2C<T extends NumenPayload>(
            Function<FriendlyByteBuf, T> decoder,
            Consumer<T> handler) {}

    private final Map<ResourceLocation, C2S<?>> c2s = new HashMap<>();
    private final Map<ResourceLocation, S2C<?>> s2c = new HashMap<>();

    /** Default constructor used by {@code ServiceLoader}; wires the single envelope message. */
    public ForgeNetworkChannel() {
        CHANNEL.registerMessage(0, Envelope.class,
                ForgeNetworkChannel::encodeEnvelope,
                ForgeNetworkChannel::decodeEnvelope,
                (env, ctxSupplier) -> {
                    NetworkEvent.Context ctx = ctxSupplier.get();
                    ctx.enqueueWork(() -> {   // hop to the receiving side's main thread
                        ServerPlayer sender = ctx.getSender();
                        if (sender != null) {
                            receiveC2S(env, sender);   // a C→S packet arrived on the server
                        } else {
                            receiveS2C(env);           // an S→C packet arrived on the client
                        }
                    });
                    ctx.setPacketHandled(true);
                },
                Optional.empty());   // bidirectional: the one envelope class travels both ways
    }

    // ---- registration ----

    @Override
    public <T extends NumenPayload> void registerClientToServer(
            ResourceLocation id,
            Function<FriendlyByteBuf, T> decoder,
            BiConsumer<T, ServerPlayer> handler) {
        c2s.put(id, new C2S<>(decoder, handler));
    }

    @Override
    public <T extends NumenPayload> void registerServerToClient(
            ResourceLocation id,
            Function<FriendlyByteBuf, T> decoder,
            Consumer<T> handler) {
        s2c.put(id, new S2C<>(decoder, handler));
    }

    // ---- sending ----

    @Override
    public void sendToServer(NumenPayload payload) {
        CHANNEL.sendToServer(new Envelope(payload.id(), serialise(payload)));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, NumenPayload payload) {
        // Classic API: target first, message second; PLAYER.with takes a Supplier<ServerPlayer>.
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Envelope(payload.id(), serialise(payload)));
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
    private static <T extends NumenPayload> void dispatchC2S(C2S<T> reg, byte[] data, ServerPlayer sender) {
        reg.handler().accept(reg.decoder().apply(reader(data)), sender);
    }

    private static <T extends NumenPayload> void dispatchS2C(S2C<T> reg, byte[] data) {
        reg.handler().accept(reg.decoder().apply(reader(data)));
    }

    // ---- (de)serialisation ----

    private static byte[] serialise(NumenPayload payload) {
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
