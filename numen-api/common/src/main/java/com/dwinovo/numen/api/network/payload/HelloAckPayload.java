package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.network.ClientPayloadSink;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → Client: 收下了 {@link HelloPayload}(协议对得上)——这位玩家能不能改服务器上的配置(模型档案与 key、外部大脑……,
 * 见 {@code Authority}),以及他是不是这台服务器的房主(他的"我的"就是服务器目录那一份,见 {@code Homes#isHost})。
 * 只发给协议对得上的客户端,格式随协议走。
 */
public record HelloAckPayload(boolean admin, boolean host) implements CustomPacketPayload {

    public static final Type<HelloAckPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "hello_ack"));

    public static final StreamCodec<ByteBuf, HelloAckPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, HelloAckPayload::admin,
                    ByteBufCodecs.BOOL, HelloAckPayload::host,
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
