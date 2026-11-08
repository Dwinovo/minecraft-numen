package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.network.NumenPayload;
import net.minecraft.network.FriendlyByteBuf;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.brain.Replies;
import com.dwinovo.numen.api.network.Wire;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client → Server: 面板要一页历史。{@code cursor} 是上一页带回来的游标(原样带回,空串 = 最新一页)。答复是
 * {@link HistoryPagePayload},带着同一个 {@code requestId}。
 */
public record HistoryRequestPayload(long requestId, String conversation, String cursor, int limit)
        implements NumenPayload {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "history_request");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarLong(requestId);
        Wire.writeText(buf, conversation);
        Wire.writeText(buf, cursor);
        buf.writeVarInt(limit);
    }

    public static HistoryRequestPayload read(FriendlyByteBuf buf) {
        return new HistoryRequestPayload(buf.readVarLong(), Wire.readText(buf), Wire.readText(buf), buf.readVarInt());
    }

    /** Handler invoked on the server main thread; the page is computed on the companions' write lanes. */
    public static void handle(HistoryRequestPayload p, ServerPlayer player) {
        Replies.history(player, p.requestId(), p.conversation(), p.cursor(), p.limit());
    }
}
