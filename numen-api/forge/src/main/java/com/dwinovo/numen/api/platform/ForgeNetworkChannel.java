package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.platform.services.INetworkChannel;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.network.CustomPayloadEvent;
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
 * Forge 1.20.2 routes on a {@link SimpleChannel} keyed by the message's
 * runtime {@code Class}. The cross-loader interface registers payloads by
 * {@code ResourceLocation}, so this channel registers a <em>single</em> Forge
 * message — an {@link Envelope} carrying {@code (payload id, serialised bytes)} —
 * and multiplexes every payload through it, looking the decoder/handler up by id
 * on both the send and receive sides.
 *
 * <p>Registration is eager (Forge's {@code SimpleChannel} accepts message
 * registration during construction); the single envelope class is registered without a direction so it
 * travels both ways.
 *
 * <h2>Threading</h2>
 * The consumer runs on the network thread. Downlink payloads and uplink payloads registered
 * {@link Execution#MAIN} hop to the receiving side's main thread with {@link CustomPayloadEvent.Context#enqueueWork}
 * (server-main for C→S, client-main for S→C); uplink payloads registered {@link Execution#NETWORK} run right there.
 *
 * <h2>Vanilla clients</h2>
 * The channel is optional both ways: a client without Numen (or a server without it) is accepted, and
 * {@link #canSend} asks whether the other end has the channel before anything goes out.
 */
public final class ForgeNetworkChannel implements INetworkChannel {

    private static final int PROTOCOL_VERSION = 1;

    private static final ResourceLocation CHANNEL_ID = new ResourceLocation(Constants.MOD_ID, "main");

    /** 对端的版本:同版,或对端根本没有这条频道(原版的、没装我们的),两头都是({@code optional})。 */
    private static final SimpleChannel CHANNEL = ChannelBuilder.named(CHANNEL_ID)
            .networkProtocolVersion(PROTOCOL_VERSION)
            .optional()
            .simpleChannel();

    /** A single opaque message multiplexing every payload: id + serialised bytes. */
    private record Envelope(ResourceLocation id, byte[] data) {}

    private record C2S<T extends CustomPacketPayload>(
            Function<FriendlyByteBuf, T> decoder,
            BiConsumer<T, ServerPlayer> handler,
            Execution execution) {}

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
                .consumerNetworkThread((env, ctx) -> {
                    ServerPlayer sender = ctx.getSender();
                    if (sender != null) {
                        receiveC2S(env, sender, ctx);   // a C→S packet arrived on the server
                    } else {
                        ctx.enqueueWork(() -> receiveS2C(env));   // an S→C packet arrived on the client
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
            BiConsumer<T, ServerPlayer> handler,
            Execution execution) {
        c2s.put(id, new C2S<>(decoder, handler, execution));
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
        // Lazy class-load: Minecraft is client-only. Server JVM never reaches this call site.
        // 没连接就发不出去，而发包往往夹在一串清理中间（登出钩子），一报错就把
        // 后面的清理全截断了。警告并丢包：丢一个本来就发不出去的包无害，截断清理有害。
        // 不静默：真有人在错的时机发包，日志里看得到。
        if (net.minecraft.client.Minecraft.getInstance().getConnection() == null) {
            Constants.LOG.warn("[numen] 无连接,丢弃 C2S 包 {}", payload.id());
            return;
        }
        CHANNEL.send(new Envelope(payload.id(), serialise(payload)), PacketDistributor.SERVER.noArg());
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        CHANNEL.send(new Envelope(payload.id(), serialise(payload)), PacketDistributor.PLAYER.with(player));
    }

    @Override
    public boolean canSend(ServerPlayer player, ResourceLocation id) {
        // 所有载荷走同一条频道:对端有这条频道,就收得下其中任何一种
        return CHANNEL.isRemotePresent(player.connection.getConnection());
    }

    /**
     * 下行的一个包里装的 Numen 载荷;不是这条通道发的就是 null。没有真网线的地方(GameTest 里扮主人客户端的连接)用它读下行的包,
     * 与客户端收包走同一张解码表。
     */
    public CustomPacketPayload payloadOf(net.minecraft.network.protocol.Packet<?> packet) {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom)
                || !(custom.payload() instanceof DiscardedPayload raw) || !raw.id().equals(CHANNEL_ID)) {
            return null;
        }
        FriendlyByteBuf data = new FriendlyByteBuf(raw.data().duplicate());
        data.readVarInt();   // SimpleChannel 的消息序号
        Envelope env = decodeEnvelope(data);
        S2C<?> reg = s2c.get(env.id());
        return reg == null ? null : reg.decoder().apply(reader(env.data()));
    }

    // ---- receiving ----

    private void receiveC2S(Envelope env, ServerPlayer sender, CustomPayloadEvent.Context ctx) {
        C2S<?> reg = c2s.get(env.id());
        if (reg == null) {
            Constants.LOG.warn("[numen-net] dropped unknown C→S payload {}", env.id());
            return;
        }
        if (reg.execution() == Execution.NETWORK) {
            dispatchC2S(reg, env.data(), sender);
        } else {
            ctx.enqueueWork(() -> dispatchC2S(reg, env.data(), sender));
        }
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
