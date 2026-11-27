package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.agent.conversation.Transcript;
import com.dwinovo.numen.api.agent.llm.Rows;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 一个会话的记录,按页往前翻,在服务端读。"这个会话发生了什么"是一个读取问题,只在这一处答:成员各自的对话记录里盖着这个会话印的行,
 * 按时间归并({@link Transcript}),主人的一句话在每个醒着的成员那儿各有一份副本,归并成一行。面板、外部大脑都从这里读;单聊是成员
 * 只有她一个、没有会话印的会话。
 *
 * <p>翻页的游标是不透明串(各成员各自翻到了哪一行),客户端原样带回来;{@code null} 是最新一页。
 * 读排在各成员记录的写车道上,在它前面交的追加都落盘之后才读,所以刚说的话一定在页里。
 */
public final class History {

    /** 一页的行数。 */
    public static final int PAGE = 60;

    /**
     * 一页。
     *
     * @param entries 按时间从老到新
     * @param cursor  再往前翻带回来的游标
     * @param more    再往前还有没有这个会话的行
     * @param conversations 成员各自此刻在写哪一份会话:客户端据此认出她换了新的会话(清空上下文),把旧的时间线作废
     */
    public record Page(List<Transcript.Entry> entries, String cursor, boolean more, Map<UUID, UUID> conversations) {

        public JsonObject toJson() {
            JsonArray list = new JsonArray();
            for (Transcript.Entry entry : entries) {
                JsonObject o = new JsonObject();
                o.addProperty("ts", entry.ts());
                o.addProperty("companion", entry.companion().toString());
                JsonObject ids = new JsonObject();
                entry.ids().forEach((member, id) -> ids.addProperty(member.toString(), id));
                o.add("ids", ids);
                JsonArray rows = new JsonArray();
                entry.rows().forEach(rows::add);
                o.add("rows", rows);
                list.add(o);
            }
            JsonObject o = new JsonObject();
            o.add("entries", list);
            o.addProperty("cursor", cursor);
            o.addProperty("more", more);
            JsonObject now = new JsonObject();
            conversations.forEach((member, conversation) -> now.addProperty(member.toString(), conversation.toString()));
            o.add("conversations", now);
            return o;
        }

        public static Page fromJson(JsonObject o) {
            List<Transcript.Entry> entries = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("entries")) {
                JsonObject entry = e.getAsJsonObject();
                List<JsonObject> rows = new ArrayList<>();
                entry.getAsJsonArray("rows").forEach(r -> rows.add(r.getAsJsonObject()));
                Map<UUID, String> ids = new HashMap<>();
                entry.getAsJsonObject("ids").asMap().forEach((member, id) -> ids.put(UUID.fromString(member), id.getAsString()));
                // 一条的行读出来是一条消息:翻页时服务端只会把整条消息的行放在一起
                entries.add(new Transcript.Entry(entry.get("ts").getAsLong(), UUID.fromString(entry.get("companion").getAsString()),
                        Rows.lines(rows).getFirst().msg(), ids, rows));
            }
            Map<UUID, UUID> now = new LinkedHashMap<>();
            o.getAsJsonObject("conversations").asMap().forEach((member, conversation) ->
                    now.put(UUID.fromString(member), UUID.fromString(conversation.getAsString())));
            return new Page(List.copyOf(entries), o.get("cursor").getAsString(), o.get("more").getAsBoolean(), now);
        }
    }

    private History() {}

    /**
     * 取一页:这个会话的成员各读一段,归并,留最新的 {@code limit} 行。每一行说清是谁那份记录里的、各份记录里的行编号。
     *
     * @param members 成员与她们此刻的会话(文件),按成员表的顺序
     * @param tag     会话印;{@code null} = 就她一个的单聊
     */
    public static CompletableFuture<Page> page(TranscriptStore store, Map<UUID, UUID> members, String tag, String cursor,
                                               int limit) {
        Map<UUID, String> from = readCursor(cursor);
        Map<UUID, CompletableFuture<List<Rows.Line>>> reads = new LinkedHashMap<>();
        members.forEach((member, conversation) -> reads.put(member,
                store.read(conversation, rows -> Rows.lines(rows).stream().filter(l -> Objects.equals(l.conv(), tag)).toList())));
        return CompletableFuture.allOf(reads.values().toArray(new CompletableFuture[0])).thenApply(done -> {
            Map<UUID, List<Rows.Line>> whole = new LinkedHashMap<>();
            reads.forEach((member, read) -> whole.put(member, read.join()));
            Map<UUID, List<Rows.Line>> older = new LinkedHashMap<>();
            whole.forEach((member, lines) -> older.put(member, before(lines, from.get(member), limit)));
            List<Transcript.Entry> merged = Transcript.merge(tag, older);
            List<Transcript.Entry> chosen = merged.subList(Math.max(0, merged.size() - limit), merged.size());
            // 游标走到这一页里每份记录最老的那一行:比它新的都在这一页里了;这一页里没有她的成员,游标不动
            Map<UUID, String> oldest = new HashMap<>();
            for (Transcript.Entry entry : chosen) {
                entry.ids().forEach(oldest::putIfAbsent);
            }
            Map<UUID, String> next = new HashMap<>(from);
            next.putAll(oldest);
            boolean more = whole.entrySet().stream().anyMatch(e -> !before(e.getValue(), next.get(e.getKey()), 1).isEmpty());
            return new Page(List.copyOf(chosen), writeCursor(next), more, members);
        });
    }

    /** 一份记录里 {@code beforeId} 那一行之前的最新 {@code limit} 行,从老到新;{@code beforeId} 为 null 是从最新起;游标找不到当从最新起。 */
    private static List<Rows.Line> before(List<Rows.Line> lines, String beforeId, int limit) {
        int end = lines.size();
        if (beforeId != null) {
            for (int i = 0; i < lines.size(); i++) {
                if (beforeId.equals(lines.get(i).id())) {
                    end = i;
                    break;
                }
            }
        }
        return lines.subList(Math.max(0, end - limit), end);
    }

    // ---- 游标 ----

    private static Map<UUID, String> readCursor(String cursor) {
        Map<UUID, String> out = new HashMap<>();
        if (cursor == null || cursor.isBlank()) {
            return out;
        }
        try {
            for (Map.Entry<String, JsonElement> e : JsonParser.parseString(cursor).getAsJsonObject().entrySet()) {
                out.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
            }
        } catch (RuntimeException unreadable) {
            out.clear();   // 读不懂的游标从最新一页重来:客户端的状态坏了,不该替它猜
        }
        return out;
    }

    private static String writeCursor(Map<UUID, String> cursor) {
        JsonObject o = new JsonObject();
        cursor.forEach((member, id) -> o.addProperty(member.toString(), id));
        return o.toString();
    }
}
