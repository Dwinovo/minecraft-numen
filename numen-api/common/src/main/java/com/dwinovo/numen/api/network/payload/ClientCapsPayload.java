package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.brain.Replies;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;

/**
 * Client → Server: 紧跟 {@link HelloPayload} 之后,客户端说它提供哪些<b>目标模组只在客户端</b>的联动(名字空间的清单,见
 * {@link com.dwinovo.numen.api.plugin.ClientMods}):这台电脑上装着 Litematica 就报 {@code litematica}。服务端收到它才算这位玩家报到齐
 * (回 {@link HelloAckPayload}、记进 {@code Presence}),所以"有客户端"与"客户端提供什么"总是同一刻成立。
 *
 * @param provided 提供的联动名字空间;没有是空表
 */
public record ClientCapsPayload(List<String> provided) implements CustomPacketPayload {

    public static final Type<ClientCapsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "client_caps"));

    public static final StreamCodec<ByteBuf, ClientCapsPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ClientCapsPayload::provided,
                    ClientCapsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handler invoked on the server main thread. */
    public static void handle(ClientCapsPayload p, ServerPlayer player) {
        Replies.ready(player, Set.copyOf(p.provided()));
    }
}
