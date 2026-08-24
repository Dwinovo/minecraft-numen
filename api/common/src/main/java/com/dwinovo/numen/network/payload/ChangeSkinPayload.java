package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client → Server:编辑卡给一只<b>已创建</b>同伴换皮肤(或清空回原版默认)。
 *
 * <p>皮肤的真源是注册表({@link CompanionRegistry.Entry#withSkin}),spawn 时注入
 * GameProfile——所以换肤 = 改注册表 + 原地回收身体(存盘→离场→按注册表重建,
 * {@link Companions#dormant}/{@link Companions#respawn} 现成一对,背包/位置/在做的活
 * 全走既有持久化通道回来)。客户端只认 Mojang 签过名的 textures,连皮肤格式校验
 * 都不用自己写。休眠/死亡的身体只改注册表,下次重建自然生效。
 */
public record ChangeSkinPayload(UUID uuid, String skinValue, String skinSig)
        implements CustomPacketPayload {

    public static final ResourceLocation ID =
            new ResourceLocation(Constants.MOD_ID, "change_skin");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(uuid);
        buf.writeUtf(skinValue == null ? "" : skinValue, SummonRequestPayload.MAX_SKIN_VALUE);
        buf.writeUtf(skinSig == null ? "" : skinSig, SummonRequestPayload.MAX_SKIN_SIG);
    }

    public static ChangeSkinPayload read(FriendlyByteBuf buf) {
        return new ChangeSkinPayload(buf.readUUID(),
                buf.readUtf(SummonRequestPayload.MAX_SKIN_VALUE),
                buf.readUtf(SummonRequestPayload.MAX_SKIN_SIG));
    }

    public static void handle(ChangeSkinPayload p, ServerPlayer owner) {
        MinecraftServer server = ((ServerLevel) owner.level()).getServer();
        if (server == null || p.uuid() == null) return;
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry entry = reg.find(p.uuid());
        if (entry == null || !owner.getUUID().equals(entry.owner())) {
            return;   // 不存在或不是他的
        }
        reg.put(p.uuid(), entry.withSkin(p.skinValue(), p.skinSig()));
        NumenPlayer body = NumenPlayer.findByUuid(server, p.uuid());
        if (body != null) {
            // 活体立即生效:存盘离场再按注册表重建。同一 tick 内先移后加同一 UUID,
            // 与死亡复活流用的是同一对出入口。
            Companions.dormant(server, body);
            Companions.respawn(server, p.uuid());
        }
    }
}
