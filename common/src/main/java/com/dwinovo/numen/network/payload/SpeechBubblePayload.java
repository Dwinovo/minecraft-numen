package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.platform.Services;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client → Server:同伴的头顶气泡状态(大脑在主人客户端,气泡要让附近
 * 所有玩家看见就得经服务端转发)。服务端校验主人身份后,把同一份内容
 * 以 {@link SpeechBubbleSyncPayload} 广播给同伴附近的玩家(含主人本人
 * ——主人也走这一条路,不做本地特例)。
 */
public record SpeechBubblePayload(UUID entityUuid, byte kind, String text) implements CustomPacketPayload {

    /** 收起一切(回合失败/被打断/空回复)。 */
    public static final byte KIND_CLEAR = 0;
    /** 思考中:省略号等待泡。客户端规则:活着的正文泡优先,不被它顶掉。 */
    public static final byte KIND_THINKING = 1;
    /** 正文:一句话浮在头顶,按长度限时消失;顶掉一切旧泡。 */
    public static final byte KIND_TEXT = 2;
    /** 落定(开工干活):只收思考泡,正文泡留着走完自己的生命周期。 */
    public static final byte KIND_SETTLE = 3;

    /** 气泡是预览,不是全文载体——全文在聊天栏与 G 面板。 */
    public static final int MAX_TEXT = 512;

    /** 气泡可见半径(方块),与名牌可视距离同量级。 */
    private static final double RADIUS_SQ = 64.0 * 64.0;

    public static final Type<SpeechBubblePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "speech_bubble"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpeechBubblePayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, SpeechBubblePayload::entityUuid,
                    ByteBufCodecs.BYTE, SpeechBubblePayload::kind,
                    ByteBufCodecs.stringUtf8(MAX_TEXT), SpeechBubblePayload::text,
                    SpeechBubblePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server main thread. 只认主人本人发来的气泡,广播给同伴附近的玩家。 */
    public static void handle(SpeechBubblePayload p, ServerPlayer sender) {
        var companion = com.dwinovo.numen.entity.NumenPlayer.findByUuid(
                sender.level().getServer(), p.entityUuid());
        if (companion == null || !companion.isOwnedByPlayer(sender.getUUID())) return;
        var sync = new SpeechBubbleSyncPayload(p.entityUuid(), p.kind(), p.text());
        for (ServerPlayer viewer : companion.serverLevel().players()) {
            if (viewer.distanceToSqr(companion) > RADIUS_SQ) continue;
            try {
                Services.NETWORK.sendToPlayer(viewer, sync);
            } catch (Exception e) {
                // 混装服上没带本模组的客户端:发不过去就跳过,气泡不是刚需
                Constants.LOG.debug("speech bubble not sent to {}: {}",
                        viewer.getScoreboardName(), e.toString());
            }
        }
    }
}
