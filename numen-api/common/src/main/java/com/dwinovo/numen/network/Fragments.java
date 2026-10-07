package com.dwinovo.numen.network;

import com.dwinovo.numen.network.payload.FragmentPayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 超过单包上限的消息怎么切、怎么拼({@link Wire} 说了为什么在自己的载荷层做、各个数从哪来)。
 *
 * <h2>切</h2>
 * {@link #packets} 是一个包在一个方向上真正送出去的样子:没实现 {@link Wire.Fragmentable} 的包是 {@link Wire#fit} 量过的
 * 那一个;可分片的包编码一遍,装得下单包上限就是它自己(没有任何额外字节),装不下切成 {@link #chunkBytes} 一段的
 * {@link FragmentPayload},除最后一片外片片等长,连续发出,不逐片等确认。
 *
 * <h2>拼</h2>
 * 每个连接有一个 {@link Inbox},按消息 id 记着拼装中的消息。收到的片不合规矩就当场拒收并丢弃那一整条消息(接下来属于它的片
 * 没有去处,悄悄放过):片的编号不连续、片不是满的(最后一片除外)、声明的片数多过装满 {@link Wire#MESSAGE_BYTES} 要的、已收的字节超过它、
 * 同时拼装的消息多过 {@link Wire#ASSEMBLING}。<b>每片必须是满的</b>是为了防放大:发送方用一字节的片填满总数,
 * 每片的对象头就比内容大几十倍。
 */
public final class Fragments {

    /**
     * 一片里留给包头的字节:三个 VarInt(至多 15 字节)、第 0 片的原包种类(至多 64 个字符的 id 加长度前缀)、字节数组的长度前缀。
     * 取 128,有余。
     */
    static final int HEADER_BYTES = 128;

    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private Fragments() {}

    /** 这个方向上一片里装多少字节的原包内容。 */
    public static int chunkBytes(Wire direction) {
        return direction.bytes() - HEADER_BYTES;
    }

    /** 一条合规的消息在这个方向上至多切成几片。 */
    static int maxCount(Wire direction) {
        return (Wire.MESSAGE_BYTES + chunkBytes(direction) - 1) / chunkBytes(direction);
    }

    /**
     * 这个包在这个方向上真正送出去的包,按先后。
     *
     * @throws IllegalStateException 可分片的包连整条消息的上限都装不下(发送方应当先用 {@link Wire#carries} 量,装不下不送);
     *                               别的包的情形见 {@link Wire#fit}
     */
    public static <B extends ByteBuf, T extends CustomPacketPayload> List<CustomPacketPayload> packets(
            Wire direction, StreamCodec<? super B, T> codec, T payload, Supplier<B> buffers) {
        if (!(payload instanceof Wire.Fragmentable)) {
            return List.of(direction.fit(codec, payload, buffers));
        }
        byte[] encoded = encode(codec, payload, buffers);
        if (direction.holds(encoded.length)) {
            return List.of(payload);
        }
        return split(direction, NEXT_ID.getAndIncrement(), payload.type().id(), encoded);
    }

    /** 把编码后的字节切成片。 */
    static List<CustomPacketPayload> split(Wire direction, int id, ResourceLocation kind, byte[] encoded) {
        if (!direction.carries(encoded.length)) {
            throw new IllegalStateException(kind + " came to " + encoded.length + " bytes, over the "
                    + Wire.MESSAGE_BYTES + " bytes one message " + direction + " carries; whoever sends it checks "
                    + "Wire.carries first");
        }
        int chunk = chunkBytes(direction);
        int count = (encoded.length + chunk - 1) / chunk;
        List<CustomPacketPayload> out = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            byte[] piece = Arrays.copyOfRange(encoded, index * chunk, Math.min(encoded.length, (index + 1) * chunk));
            out.add(new FragmentPayload(FragmentPayload.typeOf(direction), id, index, count,
                    index == 0 ? Optional.of(kind) : Optional.empty(), piece));
        }
        return out;
    }

    /**
     * 一个包经过这个方向的线之后对端拿到的样子:切、每个包编一遍再解一遍、对端的收件箱拼回、解码——收发两头用的部件
     * 都是产品里的,只是不出进程。没有真网线的地方(GameTest、评测、回环的客户端)用它。
     */
    public static <T extends CustomPacketPayload> T crossed(Wire direction, StreamCodec<ByteBuf, T> codec, T payload) {
        Inbox inbox = new Inbox(direction);
        for (CustomPacketPayload packet : packets(direction, codec, payload, Unpooled::buffer)) {
            if (!(packet instanceof FragmentPayload fragment)) {
                @SuppressWarnings("unchecked") // 不是片的就是 payload 自己,或它缩成的样子
                T single = (T) packet;
                return decode(codec, encode(codec, single, Unpooled::buffer));
            }
            FragmentPayload arrived = decode(FragmentPayload.codecOf(direction),
                    encode(FragmentPayload.codecOf(direction), fragment, Unpooled::buffer));
            switch (inbox.accept(arrived)) {
                case Complete whole -> {
                    return decode(codec, whole.bytes());
                }
                case Rejected rejected -> throw new IllegalStateException(rejected.why());
                case Pending pending -> { }
            }
        }
        throw new IllegalStateException(payload.type().id() + " was cut into fragments that never completed");
    }

    private static <B extends ByteBuf, T> byte[] encode(StreamCodec<? super B, T> codec, T payload,
                                                        Supplier<B> buffers) {
        B buf = buffers.get();
        try {
            codec.encode(buf, payload);
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    /**
     * 拼回的字节解成原包。原包的编解码器不碰注册表(见 {@link Wire.Fragmentable}),所以用空的注册表就够。
     *
     * @throws DecoderException 字节读不成这种包,或读完还剩字节
     */
    public static <T> T decode(StreamCodec<? super RegistryFriendlyByteBuf, T> codec, byte[] bytes) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), RegistryAccess.EMPTY);
        try {
            T payload = codec.decode(buf);
            if (buf.isReadable()) {
                throw new DecoderException("a fragmented message had " + buf.readableBytes() + " bytes left over");
            }
            return payload;
        } finally {
            buf.release();
        }
    }

    /** 收一片之后消息的去向。 */
    public sealed interface Outcome permits Pending, Complete, Rejected {}

    /** 还没收齐,或这一片属于一条已经丢弃的消息:没有什么要做。 */
    public record Pending() implements Outcome {}

    /** 收齐了:原包的种类与编码后的字节。 */
    public record Complete(ResourceLocation kind, byte[] bytes) implements Outcome {}

    /** 这一片不合规矩,它所在的整条消息已经丢弃。 */
    public record Rejected(String why) implements Outcome {}

    /** 一个连接在一个方向上收到的、拼装中的消息。线程:任何线程。 */
    public static final class Inbox {

        private static final class Assembly {
            final ResourceLocation kind;
            final int count;
            final List<byte[]> chunks = new ArrayList<>();
            int bytes;

            Assembly(ResourceLocation kind, int count) {
                this.kind = kind;
                this.count = count;
            }
        }

        private final Wire direction;
        private final Map<Integer, Assembly> assembling = new HashMap<>();

        public Inbox(Wire direction) {
            this.direction = direction;
        }

        public Wire direction() {
            return direction;
        }

        /** 现在拼装中的消息数。 */
        public synchronized int assembling() {
            return assembling.size();
        }

        /** 连接断了:没收完的残片全丢。 */
        public synchronized void clear() {
            assembling.clear();
        }

        public synchronized Outcome accept(FragmentPayload fragment) {
            int chunk = chunkBytes(direction);
            int id = fragment.id();
            int size = fragment.chunk().length;
            if (fragment.count() < 1 || fragment.count() > maxCount(direction) || fragment.index() < 0
                    || fragment.index() >= fragment.count() || size < 1 || size > chunk) {
                assembling.remove(id);
                return new Rejected("message " + id + " has a fragment " + fragment.index() + " of "
                        + fragment.count() + " that is not one");
            }
            boolean last = fragment.index() == fragment.count() - 1;
            if (!last && size != chunk) {
                assembling.remove(id);
                return new Rejected("message " + id + " has fragment " + fragment.index() + " of " + size
                        + " bytes that is not full (" + chunk + ")");
            }
            Assembly message;
            if (fragment.index() == 0) {
                if (assembling.containsKey(id)) {
                    assembling.remove(id);
                    return new Rejected("message " + id + " started again before it was complete");
                }
                if (assembling.size() >= Wire.ASSEMBLING) {
                    return new Rejected("message " + id + " would be the " + (assembling.size() + 1)
                            + "th being assembled on this connection, over " + Wire.ASSEMBLING);
                }
                message = new Assembly(fragment.inner().orElseThrow(), fragment.count());
                assembling.put(id, message);
            } else {
                message = assembling.get(id);
                if (message == null) {
                    return new Pending();   // 一条已经丢弃的消息的后半
                }
                if (fragment.index() != message.chunks.size() || fragment.count() != message.count) {
                    assembling.remove(id);
                    return new Rejected("message " + id + " got fragment " + fragment.index() + " of "
                            + fragment.count() + " after " + message.chunks.size() + " of " + message.count);
                }
            }
            if ((long) message.bytes + size > Wire.MESSAGE_BYTES) {
                assembling.remove(id);
                return new Rejected("message " + id + " is over " + Wire.MESSAGE_BYTES + " bytes");
            }
            message.chunks.add(fragment.chunk());
            message.bytes += size;
            if (message.chunks.size() < message.count) {
                return new Pending();
            }
            assembling.remove(id);
            byte[] whole = new byte[message.bytes];
            int at = 0;
            for (byte[] piece : message.chunks) {
                System.arraycopy(piece, 0, whole, at, piece.length);
                at += piece.length;
            }
            return new Complete(message.kind, whole);
        }
    }
}
