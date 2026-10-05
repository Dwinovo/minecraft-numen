package com.dwinovo.numen.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.Utf8String;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 一个 Numen 包在线上最多多大,一条消息最多多大,按方向。Numen 每个包的大小只看这里:送出去之前都按它量({@link NumenNetwork}),
 * 长度不由包自己定的文字字段都用它的 {@link #text()}。
 *
 * <h2>单包上限:各方向的安全线</h2>
 * 上行 {@value #TO_SERVER_PACKET} 字节,下行 {@value #TO_CLIENT_PACKET} 字节。
 * 下行的数就是原版 {@code ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE}。上行的数是 32767:1.20.1 的原版对客户端发来的
 * <em>所有</em>自定义载荷都硬卡这个数;1.20.2 起原版只卡认不出的载荷,但 NeoForge({@code GenericPacketSplitter})、Fabric
 * ({@code registerLarge},上行分片阈值同为 32767)与 CC: Tweaked(上传文件按 30 KB 切片)都把它当成客户端到服务端一个包的
 * 安全线,超过就分片。这个数在哪个加载器、哪个版本、直连还是经代理都送得出去,所以判据不随环境变。再加上包头仍远在一帧
 * ({@link #FRAME_BYTES},三字节长度前缀能写的最大帧)以内。
 *
 * <h2>超过单包上限:分成片,在对端拼回</h2>
 * 她的一整段程序(带上这个连接还没送过的模块原文)、反向请求的答复、反向请求本身,长度随数据长,一个包装不下。它们实现
 * {@link Fragmentable},{@link Fragments} 把编码后的字节切成带编号的片({@code FragmentPayload}:消息 id、第几片、共几片)连续发出,
 * 不逐片等确认——Minecraft 的连接保证有序可靠——对端收齐拼回原包,交给原来的处理器,处理器感觉不到分过片。没超过单包上限的包照旧
 * 一个包,没有任何额外字节。
 *
 * <p>为什么在自己的载荷层做,而不用加载器的分片器:NeoForge 与 Fabric 的分片行为不一(阈值、要不要对端协商、登记方式都不同),
 * Forge 1.20.1 根本没有,而且要求对端同样有它;自己的一层在 13 个分支、3 个加载器上是同一份代码,收发两端永远配套。也不碰
 * Netty 流水线:那要 mixin {@code Connection},各分支、各加载器写法不同,又容易与加载器和别的模组冲突。先例是 CC: Tweaked 的
 * {@code UploadFileMessage}:同样在消息层按 30 KB 切片上传,由接收端按编号拼回。
 *
 * <h2>整条消息的上限与防滥用</h2>
 * 一条消息(拼回后的包)至多 {@link #MESSAGE_BYTES}:要大过正当消息的最大值——模块缓存每位主人 4 MB
 * ({@code ProgramLimits.MODULE_CACHE_BYTES}),服务端报缺时客户端要把这些正文一次重送,再加程序本身与清单——所以取 8 MiB。
 * 拼装中的消息超过它,或每个连接同时拼装的消息超过 {@link #ASSEMBLING},当场拒收并丢弃;断线清掉未收完的残片。
 *
 * <h2>装不下怎么办</h2>
 * 发送方在编码之前就量({@link #fit}):装得下照发;内容随数据长的包({@link Oversized})缩成它自己给的那个装得下的样子,
 * 如实说明原来多大、上限多少;可分片的包({@link Fragmentable})只在超过整条消息的上限时才装不下,由发送方在送出之前
 * 先量({@link #carries}),装不下不送,就地给模型一条失败;别的包内容本来有界,装不下是填它的代码错了,当场抛出,不交给网络去断开连接。
 */
public enum Wire {

    /** 服务端 → 客户端。 */
    TO_CLIENT(Wire.TO_CLIENT_PACKET, "your client"),
    /** 客户端 → 服务端。 */
    TO_SERVER(Wire.TO_SERVER_PACKET, "the server");

    /** 下行一个包的字节上限:原版的 {@code MAX_PAYLOAD_SIZE}。 */
    public static final int TO_CLIENT_PACKET = 1_048_576;
    /** 上行一个包的字节上限:各加载器与 CC: Tweaked 共同的安全线。 */
    public static final int TO_SERVER_PACKET = 32_767;

    /**
     * 一条消息(分片的包拼回之后)最多多少字节,两个方向相同:8 MiB。比正当消息的最大值大——模块缓存一位主人至多
     * 4 MB({@code ProgramLimits.MODULE_CACHE_BYTES}),缺了要整批重送,再加程序、清单与包头。再大就不是 Numen 的正当消息。
     */
    public static final int MESSAGE_BYTES = 8 << 20;

    /**
     * 一个连接同时拼装中的消息最多几条:8。一条消息的片连续发出,正当情形下同一刻只有一条在拼;几个线程同时送大消息时片会交错,
     * 一位主人至多同时跑 8 段程序({@code ProgramLimits.PER_OWNER}),每段至多一条大消息在路上。多出来的是滥用:最坏占
     * {@code ASSEMBLING * MESSAGE_BYTES} 即 64 MiB,仅在这一个连接的生命期内,断线即清。
     */
    public static final int ASSEMBLING = 8;

    /** 三字节长度前缀能写的最大帧:2^21 - 1。 */
    public static final int FRAME_BYTES = (1 << 21) - 1;

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
                buf -> Utf8String.read(buf, MESSAGE_BYTES));
    }

    /** 这个方向上一个包最多多少字节。 */
    public int bytes() {
        return bytes;
    }

    /**
     * 长度不由包自己定的文字字段(模型写的、世界里长出来的、插件给的)的编解码器。编码不另拦(见 {@link #UNCAPPED}),
     * 解码以整条消息的上限为防线({@link #MESSAGE_BYTES}):可分片的包整条拼回之后才解码,字段可以比一个包长;收到的字段比整条
     * 消息还长,那一定不是 Numen 发的。
     */
    public StreamCodec<ByteBuf, String> text() {
        return text;
    }

    /**
     * 内容随数据长、装不下<em>一个包</em>就缩的包,装不下时给模型的那半句,后半句(缩成了什么、怎么办)各自接:
     * {@code "<what> came to N bytes, more than the M bytes one message to your client can carry"}。
     */
    public String tooBig(String what, int size) {
        return tooBig(what, size, bytes);
    }

    /** 可分片的包超过整条消息的上限时给模型的那半句,格式同 {@link #tooBig(String, int)},数是 {@link #MESSAGE_BYTES}。 */
    public String tooBigMessage(String what, int size) {
        return tooBig(what, size, MESSAGE_BYTES);
    }

    private String tooBig(String what, int size, int limit) {
        return what + " came to " + size + " bytes, more than the " + limit + " bytes one message to " + to
                + " can carry";
    }

    /** {@code size} 字节的一个包在这个方向上装不装得下<em>一个包</em>。 */
    public boolean holds(int size) {
        return size <= bytes;
    }

    /** {@code size} 字节的一个可分片的包,整条消息的上限装不装得下(装得下是一个包或分成片,都送得出去)。 */
    public boolean carries(int size) {
        return size <= MESSAGE_BYTES;
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
     * 这个包在这个方向上真正作为<em>一个包</em>送出去的样子:装得下是它自己;装不下,内容随数据长的包缩成它自己给的那个,
     * 别的包抛出。可分片的包不走这里({@link Fragments#packets}):它们装不下一个包就分片。
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
     * 内容随数据长、一个包装不下就分成片送的包:拼回后至多 {@link #MESSAGE_BYTES}。编解码器必须写在 {@code ByteBuf} 上,不碰
     * 注册表(对端拼回时没有这位玩家的注册表)。哪些包可分片只由这个接口声明,发送与接收都看它。
     */
    public interface Fragmentable {
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
