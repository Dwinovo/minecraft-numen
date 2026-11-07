package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.conversation.Transcript;
import com.dwinovo.numen.api.agent.llm.ConvoLog;
import com.dwinovo.numen.api.brain.History;
import com.dwinovo.numen.api.network.ClientPayloadSink;
import com.dwinovo.numen.api.network.Wire;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server → Client: 一页历史,答 {@link HistoryRequestPayload}。行按时间从老到新({@code ConvoLog.Line} 的 JSON);
 * {@code cursor} 是再往前翻带回来的游标;{@code more} 说再往前还有没有。一页的行里工具结果可能很长,所以可分片。
 */
public record HistoryPagePayload(long requestId, String conversation, String entriesJson, String cursor, boolean more)
        implements CustomPacketPayload, Wire.Fragmentable {

    public static final Type<HistoryPagePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "history_page"));

    public static final StreamCodec<ByteBuf, HistoryPagePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, HistoryPagePayload::requestId,
                    Wire.TO_CLIENT.text(), HistoryPagePayload::conversation,
                    Wire.TO_CLIENT.text(), HistoryPagePayload::entriesJson,
                    Wire.TO_CLIENT.text(), HistoryPagePayload::cursor,
                    ByteBufCodecs.BOOL, HistoryPagePayload::more,
                    HistoryPagePayload::new);

    public static HistoryPagePayload of(long requestId, String conversation, History.Page page) {
        JsonArray entries = new JsonArray();
        for (Transcript.Entry entry : page.entries()) {
            // 哪一行是谁那本日志里的:行编号是那一份的,群里别人的副本不用上线
            JsonObject o = ConvoLog.toJson(new ConvoLog.Line(entry.ids().get(entry.companion()), entry.ts(), null,
                    entry.msg()));
            o.addProperty("companion", entry.companion().toString());
            entries.add(o);
        }
        return new HistoryPagePayload(requestId, conversation, entries.toString(), page.cursor(), page.more());
    }

    /** 读出这一页的行;读不懂的跳过。 */
    public List<Transcript.Entry> entries() {
        List<Transcript.Entry> out = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(entriesJson).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            ConvoLog.Line line = ConvoLog.lineFromJson(o);
            if (line != null) {
                UUID companion = UUID.fromString(o.get("companion").getAsString());
                out.add(new Transcript.Entry(line.ts(), companion, line.msg(), Map.of(companion, line.id())));
            }
        }
        return out;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client main thread. */
    public static void handle(HistoryPagePayload p) {
        ClientPayloadSink.historyPage.accept(p);
    }
}
