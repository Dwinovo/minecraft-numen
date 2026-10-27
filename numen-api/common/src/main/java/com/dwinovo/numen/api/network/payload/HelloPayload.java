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
 * Client → Server: 对 {@link ServerHelloPayload} 的回答,客户端说的协议版本。版本对得上,服务端接着等它的 {@link ClientCapsPayload},
 * 到齐了回 {@link HelloAckPayload},随后把名册、这位玩家名下各大脑此刻的样子和会话列表补给他;对不上就什么也不发。没报到的玩家(没装客户端、或装的版本不同)
 * 收不到任何 Numen 包。
 *
 * <p><b>格式永远只是一个协议号</b>,理由见 {@link ServerHelloPayload}。
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
        Replies.reported(player, p.protocol());
    }
}
