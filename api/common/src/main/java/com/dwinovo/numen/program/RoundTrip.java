package com.dwinovo.numen.program;

import com.dwinovo.numen.network.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 编码再解码:一个包在线上的样子。回环的两处(传输与客户端)都经它,不出进程也走一遍真的编解码,并且和网络发送一样先按这个方向的
 * 上限量过({@link Wire#fit}):装不下的包缩成它自己给的样子。
 */
final class RoundTrip {

    private RoundTrip() {}

    static <T extends CustomPacketPayload> T of(Wire direction, StreamCodec<ByteBuf, T> codec, T payload) {
        T sent = direction.fit(codec, payload, Unpooled::buffer);
        ByteBuf buf = Unpooled.buffer();
        try {
            codec.encode(buf, sent);
            return codec.decode(buf);
        } finally {
            buf.release();
        }
    }
}
