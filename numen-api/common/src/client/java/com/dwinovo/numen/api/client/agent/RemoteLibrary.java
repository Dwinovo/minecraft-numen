package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.network.payload.LibraryPayload;
import com.dwinovo.numen.api.network.payload.LibraryRequestPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 服务器上那几种库(人设、模型档案、服务器配置)在客户端的视图:最近一次服务端说的那张整表,和最近一次请求没办成的原因。
 * 内存里的,不落盘、不是副本——真源在服务器,关了就丢,界面打开时再要。存和删不在这里办,只变成请求发给服务端,
 * 能不能由它判;办成之后它把新表推回来,视图才变。删是例外:那一行先画成没了({@link PendingChanges}),真表一到以真表为准。
 */
public final class RemoteLibrary {

    private final Consumer<LibraryRequestPayload> send;
    private final Map<String, List<JsonElement>> tables = new HashMap<>();
    /** 删了、服务端还没回真表的行,按库分:先画成办成,真表一到以真表为准。 */
    private final Map<String, PendingChanges<JsonElement>> pending = new HashMap<>();
    private final Map<String, String> problems = new HashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();
    private final Map<String, Long> edits = new HashMap<>();
    /** 等答复的请求:请求号 → 发请求的那一处。答复一到(带着同一个号)交过去、摘掉。 */
    private final Map<Long, Consumer<Reply>> waiting = new HashMap<>();
    private long nextRequest = 1;

    /** 服务端对一次请求的答复:没办成的原因(办成是 null),和新建成功时新条目的 id(其余是 null)。 */
    public record Reply(String problem, String created) {

        public boolean ok() {
            return problem == null;
        }
    }

    public RemoteLibrary(Consumer<LibraryRequestPayload> send) {
        this.send = send;
    }

    /** 这种库最近一次读到的整表,盖着删了还没回真表的行;还没读过是空表。 */
    public JsonArray entries(String kind) {
        JsonArray out = new JsonArray();
        List<JsonElement> table = tables.get(kind);
        if (table != null) {
            pending.computeIfAbsent(kind, k -> new PendingChanges<>())
                    .over(table, RemoteLibrary::rowKey)
                    .forEach(out::add);
        }
        return out;
    }

    /** 这种库在客户端的版本:服务端推来一张表、或这里先把删了的行画掉,都会变;界面据此知道要不要重读。 */
    public long version(String kind) {
        return revision(kind) + edits.getOrDefault(kind, 0L);
    }

    /** 这种库的表被服务端推来过几次——界面发了请求等回执时,看它变没变。 */
    public long revision(String kind) {
        return revisions.getOrDefault(kind, 0L);
    }

    /** 最近一次请求没办成的原因;办成了(或没请求过)是 {@code null}。 */
    public String problem(String kind) {
        return problems.get(kind);
    }

    /** 要最新的整表(打开页面时)。 */
    public void ask(String kind) {
        send.accept(new LibraryRequestPayload(kind, "list", "{}"));
    }

    /** 存一条(新建或改);办成之后服务端把新表推回来。 */
    public void put(String kind, JsonObject entry) {
        send.accept(new LibraryRequestPayload(kind, "put", entry.toString()));
    }

    /**
     * 存一条并等它的答复:{@code onReply} 在服务端答这一次请求时(只答这一次,推送不算)调一次,这时表已经是答复里的那张。
     */
    public void put(String kind, JsonObject entry, Consumer<Reply> onReply) {
        request(kind, "put", entry, onReply);
    }

    /** 这种库的一个专有操作,并等它的答复(见 {@link #put(String, JsonObject, Consumer)})。 */
    public void request(String kind, String op, JsonObject body, Consumer<Reply> onReply) {
        long id = nextRequest++;
        waiting.put(id, onReply);
        send.accept(new LibraryRequestPayload(kind, op, body.toString(), id));
    }

    /** 这种库的一个专有操作(接上、断开、重扫、交回授权网址……),能不能办由服务端判。 */
    public void request(String kind, String op, JsonObject body) {
        send.accept(new LibraryRequestPayload(kind, op, body.toString()));
    }

    /**
     * 删一条;这一行马上从表里消失,服务端的真表一到以它为准。
     *
     * @param builtin 删的是内置组里的那一条(只有管理员办得成);默认删我的
     */
    public void delete(String kind, String id, boolean builtin) {
        pending.computeIfAbsent(kind, k -> new PendingChanges<>()).put(rowKey(id, builtin), null);
        edits.merge(kind, 1L, Long::sum);
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        if (builtin) {
            body.addProperty("group", "builtin");
        }
        send.accept(new LibraryRequestPayload(kind, "delete", body.toString()));
    }

    /** 内置与我的可以有同名的两行,所以一行的身份是名字加组。 */
    private static String rowKey(String id, boolean builtin) {
        return (builtin ? "builtin:" : "mine:") + id;
    }

    private static String rowKey(JsonElement row) {
        JsonObject o = row.getAsJsonObject();
        return rowKey(o.get("id").getAsString(), o.has("group") && "builtin".equals(o.get("group").getAsString()));
    }

    /** 服务端推来一张表。 */
    public void onPayload(LibraryPayload p) {
        // 新建成功的答复在表后附一行 {"created": id}:它不是表里的一行,取出来交给发请求的那一处
        List<JsonElement> rows = new ArrayList<>();
        String created = null;
        for (JsonElement row : JsonParser.parseString(p.entries()).getAsJsonArray()) {
            if (row.isJsonObject() && row.getAsJsonObject().has(Libraries.CREATED)) {
                created = row.getAsJsonObject().get(Libraries.CREATED).getAsString();
            } else {
                rows.add(row);
            }
        }
        tables.put(p.kind(), List.copyOf(rows));
        revisions.merge(p.kind(), 1L, Long::sum);
        if (p.problem().isEmpty()) {
            problems.remove(p.kind());
        } else {
            problems.put(p.kind(), p.problem());
        }
        Consumer<Reply> asked = p.request() == 0 ? null : waiting.remove(p.request());
        if (asked != null) {
            asked.accept(new Reply(p.problem().isEmpty() ? null : p.problem(), created));
        }
    }

    /** 断线:服务器上的东西都不算数了。 */
    public void clear() {
        tables.clear();
        pending.clear();
        problems.clear();
        revisions.clear();
        edits.clear();
        waiting.clear();
    }
}
