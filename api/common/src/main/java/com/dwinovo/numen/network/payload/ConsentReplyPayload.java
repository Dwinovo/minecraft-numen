package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentDesk;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client → Server:主人在答复框上选的那一项,和可选的一句附言。
 *
 * <h2>信任模型</h2>
 * 与 {@link ExecuteToolPayload} 同一条:目标必须是同伴(跨维度查找),发送者必须是它的主人。
 * 认主人、对请求号都在 {@link ConsentDesk#reply}(与 {@code /numen consent} 命令同一个入口),这里只转交。
 *
 * @param companion 哪只同伴
 * @param id        答的是哪一条请求
 * @param decision  允许 / 允许并记住 / 拒绝
 * @param note      附言;空串 = 没说
 */
public record ConsentReplyPayload(UUID companion, long id, ConsentAnswer.Decision decision, String note)
        implements CustomPacketPayload {

    public static final int MAX_NOTE_LENGTH = 512;

    public static final Type<ConsentReplyPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "consent_reply"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConsentReplyPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ConsentReplyPayload::companion,
                    ByteBufCodecs.VAR_LONG, ConsentReplyPayload::id,
                    ByteBufCodecs.idMapper(i -> ConsentAnswer.Decision.values()[i], Enum::ordinal),
                    ConsentReplyPayload::decision,
                    ByteBufCodecs.stringUtf8(MAX_NOTE_LENGTH), ConsentReplyPayload::note,
                    ConsentReplyPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handler invoked on the server main thread. */
    public static void handle(ConsentReplyPayload p, ServerPlayer player) {
        NumenPlayer companion = NumenPlayer.findByUuid(player.level().getServer(), p.companion());
        if (companion == null) {
            Constants.LOG.debug("[numen-net] consent_reply for unknown companion {}", p.companion());
            return;
        }
        switch (ConsentDesk.reply(player, companion, p.id(), p.decision(), p.note())) {
            case NOT_OWNER -> Constants.LOG.warn("[numen-net] ✗ consent_reply rejected from {}: not the owner",
                    player.getName().getString());
            case NOT_PENDING -> Constants.LOG.info("[numen-net] consent_reply #{} for {} no longer pending",
                    p.id(), p.companion());
            case ANSWERED -> { }
        }
    }
}
