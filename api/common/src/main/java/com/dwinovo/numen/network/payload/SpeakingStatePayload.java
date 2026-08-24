package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.CompanionSpeech;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client → Server:同伴的大脑开始/结束输出(回合进行中或语音在播)。
 * 纯姿态信号——身体据此在说话期间注视主人(闲时链消费),不承载任何
 * 逻辑状态,丢了漂了都无害。只在状态翻转时发,不逐 tick 刷。
 */
public record SpeakingStatePayload(UUID entityUuid, boolean speaking) implements CustomPacketPayload {

    public static final Type<SpeakingStatePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "speaking_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpeakingStatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, SpeakingStatePayload::entityUuid,
                    ByteBufCodecs.BOOL, SpeakingStatePayload::speaking,
                    SpeakingStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server main thread. 只认主人本人发来的状态。 */
    public static void handle(SpeakingStatePayload p, ServerPlayer sender) {
        var companion = com.dwinovo.numen.entity.NumenPlayer.findByUuid(
                sender.level().getServer(), p.entityUuid());
        if (companion == null || !companion.isOwnedByPlayer(sender.getUUID())) return;
        CompanionSpeech.setSpeaking(p.entityUuid(), p.speaking());
    }
}
