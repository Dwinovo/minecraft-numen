package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.network.NumenPayload;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentDesk;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client → Server:主人在答复框上选的那一项;拒绝可以带一句附言。
 *
 * <h2>信任模型</h2>
 * 与 {@link RunProgramPayload} 同一条:目标必须是同伴(跨维度查找),发送者必须是它的主人。
 * 认主人、对请求号都在 {@link ConsentDesk#reply}(与 {@code /numen consent} 命令同一个入口),这里只转交。
 *
 * @param companion 哪只同伴
 * @param requestId 答的是哪一条请求
 * @param decision  允许 / 允许并记住 / 拒绝
 * @param note      附言,只有拒绝带;空串 = 没说
 */
public record ConsentReplyPayload(UUID companion, long requestId, ConsentAnswer.Decision decision, String note)
        implements NumenPayload {

    public static final int MAX_NOTE_LENGTH = 512;

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "consent_reply");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(companion);
        buf.writeVarLong(requestId);
        buf.writeEnum(decision);
        buf.writeUtf(note, MAX_NOTE_LENGTH);
    }

    public static ConsentReplyPayload read(FriendlyByteBuf buf) {
        return new ConsentReplyPayload(buf.readUUID(),
                buf.readVarLong(),
                buf.readEnum(ConsentAnswer.Decision.class),
                buf.readUtf(MAX_NOTE_LENGTH));
    }

    /** Handler invoked on the server main thread. */
    public static void handle(ConsentReplyPayload p, ServerPlayer player) {
        NumenPlayer companion = NumenPlayer.findByUuid(player.level().getServer(), p.companion());
        if (companion == null) {
            Constants.LOG.debug("[numen-net] consent_reply for unknown companion {}", p.companion());
            return;
        }
        if (p.decision() != ConsentAnswer.Decision.DENY && !p.note().isBlank()) {
            Constants.LOG.warn("[numen-net] ✗ consent_reply rejected from {}: a note only goes with a deny",
                    player.getName().getString());
            return;
        }
        switch (ConsentDesk.reply(player, companion, p.requestId(), p.decision(), p.note())) {
            case NOT_OWNER -> Constants.LOG.warn("[numen-net] ✗ consent_reply rejected from {}: not the owner",
                    player.getName().getString());
            case NOT_PENDING -> Constants.LOG.info("[numen-net] consent_reply #{} for {} no longer pending",
                    p.requestId(), p.companion());
            case ANSWERED -> { }
        }
    }
}
