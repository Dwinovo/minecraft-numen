package com.dwinovo.numen.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/**
 * 假的 OpenAI 兼容端点(chat completions,流式):服务端的模型档案(种子里那份内置档案)指向它,她要回话就来问这里。
 * 回什么由场景事先排好——产品里走的是真的 HTTP 传输、真的档案解析、真的钥匙串,只是对面不是大模型。
 *
 * <p>一个脚本({@link #script})是"一个触发条件加一串依次的回复"。触发条件看到一次新的请求(最后一条是人说的话)满足,
 * 脚本就开始,回第一条;她跑完工具带着回执回来问时(最后一条是工具结果),接着回脚本的下一条。别的请求(例如事件唤醒)
 * 不吃脚本的步子;没有任何脚本接得住的请求回一句默认的话——场景只断言自己关心的那句话,默认回复是背景。
 *
 * <p>线程:HTTP 处理线程与场景线程并用,状态都在同一把锁里。
 */
final class FakeModel implements AutoCloseable {

    /** 模型的一次回复。 */
    sealed interface Reply {
        /** 一句话,回合到此为止。 */
        record Say(String text) implements Reply {}

        /** 派一段 Lua 去跑,回执回来再问一次。 */
        record Lua(String code) implements Reply {}
    }

    static Reply say(String text) {
        return new Reply.Say(text);
    }

    static Reply lua(String code) {
        return new Reply.Lua(code);
    }

    /** 一次请求里场景关心的部分。 */
    record Asked(String lastRole, String lastText, String lastToolCallId, String body) {
        boolean fromHuman() {
            return lastRole.equals("user") && lastToolCallId == null;
        }
    }

    private static final String DEFAULT_REPLY = "收到。";

    private final HttpServer http;
    private final String key;
    private final List<Asked> asked = new ArrayList<>();
    private final List<Script> scripts = new ArrayList<>();
    private int calls;

    FakeModel(String host, int port, String key) throws IOException {
        this.key = key;
        this.http = HttpServer.create(new InetSocketAddress(host, port), 0);
        http.createContext("/v1/chat/completions", this::handle);
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "e2e-fake-model");
            t.setDaemon(true);
            return t;
        }));
        http.start();
    }

    private static final class Script {
        final Predicate<Asked> trigger;
        final Deque<Reply> replies;
        boolean started;
        /** 脚本最近派出去的那次工具调用的号;回执带着这个号回来,才是它的下一步。 */
        String awaiting;

        Script(Predicate<Asked> trigger, List<Reply> replies) {
            this.trigger = trigger;
            this.replies = new ArrayDeque<>(replies);
        }
    }

    /** 下一次满足 {@code trigger} 的新请求开始这个脚本,依次回 {@code replies}。 */
    synchronized void script(Predicate<Asked> trigger, Reply... replies) {
        scripts.add(new Script(trigger, List.of(replies)));
    }

    /** 人说的话里带这段文字就触发。 */
    synchronized void whenHeard(String text, Reply... replies) {
        script(asked -> asked.fromHuman() && asked.lastText().contains(text), replies);
    }

    /** 到目前为止收到的全部请求。 */
    synchronized List<Asked> asked() {
        return List.copyOf(asked);
    }

    /** 收到过一条人说的话里带这段文字的请求吗。 */
    synchronized boolean wasToldAbout(String text) {
        return asked.stream().anyMatch(a -> a.fromHuman() && a.lastText().contains(text));
    }

    /** 清空预设与记录——每个场景开头做一次。 */
    synchronized void reset() {
        scripts.clear();
        asked.clear();
    }

    /** 这次请求该回什么;{@code callId} 是回复里工具调用的号(只有 Lua 回复用)。 */
    private record Chosen(Reply reply, String callId) {}

    private synchronized Chosen next(Asked request) {
        asked.add(request);
        for (Script script : scripts) {
            boolean continuation = script.awaiting != null && script.awaiting.equals(request.lastToolCallId());
            boolean begins = !script.started && request.fromHuman() && script.trigger.test(request);
            if ((continuation || begins) && !script.replies.isEmpty()) {
                script.started = true;
                Reply reply = script.replies.poll();
                script.awaiting = reply instanceof Reply.Lua ? "call_e2e_" + (++calls) : null;
                return new Chosen(reply, script.awaiting);
            }
        }
        return new Chosen(say(DEFAULT_REPLY), null);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!("Bearer " + key).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                // 档案用的 key 必须是钥匙串里那把:传错了就是产品里 key 的出处坏了
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Chosen chosen = next(read(body));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (JsonObject chunk : chunks(chosen)) {
                    out.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static Asked read(String body) {
        JsonArray messages = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("messages");
        JsonObject last = messages.get(messages.size() - 1).getAsJsonObject();
        String toolCallId = null;
        for (JsonElement message : messages) {
            JsonObject m = message.getAsJsonObject();
            if (m.get("role").getAsString().equals("tool") && m.has("tool_call_id")) {
                toolCallId = m.get("tool_call_id").getAsString();
            } else if (m.get("role").getAsString().equals("assistant")) {
                toolCallId = null;
            }
        }
        return new Asked(last.get("role").getAsString(), text(last.get("content")), toolCallId, body);
    }

    /** content 是字符串,或一组 {type:text,text:…}。 */
    private static String text(JsonElement content) {
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        StringBuilder out = new StringBuilder();
        for (JsonElement part : content.getAsJsonArray()) {
            JsonObject object = part.getAsJsonObject();
            if (object.has("text")) {
                out.append(object.get("text").getAsString());
            }
        }
        return out.toString();
    }

    private static List<JsonObject> chunks(Chosen chosen) {
        JsonObject delta = new JsonObject();
        delta.addProperty("role", "assistant");
        String finish;
        switch (chosen.reply()) {
            case Reply.Say say -> {
                delta.addProperty("content", say.text());
                finish = "stop";
            }
            case Reply.Lua lua -> {
                JsonObject arguments = new JsonObject();
                arguments.addProperty("code", lua.code());
                JsonObject function = new JsonObject();
                function.addProperty("name", "lua");
                function.addProperty("arguments", arguments.toString());
                JsonObject call = new JsonObject();
                call.addProperty("index", 0);
                call.addProperty("id", chosen.callId());
                call.addProperty("type", "function");
                call.add("function", function);
                JsonArray calls = new JsonArray();
                calls.add(call);
                delta.add("tool_calls", calls);
                finish = "tool_calls";
            }
        }
        JsonObject usage = new JsonObject();
        usage.addProperty("prompt_tokens", 10);
        usage.addProperty("completion_tokens", 5);
        usage.addProperty("total_tokens", 15);
        JsonObject tail = new JsonObject();
        tail.add("choices", new JsonArray());
        tail.add("usage", usage);
        return List.of(chunk(delta, null), chunk(new JsonObject(), finish), tail);
    }

    private static JsonObject chunk(JsonObject delta, String finish) {
        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0);
        choice.add("delta", delta);
        if (finish == null) {
            choice.add("finish_reason", com.google.gson.JsonNull.INSTANCE);
        } else {
            choice.addProperty("finish_reason", finish);
        }
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject out = new JsonObject();
        out.addProperty("object", "chat.completion.chunk");
        out.add("choices", choices);
        return out;
    }

    @Override
    public void close() {
        http.stop(0);
    }
}
