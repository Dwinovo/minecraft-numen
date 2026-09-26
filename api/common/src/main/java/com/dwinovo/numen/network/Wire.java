package com.dwinovo.numen.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.Utf8String;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 一个 Numen 包在线上最多多大,按方向。Numen 每个包的大小只看这里:送出去之前都按它量({@link NumenNetwork}),
 * 长度不由包自己定的文字字段都用它的 {@link #text()}。
 *
 * <h2>数从哪来</h2>
 * 原版给自定义载荷定的方向上限:下行 {@code ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE} 是 1048576 字节,上行
 * {@code ServerboundCustomPayloadPacket.MAX_PAYLOAD_SIZE} 是 32767 字节(两处都是私有常量,单测对着原版核这两个数)。
 * 原版只拿它们卡认不出的载荷;登记过的载荷 Fabric 与 NeoForge 都不另设上限,真正的硬顶是帧——三字节的长度前缀,一帧
 * 至多 2097151 字节,NeoForge 超过时拆包,Fabric 不拆、连接断开。取原版的方向上限,因为它们都在帧以内:哪个加载器、
 * 压不压缩、单人还是联机都成立,一个包送不送得出去不随环境变。
 *
 * <h2>装不下怎么办</h2>
 * 发送方在编码之前就量({@link #fit}):装得下照发;内容随数据长的包({@link Oversized})缩成它自己给的那个装得下的样子,
 * 如实说明原来多大、上限多少;别的包内容本来有界,装不下是填它的代码错了,当场抛出,不交给网络去断开连接。上行的工具
 * 调用不能换一个包送,由发送方在客户端先量({@code ServerToolTransport}),装不下不送,就地给模型一条失败。
 */
public enum Wire {

    /** 服务端 → 客户端。 */
    TO_CLIENT(1_048_576, "your client"),
    /** 客户端 → 服务端。 */
    TO_SERVER(32_767, "the server");

    /**
     * 文字字段编码时不设字符上限:一整个包装不装得下由 {@link #fit} 量,字段自己不再另拦——另拦就是第二个判据,
     * 而且在量的那一刻抛出。{@link Utf8String#write} 按字符数的三倍算字节上限,这里取不溢出 int 的最大值。
     */
    private static final int UNCAPPED = Integer.MAX_VALUE / 3;

    private final int bytes;
    /** 模型读的话里怎么说这个方向的收件方。 */
    private final String to;
    private final StreamCodec<ByteBuf, String> text;

    Wire(int bytes, String to) {
        this.bytes = bytes;
        this.to = to;
        this.text = StreamCodec.of((buf, value) -> Utf8String.write(buf, value, UNCAPPED),
                buf -> Utf8String.read(buf, bytes));
    }

    /** 这个方向上一个包最多多少字节。 */
    public int bytes() {
        return bytes;
    }

    /**
     * 长度不由包自己定的文字字段(模型写的、世界里长出来的、插件给的)的编解码器。编码不另拦(见 {@link #UNCAPPED}),
     * 解码以这个方向的整包上限为防线:收到的字段比整包上限还长,那一定不是 Numen 发的。
     */
    public StreamCodec<ByteBuf, String> text() {
        return text;
    }

    /**
     * 装不下时给模型的那半句,各个包的说明都从这里起头,后半句(没送、怎么办)各自接:
     * {@code "<what> came to N bytes, more than the M bytes one message to your client can carry"}。
     */
    public String tooBig(String what, int size) {
        return what + " came to " + size + " bytes, more than the " + bytes + " bytes one message to " + to
                + " can carry";
    }

    /** {@code size} 字节的一个包在这个方向上装不装得下。 */
    public boolean holds(int size) {
        return size <= bytes;
    }

    /** 这个包用它自己的编解码器编一遍有多少字节:量的就是真正要上线的那些字节。 */
    public static <B extends ByteBuf, T> int size(StreamCodec<? super B, T> codec, T payload, Supplier<B> buffers) {
        B buf = buffers.get();
        try {
            codec.encode(buf, payload);
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }

    /**
     * 这个包在这个方向上真正送出去的样子:装得下是它自己;装不下,内容随数据长的包缩成它自己给的那个,别的包抛出。
     *
     * @throws IllegalStateException 内容有界的包装不下,或者缩出来的仍装不下:填它或缩它的代码错了
     */
    public <B extends ByteBuf, T extends CustomPacketPayload> T fit(StreamCodec<? super B, T> codec, T payload,
                                                                    Supplier<B> buffers) {
        int size = size(codec, payload, buffers);
        if (holds(size)) {
            return payload;
        }
        if (!(payload instanceof Oversized<?> oversized)) {
            throw new IllegalStateException(payload.type().id() + " came to " + size + " bytes, over the " + bytes
                    + " bytes one payload " + name() + " carries; its content is bounded, so whatever filled it is "
                    + "wrong");
        }
        @SuppressWarnings("unchecked") // 一个包实现的总是它自己那一种的 Oversized
        T shrunk = ((Oversized<T>) oversized).shrunk(p -> holds(size(codec, p, buffers)), size, bytes);
        int after = size(codec, shrunk, buffers);
        if (!holds(after)) {
            throw new IllegalStateException(payload.type().id() + " shrunk from " + size + " to " + after
                    + " bytes, still over the " + bytes + " bytes one payload " + name() + " carries");
        }
        com.dwinovo.numen.Constants.LOG.warn("[numen-net] {} came to {} bytes, over the {} bytes one payload {} "
                + "carries; sent its shrunk form ({} bytes)", payload.type().id(), size, bytes, name(), after);
        return shrunk;
    }

    /**
     * 内容随数据长、整包可能装不下的包:装不下时缩成哪一个。缩出来的送到同一个去处,如实说明原来多大、上限多少;
     * 能保留的尽量保留。
     *
     * @param <P> 实现它的那个包自己
     */
    public interface Oversized<P extends CustomPacketPayload> {

        /**
         * @param fits   一个候选装不装得下,和 {@link #fit} 同一个量法;要逐步缩的包拿它试
         * @param bytes  原来整包多少字节
         * @param budget 这个方向的上限
         */
        P shrunk(Predicate<P> fits, int bytes, int budget);
    }
}
