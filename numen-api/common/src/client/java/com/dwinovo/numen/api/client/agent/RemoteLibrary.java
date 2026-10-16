package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.network.payload.LibraryPayload;
import com.dwinovo.numen.api.network.payload.LibraryRequestPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 服务器上那几种库(人设、模型档案、服务器配置)在客户端的视图:最近一次服务端说的那张整表,和最近一次请求没办成的原因。
 * 内存里的,不落盘、不是副本——真源在服务器,关了就丢,界面打开时再要。存和删不在这里办,只变成请求发给服务端,
 * 能不能由它判;办成之后它把新表推回来,视图才变。
 */
public final class RemoteLibrary {

    private final Consumer<LibraryRequestPayload> send;
    private final Map<String, JsonArray> entries = new HashMap<>();
    private final Map<String, String> problems = new HashMap<>();

    public RemoteLibrary(Consumer<LibraryRequestPayload> send) {
        this.send = send;
    }

    /** 这种库最近一次读到的整表;还没读过是空表。 */
    public JsonArray entries(String kind) {
        return entries.getOrDefault(kind, new JsonArray());
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

    /** 删一条。 */
    public void delete(String kind, String id) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        send.accept(new LibraryRequestPayload(kind, "delete", body.toString()));
    }

    /** 服务端推来一张表。 */
    public void onPayload(LibraryPayload p) {
        entries.put(p.kind(), JsonParser.parseString(p.entries()).getAsJsonArray());
        if (p.problem().isEmpty()) {
            problems.remove(p.kind());
        } else {
            problems.put(p.kind(), p.problem());
        }
    }

    /** 断线:服务器上的东西都不算数了。 */
    public void clear() {
        entries.clear();
        problems.clear();
    }
}
