package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.CompanionSpeech;
import com.dwinovo.numen.network.NumenPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client → Server:同伴的大脑开始/结束输出(回合进行中或语音在播)。
 * 纯姿态信号——身体据此在说话期间注视主人(闲时链消费),不承载任何
 * 逻辑状态,丢了漂了都无害。只在状态翻转时发,不逐 tick 刷。
 */
public record SpeakingStatePayload(UUID entityUuid, boolean speaking) implements NumenPayload {

    public static final ResourceLocation ID =
            new ResourceLocation(Constants.MOD_ID, "speaking_state");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(entityUuid);
        buf.writeBoolean(speaking);
    }

    public static SpeakingStatePayload read(FriendlyByteBuf buf) {
        return new SpeakingStatePayload(buf.readUUID(), buf.readBoolean());
    }

    /** Server main thread. 只认主人本人发来的状态。 */
    public static void handle(SpeakingStatePayload p, ServerPlayer sender) {
        var companion = com.dwinovo.numen.entity.NumenPlayer.findByUuid(
                sender.level().getServer(), p.entityUuid());
        if (companion == null || !companion.isOwnedByPlayer(sender.getUUID())) return;
        CompanionSpeech.setSpeaking(p.entityUuid(), p.speaking());
    }
}
