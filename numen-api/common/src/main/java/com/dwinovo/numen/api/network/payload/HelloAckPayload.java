package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.network.ClientPayloadSink;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → Client: 对 {@link HelloPayload} 的回答——服务端说的协议版本,以及这位玩家能不能改服务器上的配置
 * (模型档案与 key、外部大脑……,见 {@code Authority})。版本对不上,客户端不订阅、不画服务端大脑的东西,并提示。
 */
public record HelloAckPayload(int protocol, boolean admin) implements CustomPacketPayload {

    public static final Type<HelloAckPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "hello_ack"));

    public static final StreamCodec<ByteBuf, HelloAckPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, HelloAckPayload::protocol,
                    ByteBufCodecs.BOOL, HelloAckPayload::admin,
                    HelloAckPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client main thread. */
    public static void handle(HelloAckPayload p) {
        ClientPayloadSink.helloAck.accept(p);
    }
}
