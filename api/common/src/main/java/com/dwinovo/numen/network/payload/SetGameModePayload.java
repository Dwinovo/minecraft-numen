package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
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
 * Client → Server:编辑卡把一只<b>已创建</b>同伴的游戏模式切到生存/创造。
 * 权限门与召唤同一道({@link Companions#applyGameMode}):创造要主人有
 * gamemode 权限或本人在创造。改完立刻重推名册,编辑卡的模式格按新真相显示。
 */
public record SetGameModePayload(UUID uuid, boolean creative) implements CustomPacketPayload {

    public static final ResourceLocation ID =
            new ResourceLocation(Constants.MOD_ID, "set_game_mode");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(uuid);
        buf.writeBoolean(creative);
    }

    public static SetGameModePayload read(FriendlyByteBuf buf) {
        return new SetGameModePayload(buf.readUUID(), buf.readBoolean());
    }

    public static void handle(SetGameModePayload p, ServerPlayer owner) {
        MinecraftServer server = ((ServerLevel) owner.level()).getServer();
        if (server == null || p.uuid() == null) return;
        NumenPlayer body = NumenPlayer.findByUuid(server, p.uuid());
        if (body == null || !body.isOwnedByPlayer(owner.getUUID())) {
            return;   // 不在场(休眠/死亡)或不是他的:模式住在活体上,没有活体没有可改的
        }
        Companions.applyGameMode(owner, body, p.creative());
        Companions.syncRosterToOwner(server, owner);
    }
}
