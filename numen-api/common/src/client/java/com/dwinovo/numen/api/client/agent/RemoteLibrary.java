package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.network.payload.LibraryPayload;
import com.dwinovo.numen.api.network.payload.LibraryRequestPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

    public RemoteLibrary(Consumer<LibraryRequestPayload> send) {
        this.send = send;
    }

    /** 这种库最近一次读到的整表,盖着删了还没回真表的行;还没读过是空表。 */
    public JsonArray entries(String kind) {
        JsonArray out = new JsonArray();
        List<JsonElement> table = tables.get(kind);
        if (table != null) {
            pending.computeIfAbsent(kind, k -> new PendingChanges<>())
                    .over(table, row -> row.getAsJsonObject().get("id").getAsString())
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

    /** 这种库的一个专有操作(接上、断开、重扫、交回授权网址……),能不能办由服务端判。 */
    public void request(String kind, String op, JsonObject body) {
        send.accept(new LibraryRequestPayload(kind, op, body.toString()));
    }

    /** 删一条;这一行马上从表里消失,服务端的真表一到以它为准。 */
    public void delete(String kind, String id) {
        pending.computeIfAbsent(kind, k -> new PendingChanges<>()).put(id, null);
        edits.merge(kind, 1L, Long::sum);
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        send.accept(new LibraryRequestPayload(kind, "delete", body.toString()));
    }

    /** 服务端推来一张表。 */
    public void onPayload(LibraryPayload p) {
        tables.put(p.kind(), List.copyOf(JsonParser.parseString(p.entries()).getAsJsonArray().asList()));
        revisions.merge(p.kind(), 1L, Long::sum);
        if (p.problem().isEmpty()) {
            problems.remove(p.kind());
        } else {
            problems.put(p.kind(), p.problem());
        }
    }

    /** 断线:服务器上的东西都不算数了。 */
    public void clear() {
        tables.clear();
        pending.clear();
        problems.clear();
        revisions.clear();
        edits.clear();
    }
}
