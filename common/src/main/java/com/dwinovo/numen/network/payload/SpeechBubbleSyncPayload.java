package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server → Client:某个同伴的头顶气泡状态(见 {@link SpeechBubblePayload}
 * 的上行说明)。收到的客户端不一定是主人——附近路过的玩家同样收到,
 * 同伴说话路人也听得见。
 */
public record SpeechBubbleSyncPayload(UUID entityUuid, byte kind, String text) implements CustomPacketPayload {

    public static final Type<SpeechBubbleSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "speech_bubble_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpeechBubbleSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, SpeechBubbleSyncPayload::entityUuid,
                    ByteBufCodecs.BYTE, SpeechBubbleSyncPayload::kind,
                    ByteBufCodecs.stringUtf8(SpeechBubblePayload.MAX_TEXT), SpeechBubbleSyncPayload::text,
                    SpeechBubbleSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client main thread. */
    public static void handle(SpeechBubbleSyncPayload p) {
        com.dwinovo.numen.client.hud.SpeechBubbles.apply(p.entityUuid(), p.kind(), p.text());
    }
}
