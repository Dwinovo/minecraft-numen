package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.network.ClientPayloadSink;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → Client: 收下了 {@link HelloPayload}(协议对得上),这位玩家报到齐了;随后到的是名册与会话。只发给协议对得上的客户端。
 */
public record HelloAckPayload() implements CustomPacketPayload {

    public static final Type<HelloAckPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "hello_ack"));

    public static final StreamCodec<ByteBuf, HelloAckPayload> STREAM_CODEC =
            StreamCodec.unit(new HelloAckPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client main thread. */
    public static void handle(HelloAckPayload p) {
        ClientPayloadSink.helloAck.accept(p);
    }
}
