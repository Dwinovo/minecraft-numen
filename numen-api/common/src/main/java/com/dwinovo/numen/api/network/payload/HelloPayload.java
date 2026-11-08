package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.network.NumenPayload;
import net.minecraft.network.FriendlyByteBuf;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.brain.Replies;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client → Server: 对 {@link ServerHelloPayload} 的回答,客户端说的协议版本。版本对得上,服务端接着等它的 {@link ClientCapsPayload},
 * 到齐了回 {@link HelloAckPayload},随后把名册、这位玩家名下各大脑此刻的样子和会话列表补给他;对不上就什么也不发。没报到的玩家(没装客户端、或装的版本不同)
 * 收不到任何 Numen 包。
 *
 * <p><b>格式永远只是一个协议号</b>,理由见 {@link ServerHelloPayload}。
 */
public record HelloPayload(int protocol) implements NumenPayload {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "hello");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(protocol);
    }

    public static HelloPayload read(FriendlyByteBuf buf) {
        return new HelloPayload(buf.readVarInt());
    }

    /** Handler invoked on the server main thread. */
    public static void handle(HelloPayload p, ServerPlayer player) {
        Replies.reported(player, p.protocol());
    }
}
