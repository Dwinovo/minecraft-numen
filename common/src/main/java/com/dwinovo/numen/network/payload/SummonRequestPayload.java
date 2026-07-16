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
 *
 * <p>名字限定 Minecraft 官方命名规则(3~16 位英文/数字/下划线),并且<b>名字就是
 * 皮肤来源</b>:服务端异步查同名正版玩家,查到就穿它的皮肤,查不到静默回落
 * 原版默认皮肤(日志可查,不打扰玩家——起个非正版名字太常见了)。
 */
public record SummonRequestPayload(String name) implements CustomPacketPayload {

    public static final int MAX_NAME = 16;

    public static final Type<SummonRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "summon_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SummonRequestPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.stringUtf8(MAX_NAME), SummonRequestPayload::name,
                    SummonRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server main thread. */
    public static void handle(SummonRequestPayload p, ServerPlayer owner) {
        String name = p.name() == null ? "" : p.name().trim();
        if (!com.dwinovo.numen.entity.MojangSkins.validName(name)) return;   // 服务端权威校验
        // 皮肤查询走 HTTP,绝不阻塞主线程:取到(或确认没有)后蹦回主线程再召唤。
        var server = owner.level().getServer();
        com.dwinovo.numen.entity.MojangSkins.fetch(name).thenAccept(skin -> server.execute(() -> {
            if (owner.hasDisconnected()) return;
            ServerLevel level = (ServerLevel) owner.level();
            Companions.summon(server, owner.getUUID(), name, level, owner.position(), skin);
            Companions.syncRosterToOwner(server, owner);   // push the new roster to the owner
        }));
    }
}
