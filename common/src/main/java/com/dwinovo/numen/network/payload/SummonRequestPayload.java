package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.Companions;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client → Server: the owner asked to summon a companion by name from the panel's
 * "+" button. Mirrors the {@code /numen player summon} command — summon is
 * idempotent per (owner, name), so re-summoning an existing name just wakes it.
 * {@code skin} 可为空:非空时是要借皮肤的正版玩家名,服务端异步向 Mojang 取签名
 * textures 后再召唤(失败回落默认皮肤并私聊告知)。
 */
public record SummonRequestPayload(String name, String skin) implements CustomPacketPayload {

    public static final int MAX_NAME = 32;
    public static final int MAX_SKIN = 16;

    public static final Type<SummonRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "summon_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SummonRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(MAX_NAME), SummonRequestPayload::name,
                    ByteBufCodecs.stringUtf8(MAX_SKIN), SummonRequestPayload::skin,
                    SummonRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server main thread. */
    public static void handle(SummonRequestPayload p, ServerPlayer owner) {
        String name = p.name() == null ? "" : p.name().trim();
        if (name.isEmpty() || name.length() > MAX_NAME) return;
        String skinName = p.skin() == null ? "" : p.skin().trim();
        if (skinName.isEmpty() || !com.dwinovo.numen.entity.MojangSkins.validName(skinName)) {
            summonNow(owner, name, null);
            return;
        }
        // 皮肤查询走 HTTP,绝不阻塞主线程:取到(或失败)后蹦回主线程再召唤。
        var server = owner.level().getServer();
        com.dwinovo.numen.entity.MojangSkins.fetch(skinName).thenAccept(skin -> server.execute(() -> {
            if (owner.hasDisconnected()) return;
            summonNow(owner, name, skin);
            if (skin == null) {
                owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "[Numen] 皮肤「" + skinName + "」获取失败(查无此正版玩家或网络不通),已用默认皮肤"));
            }
        }));
    }

    private static void summonNow(ServerPlayer owner, String name,
                                  com.dwinovo.numen.entity.MojangSkins.Skin skin) {
        ServerLevel level = (ServerLevel) owner.level();
        Companions.summon(level.getServer(), owner.getUUID(), name, level, owner.position(), skin);
        Companions.syncRosterToOwner(level.getServer(), owner);   // push the new roster to the owner
    }
}
