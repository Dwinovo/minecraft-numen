package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.mcp.protocol.McpMethods;
import com.dwinovo.numen.mcp.protocol.ProtocolVersion;
import com.dwinovo.numen.mcp.rpc.RpcError;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.dwinovo.numen.mcp.rpc.RpcMessage;
import com.dwinovo.numen.mcp.rpc.Sse;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 处理链的末端:MCP 的 Streamable HTTP 传输(规范 {@code basic/transports} 一节),一个端点 {@code /mcp} 收 POST、GET、DELETE。
 *
 * <ul>
 *   <li><b>POST</b>:一条消息(2025-03-26 的会话可以是一批)。initialize 开会话,答复头带 {@code Mcp-Session-Id};别的要带开着的会话号,
 *       不认识的答 404(结束了的、不存在的、别的令牌的都一样),没带的答 400。只有通知与答复的答 202;有请求的,答复只有一条且前面没有
 *       别的消息就是一个 JSON,否则开一条 SSE 流,先写与请求相关的消息(进度),答复写完就收场。</li>
 *   <li><b>GET</b>:客户端开的那条 SSE 流,服务端与哪个请求都无关的消息(工具表变了、问人)走它;一个会话只开一条,第二条答 409。
 *       久不说话写一行注释,写不出去就是对方走了。</li>
 *   <li><b>DELETE</b>:结束会话。</li>
 * </ul>
 * {@code MCP-Protocol-Version} 头带了却是不支持的版本答 400;没带的按会话谈定的版本。
 *
 * <p>线程:每个 HTTP 请求一条线程,它是这条流唯一的写者;请求的处理在 {@link com.dwinovo.numen.mcp.rpc.RpcPeer} 给的另一条线程上,
 * 结果经 {@link Stream} 交回来写。占着线程的只有在等的请求与开着的流。
 */
final class StreamableHttp implements Stage.Next {

    /** 请求体上限:一段程序或一句话远用不到,挡住灌内存的。 */
    static final int MAX_BODY_BYTES = 1 << 20;
    /** 流上这么久没有消息就写一行注释:让中间的代理别掐断它,也及时发现对方走了。 */
    static final long KEEPALIVE_MS = 15_000L;

    static final String SESSION_HEADER = "Mcp-Session-Id";
    static final String VERSION_HEADER = "MCP-Protocol-Version";

    private final SessionRegistry sessions;
    private final McpPresence presence;
    private final McpAudit audit;
    private final LongSupplier clock;

    StreamableHttp(SessionRegistry sessions, McpPresence presence, McpAudit audit, LongSupplier clock) {
        this.sessions = sessions;
        this.presence = presence;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        switch (call.method()) {
            case "POST" -> post(call);
            case "GET" -> get(call);
            case "DELETE" -> delete(call);
            default -> {
                call.setHeader("Allow", "GET, POST, DELETE");
                call.respond(405);
            }
        }
    }

    // ---- POST ----

    private void post(HttpCall call) throws IOException {
        String accept = call.header("Accept");
        if (!accepts(accept, "application/json") || !accepts(accept, "text/event-stream")) {
            call.refuse(406, -32000, "Not Acceptable: the client must accept both application/json and text/event-stream");
            return;
        }
        String contentType = call.header("Content-Type");
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            call.refuse(415, -32000, "Unsupported Media Type: the body must be application/json");
            return;
        }
        byte[] body = call.body(MAX_BODY_BYTES);
        if (body == null) {
            call.respond(413);
            return;
        }
        List<RpcMessage> messages;
        try {
            messages = RpcMessage.decodeAll(new String(body, StandardCharsets.UTF_8));
        } catch (RpcException bad) {
            call.refuse(400, bad.error().code(), bad.error().message());
            return;
        }
        RpcMessage.Request initialize = messages.stream()
                .filter(m -> m instanceof RpcMessage.Request r && r.method().equals(McpMethods.INITIALIZE))
                .map(m -> (RpcMessage.Request) m).findFirst().orElse(null);
        if (initialize != null) {
            if (messages.size() > 1) {
                call.refuse(400, RpcError.INVALID_REQUEST, "Invalid Request: initialize must not be part of a batch");
                return;
            }
            initialize(call, initialize);
            return;
        }
        ServerSession session = session(call);
        if (session == null) {
            return;
        }
        if (messages.size() > 1 && !session.version.batching()) {
            call.refuse(400, RpcError.INVALID_REQUEST, "Invalid Request: batches are not allowed in protocol version "
                    + session.version.wire());
            return;
        }
        presence.begin(session.token);
        session.requestStarted(clock.getAsLong());
        try {
            Stream reply = new Stream();
            Set<JsonPrimitive> awaiting = new HashSet<>();
            for (RpcMessage message : messages) {
                if (message instanceof RpcMessage.Request request) {
                    awaiting.add(request.id());
                }
                session.peer.receive(message, reply);
            }
            if (awaiting.isEmpty()) {
                call.respond(202);
            } else {
                reply(call, reply, awaiting);
            }
        } finally {
            session.requestFinished(clock.getAsLong());
            presence.end(session.token);
        }
    }

    private void initialize(HttpCall call, RpcMessage.Request request) throws IOException {
        McpToken token = call.token;
        presence.opening(token);
        try {
            SessionRegistry.Opened opened;
            try {
                opened = sessions.open(token, request);
            } catch (ServerMethods.Busy busy) {
                call.setHeader("Retry-After", "5");
                call.refuse(429, -32000, busy.getMessage());
                return;
            } catch (RpcException failed) {
                call.respond(200, RpcMessage.Response.failed(request.id(), failed.error()));
                return;
            }
            call.setHeader(SESSION_HEADER, opened.session().id);
            call.respond(200, opened.response());
            audit.connected(opened.session().id, token, opened.session().client);
        } finally {
            presence.end(token);
        }
    }

    /**
     * 写这个 POST 的答复:等着的请求只有一个、且第一条要写的就是它的答复,写成一个 JSON;否则开 SSE 流,把与这些请求相关的消息和
     * 它们的答复依次写出去,都答完(或被撤回)就收场。对方中途走了不算撤回:请求照常做完,答复没处可交。
     */
    private void reply(HttpCall call, Stream reply, Set<JsonPrimitive> awaiting) throws IOException {
        boolean single = awaiting.size() == 1;
        boolean sse = false;
        try {
            while (!awaiting.isEmpty()) {
                Object next = reply.next(KEEPALIVE_MS);
                if (next == null) {
                    if (sse) {
                        call.write(Sse.comment("keepalive"));
                    }
                    continue;
                }
                if (next == Stream.CLOSED) {
                    break;
                }
                if (next instanceof Stream.Dropped dropped) {
                    awaiting.remove(dropped.id());
                    continue;
                }
                RpcMessage message = (RpcMessage) next;
                boolean answer = message instanceof RpcMessage.Response r && awaiting.remove(r.id());
                if (!sse && single && answer) {
                    call.respond(200, message);
                    return;
                }
                if (!sse) {
                    call.openEventStream();
                    sse = true;
                }
                call.write(Sse.event(message.encode()));
            }
            if (!sse) {
                call.openEventStream();   // 唯一的请求被撤回了:规范说不再答复,流开了就收
            }
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        } catch (IOException clientGone) {
            // 对方走了:照规范断开不等于撤回,处理照常做完,答复没处可交
        } finally {
            reply.close();
            call.close();
        }
    }

    // ---- GET ----

    private void get(HttpCall call) throws IOException {
        if (!accepts(call.header("Accept"), "text/event-stream")) {
            call.refuse(406, -32000, "Not Acceptable: the client must accept text/event-stream");
            return;
        }
        ServerSession session = session(call);
        if (session == null) {
            return;
        }
        Stream stream = new Stream();
        if (!sessions.listen(session, stream)) {
            call.refuse(409, -32000, "Conflict: this session already has a stream open");
            return;
        }
        presence.begin(session.token);
        session.requestStarted(clock.getAsLong());
        try {
            call.openEventStream();
            while (true) {
                Object next = stream.next(KEEPALIVE_MS);
                if (next == Stream.CLOSED) {
                    break;
                }
                call.write(next instanceof RpcMessage message ? Sse.event(message.encode()) : Sse.comment("keepalive"));
            }
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        } catch (IOException clientGone) {
            // 对方关了流:下面收场
        } finally {
            stream.close();
            session.unlisten(stream);
            session.requestFinished(clock.getAsLong());
            presence.end(session.token);
            call.close();
        }
    }

    // ---- DELETE ----

    private void delete(HttpCall call) throws IOException {
        ServerSession session = session(call);
        if (session != null) {
            sessions.close(session);
            call.respond(200);
        }
    }

    // ---- 零件 ----

    /** 请求带的会话号认出来的、这张令牌开着的会话;认不出已答好 400/404,返回 null。版本头不对的也在这里答 400。 */
    private ServerSession session(HttpCall call) throws IOException {
        String id = call.header(SESSION_HEADER);
        if (id == null) {
            call.refuse(400, -32000, "Bad Request: the " + SESSION_HEADER + " header is required");
            return null;
        }
        ServerSession session = sessions.find(id, call.token);
        if (session == null) {
            call.refuse(404, -32001, "Session not found");
            return null;
        }
        String version = call.header(VERSION_HEADER);
        if (version != null && ProtocolVersion.of(version).isEmpty()) {
            call.refuse(400, -32000, "Bad Request: unsupported " + VERSION_HEADER + ": " + version);
            return null;
        }
        return session;
    }

    private static boolean accepts(String accept, String type) {
        return accept != null && accept.toLowerCase(java.util.Locale.ROOT).contains(type);
    }
}
