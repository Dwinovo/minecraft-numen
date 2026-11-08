package com.dwinovo.numen.api.network.payload;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.FriendlyByteBuf;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.brain.Replies;
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

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "client_caps");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeCollection(provided, FriendlyByteBuf::writeUtf);
    }

    public static ClientCapsPayload read(FriendlyByteBuf buf) {
        return new ClientCapsPayload(buf.readList(FriendlyByteBuf::readUtf));
    }

    /** Handler invoked on the server main thread. */
    public static void handle(ClientCapsPayload p, ServerPlayer player) {
        Replies.ready(player, Set.copyOf(p.provided()));
    }
}
