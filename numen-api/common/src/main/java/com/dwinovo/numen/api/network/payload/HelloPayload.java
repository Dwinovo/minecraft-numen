package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.brain.Replies;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client → Server: 装了我们的客户端的玩家进服后报到,带着它说的协议版本。服务端回 {@link HelloAckPayload},版本对得上的
 * 随后把这位玩家名下各大脑此刻的样子和会话列表补给他。没报到的玩家(没装客户端)不推任何东西。
 */
public record HelloPayload(int protocol) implements CustomPacketPayload {

    public static final Type<HelloPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "hello"));

    public static final StreamCodec<ByteBuf, HelloPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, HelloPayload::protocol, HelloPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handler invoked on the server main thread. */
    public static void handle(HelloPayload p, ServerPlayer player) {
        Replies.hello(player, p.protocol());
    }
}
