package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.brain.Replies;
import com.dwinovo.numen.api.network.Wire;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client → Server: 面板要一页历史。{@code cursor} 是上一页带回来的游标(原样带回,空串 = 最新一页)。答复是
 * {@link HistoryPagePayload},带着同一个 {@code requestId}。
 */
public record HistoryRequestPayload(long requestId, String conversation, String cursor, int limit)
        implements CustomPacketPayload {

    public static final Type<HistoryRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "history_request"));

    public static final StreamCodec<ByteBuf, HistoryRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, HistoryRequestPayload::requestId,
                    Wire.TO_SERVER.text(), HistoryRequestPayload::conversation,
                    Wire.TO_SERVER.text(), HistoryRequestPayload::cursor,
                    ByteBufCodecs.VAR_INT, HistoryRequestPayload::limit,
                    HistoryRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handler invoked on the server main thread; the page is computed on the companions' write lanes. */
    public static void handle(HistoryRequestPayload p, ServerPlayer player) {
        Replies.history(player, p.requestId(), p.conversation(), p.cursor(), p.limit());
    }
}
