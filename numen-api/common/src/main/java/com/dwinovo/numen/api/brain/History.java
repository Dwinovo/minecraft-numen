package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.agent.conversation.Transcript;
import com.dwinovo.numen.api.agent.llm.ConvoLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 一个会话的记录,按页往前翻。"这个会话发生了什么"是一个读取问题,只在这一处答:成员各自的日志里盖着这个会话印的行,按时间归并
 * ({@link Transcript}),主人的一句话在每个醒着的成员那儿各有一份副本,归并成一行。面板、外部大脑都从这里读,客户端不碰日志。
 *
 * <p>翻页的游标是服务端给的不透明串(各成员各自翻到了哪一行),客户端原样带回来;{@code null} 是最新一页。
 * 各成员的日志读走她自己那条写车道,排在前面的写都落下去之后才读,所以刚说的话一定在页里。
 */
public final class History {

    /** 一页的行数。 */
    public static final int PAGE = 60;

    /**
     * 一页。
     *
     * @param entries 按时间从老到新
     * @param cursor 再往前翻带回来的游标
     * @param more   再往前还有没有这个会话的行
     */
    public record Page(List<Transcript.Entry> entries, String cursor, boolean more) {}

    private History() {}

    /**
     * 取一页:{@code conversation} 的成员(此刻还在名册上、有大脑的)各读一段,归并,留最新的 {@code limit} 行。
     * 每一行说清是谁那本日志里的、各本日志里的行编号。
     */
    public static CompletableFuture<Page> page(Conversations rooms, Conversation conversation, String cursor,
                                               int limit) {
        String tag = rooms.tagOf(conversation);
        Map<UUID, String> from = readCursor(cursor);
        List<UUID> members = new ArrayList<>();
        Map<UUID, CompletableFuture<List<ConvoLog.Line>>> reads = new LinkedHashMap<>();
        for (UUID member : rooms.membersAlive(conversation)) {
            CompanionLoops.get(member).ifPresent(loop -> {
                members.add(member);
                reads.put(member, loop.read(() -> collect(loop.log(), from.get(member), tag, limit)));
            });
        }
        return CompletableFuture.allOf(reads.values().toArray(new CompletableFuture[0])).thenCompose(done -> {
            Map<UUID, List<ConvoLog.Line>> logs = new LinkedHashMap<>();
            reads.forEach((member, read) -> logs.put(member, read.join()));
            List<Transcript.Entry> merged = Transcript.merge(tag, logs);
            List<Transcript.Entry> chosen = merged.subList(Math.max(0, merged.size() - limit), merged.size());
            // 游标走到这一页里每本日志最老的那份:比它新的都在这一页里了;这一页里没有它的成员,游标不动
            Map<UUID, String> oldest = new HashMap<>();
            for (Transcript.Entry entry : chosen) {
                entry.ids().forEach(oldest::putIfAbsent);
            }
            Map<UUID, String> next = new HashMap<>(from);
            next.putAll(oldest);
            return probe(rooms, members, next, tag).thenApply(more -> new Page(
                    List.copyOf(chosen), writeCursor(next), more));
        });
    }

    /** 再往前还有没有这个会话的行:各成员从游标往前探一行。 */
    private static CompletableFuture<Boolean> probe(Conversations rooms, List<UUID> members, Map<UUID, String> cursor,
                                                    String tag) {
        List<CompletableFuture<Boolean>> probes = new ArrayList<>();
        for (UUID member : members) {
            CompanionLoops.get(member).ifPresent(loop ->
                    probes.add(loop.read(() -> !collect(loop.log(), cursor.get(member), tag, 1).isEmpty())));
        }
        return CompletableFuture.allOf(probes.toArray(new CompletableFuture[0]))
                .thenApply(done -> probes.stream().anyMatch(CompletableFuture::join));
    }

    /** 一本日志里从 {@code before} 往前、盖着 {@code tag} 印的最多 {@code limit} 行,从老到新。 */
    static List<ConvoLog.Line> collect(ConvoLog log, String before, String tag, int limit) {
        Deque<ConvoLog.Line> got = new ArrayDeque<>();
        String at = before;
        while (got.size() < limit) {
            ConvoLog.Page page = log.linesBefore(at, limit);
            if (page.lines().isEmpty()) {
                break;
            }
            for (int i = page.lines().size() - 1; i >= 0 && got.size() < limit; i--) {
                ConvoLog.Line line = page.lines().get(i);
                if (Objects.equals(line.conv(), tag)) {
                    got.addFirst(line);
                }
            }
            if (!page.more()) {
                break;
            }
            at = page.lines().get(0).id();
        }
        return new ArrayList<>(got);
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
