package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.mcp.protocol.McpMethods;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.dwinovo.numen.mcp.rpc.RpcMessage;
import com.dwinovo.numen.mcp.rpc.RpcPeer;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * 端点上开着的会话:开(握手谈妥后)、按会话号与令牌找、结束(客户端 DELETE、令牌作废、闲置太久)。会话开了、结束了告诉
 * {@link McpPresence};有会话变得能问人了告诉 {@link Questions}。
 *
 * <p>线程:任何线程。
 */
final class SessionRegistry {

    /** 没有请求、也没开着流,安静这么久的会话结束:客户端没说再见就走了。之后它再来得到 404,照规范重新握手。 */
    static final long IDLE_MS = 30 * 60_000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServerMethods methods;
    private final McpPresence presence;
    private final Executor executor;
    private final LongSupplier clock;
    private final Map<String, ServerSession> byId = new ConcurrentHashMap<>();
    private Questions questions;

    SessionRegistry(ServerMethods methods, McpPresence presence, Executor executor, LongSupplier clock) {
        this.methods = methods;
        this.presence = presence;
        this.executor = executor;
        this.clock = clock;
    }

    void questions(Questions questions) {
        this.questions = questions;
    }

    /** 开一个会话:握手谈妥({@link ServerMethods#initialize})才开。返回会话与 initialize 的答复。 */
    record Opened(ServerSession session, RpcMessage.Response response) {}

    Opened open(McpToken token, RpcMessage.Request initialize) throws ServerMethods.Busy, RpcException {
        ServerMethods.Admitted admitted = methods.initialize(token, initialize);
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        ServerSession session = new ServerSession(HexFormat.of().formatHex(raw), token, admitted.version(), admitted.askable(),
                admitted.client(), new RpcPeer(executor), clock.getAsLong());
        methods.install(session, questions::deliverAll);
        byId.put(session.id, session);
        presence.opened(token);
        return new Opened(session, RpcMessage.Response.ok(initialize.id(), admitted.result()));
    }

    /** 这张令牌开的、还没结束的会话 {@code id};没有(已结束、不存在、别的令牌的)是 null。 */
    ServerSession find(String id, McpToken token) {
        ServerSession session = id == null ? null : byId.get(id);
        return session != null && session.token.id().equals(token.id()) ? session : null;
    }

    /** 这张令牌(编号)开着的会话。 */
    List<ServerSession> of(String tokenId) {
        return byId.values().stream().filter(s -> s.token.id().equals(tokenId)).toList();
    }

    /** 客户端开了 GET 流:它也许变得能问人了。 */
    boolean listen(ServerSession session, Stream stream) {
        if (!session.listen(stream)) {
            return false;
        }
        questions.deliverAll();
        return true;
    }

    /** 结束一个会话。 */
    void close(ServerSession session) {
        if (byId.remove(session.id, session)) {
            session.terminate();
            presence.ended(session.token, of(session.token.id()).isEmpty());
        }
    }

    /** 这张令牌(编号)的工具表变了:它开着 GET 流的会话各收一条通知(一批增删一条)。 */
    void toolsChanged(String tokenId) {
        for (ServerSession session : of(tokenId)) {
            Stream stream = session.listening();
            if (stream != null) {
                try {
                    session.peer.notify(McpMethods.TOOLS_LIST_CHANGED, new JsonObject(), stream);
                } catch (IOException closing) {
                    // 这条流正在收场:没有别的流可送,客户端下次列工具自会看到新表
                }
            }
        }
    }

    /** 令牌作废的会话与闲置太久的会话结束。 */
    void sweep(java.util.function.Predicate<McpToken> valid) {
        long now = clock.getAsLong();
        for (ServerSession session : List.copyOf(byId.values())) {
            if (!valid.test(session.token) || session.idle(now, IDLE_MS)) {
                close(session);
            }
        }
    }

    /** 端点停了:全部结束。 */
    void closeAll() {
        for (ServerSession session : List.copyOf(byId.values())) {
            close(session);
        }
    }
}
