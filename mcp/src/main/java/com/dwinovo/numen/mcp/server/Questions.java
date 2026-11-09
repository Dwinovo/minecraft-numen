package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.mcp.protocol.ElicitRequest;
import com.dwinovo.numen.mcp.protocol.ElicitResult;
import com.dwinovo.numen.mcp.protocol.McpCodec;
import com.dwinovo.numen.mcp.protocol.McpMethods;
import com.dwinovo.numen.mcp.protocol.ProtocolVersion;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.dwinovo.numen.mcp.rpc.RpcPeer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 替宿主问坐在客户端前的人({@link McpEndpoint#ask}):照规范,与客户端哪个请求都无关的服务端请求走客户端开的 GET 流,所以问题发给
 * 这位主人某张作用域管着那个对象的大脑令牌、会答表单式 elicitation、开着 GET 流的会话({@link ServerSession#canAsk})。
 *
 * <ul>
 *   <li>此刻没有能问的会话,问题先挂着;有会话开了流(或握手做完)再试({@link #deliverAll})。没有哪个客户端答得了,它就一直挂到宿主撤掉
 *       (主人进游戏答,或到期)。</li>
 *   <li>问出去以后宿主撤掉它({@link #withdraw}):照规范发 {@code notifications/cancelled},客户端收起弹窗。</li>
 *   <li>问出去的那条流断了、或客户端答了个 JSON-RPC 错误:问题放回去挂着,等下一次有流再问(不当场重发,免得对着同一个出错的客户端打转)。</li>
 * </ul>
 *
 * <p>线程:任何线程。人的答复在收到它的 HTTP 线程上交给宿主。
 */
final class Questions {

    private static final Logger LOG = LoggerFactory.getLogger("Numen");

    private record Waiting(long id, UUID owner, UUID subject, McpQuestion question, Consumer<McpQuestion.Answer> answered) {}

    /** 问出去、还没答回来的一条。 */
    private record Out(Waiting waiting, ServerSession session, RpcPeer.Outgoing request) {}

    private final McpPresence presence;
    private final Function<String, List<ServerSession>> sessionsOf;
    private final Map<Long, Waiting> waiting = new ConcurrentHashMap<>();
    private final Map<Long, Out> out = new ConcurrentHashMap<>();
    /** 问出去、答复还没交给宿主的有几条。 */
    private final AtomicInteger asking = new AtomicInteger();

    /** @param sessionsOf 一张令牌(编号)此刻开着的会话 */
    Questions(McpPresence presence, Function<String, List<ServerSession>> sessionsOf) {
        this.presence = presence;
        this.sessionsOf = sessionsOf;
    }

    void ask(long id, UUID owner, UUID subject, McpQuestion question, Consumer<McpQuestion.Answer> answered) {
        waiting.put(id, new Waiting(id, owner, subject, question, answered));
        deliver(id);
    }

    void withdraw(long id) {
        waiting.remove(id);
        Out asked = out.remove(id);
        if (asked != null) {
            Stream stream = asked.session.listening();
            if (stream != null) {
                asked.session.peer.cancel(asked.request, "the question was answered elsewhere or expired", stream);
            } else {
                asked.request.result().cancel(false);
            }
        }
    }

    int asking() {
        return asking.get();
    }

    /** 有会话变得能问了:挂着的都试一遍。 */
    void deliverAll() {
        for (Long id : List.copyOf(waiting.keySet())) {
            deliver(id);
        }
    }

    private void deliver(long id) {
        Waiting w = waiting.get(id);
        if (w == null) {
            return;
        }
        for (McpToken brain : presence.brains(w.owner)) {
            if (!brain.scope().covers(w.subject)) {
                continue;
            }
            for (ServerSession session : sessionsOf.apply(brain.id())) {
                Stream stream = session.listening();
                if (!session.canAsk() || stream == null || !waiting.remove(id, w)) {
                    continue;
                }
                send(w, session, stream);
                return;
            }
        }
    }

    private void send(Waiting w, ServerSession session, Stream stream) {
        asking.incrementAndGet();
        RpcPeer.Outgoing request = session.peer.request(McpMethods.ELICIT,
                McpCodec.elicitRequest(session.version, new ElicitRequest(w.question.message(), form(session.version, w.question))),
                stream);
        Out asked = new Out(w, session, request);
        out.put(w.id, asked);
        request.result().whenComplete((result, error) -> {
            try {
                if (!out.remove(w.id, asked)) {
                    return;   // 撤掉了
                }
                if (error == null) {
                    w.answered.accept(answerOf(McpCodec.elicitResult(result)));
                } else if (!(unwrap(error) instanceof CancellationException)) {
                    LOG.debug("[numen-mcp] question #{} not answered at {}, kept for the next stream: {}", w.id, session.client,
                            unwrap(error).toString());
                    waiting.putIfAbsent(w.id, w);
                }
            } catch (RpcException malformed) {
                LOG.warn("[numen-mcp] {} answered question #{} with a malformed result: {}", session.client, w.id,
                        malformed.getMessage());
                waiting.putIfAbsent(w.id, w);
            } finally {
                asking.decrementAndGet();
            }
        });
    }

    /**
     * 选择题的表单:一栏带标题的单选,一栏可选的附言。带标题的单选 2025-11-25 写成 {@code oneOf} 的 {@code const}/{@code title},
     * 2025-06-18 写成 {@code enum} 加 {@code enumNames}。
     */
    private static JsonObject form(ProtocolVersion version, McpQuestion q) {
        JsonObject decision = new JsonObject();
        decision.addProperty("type", "string");
        decision.addProperty("title", q.choiceTitle());
        if (version.titledEnumsAsOneOf()) {
            JsonArray options = new JsonArray();
            for (McpQuestion.Choice c : q.choices()) {
                JsonObject option = new JsonObject();
                option.addProperty("const", c.value());
                option.addProperty("title", c.title());
                options.add(option);
            }
            decision.add("oneOf", options);
        } else {
            JsonArray values = new JsonArray();
            JsonArray names = new JsonArray();
            for (McpQuestion.Choice c : q.choices()) {
                values.add(c.value());
                names.add(c.title());
            }
            decision.add("enum", values);
            decision.add("enumNames", names);
        }
        JsonObject note = new JsonObject();
        note.addProperty("type", "string");
        note.addProperty("title", q.noteTitle());
        JsonObject properties = new JsonObject();
        properties.add("decision", decision);
        properties.add("note", note);
        JsonArray required = new JsonArray();
        required.add("decision");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    /** accept 按选的那项,decline 是明确拒绝,cancel(关掉弹窗没选)不算答复。 */
    private static McpQuestion.Answer answerOf(ElicitResult result) {
        return switch (result.action()) {
            case ACCEPT -> {
                JsonObject content = result.content() == null ? new JsonObject() : result.content();
                yield new McpQuestion.Answer(string(content, "decision"), false,
                        string(content, "note") == null ? "" : string(content, "note"));
            }
            case DECLINE -> new McpQuestion.Answer(null, true, "");
            case CANCEL -> new McpQuestion.Answer(null, false, "");
        };
    }

    private static String string(JsonObject o, String key) {
        return o.get(key) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }
}
