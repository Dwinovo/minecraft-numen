package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.mcp.protocol.ProtocolVersion;
import com.dwinovo.numen.mcp.rpc.RpcPeer;

import java.io.IOException;

/**
 * 一个 MCP 会话(Streamable HTTP 的 {@code Mcp-Session-Id}):属于开它的那张令牌,握手时定下的版本与对方的能力,JSON-RPC 的一端
 * ({@link #peer}),和客户端开着的那条 GET 流。会话状态只在这里;谁在场、连没连着由 {@link McpPresence} 按令牌汇总。
 *
 * <p>线程:任何线程。
 */
final class ServerSession {

    final String id;
    final McpToken token;
    final ProtocolVersion version;
    /** 对方会答表单式的 elicitation。 */
    final boolean askable;
    /** 对方自报的名字,如 "Claude Code 1.2.3"。 */
    final String client;
    final RpcPeer peer;

    private volatile boolean initialized;
    private Stream listening;
    /** 正在处理的请求数(含开着的 GET 流)。 */
    private int active;
    private long lastSeen;
    private boolean ended;

    ServerSession(String id, McpToken token, ProtocolVersion version, boolean askable, String client, RpcPeer peer,
                  long now) {
        this.id = id;
        this.token = token;
        this.version = version;
        this.askable = askable;
        this.client = client;
        this.peer = peer;
        this.lastSeen = now;
    }

    /** 客户端发了 {@code notifications/initialized}:此后可以向它发请求(规范:之前只许 ping 与日志)。 */
    void initialized() {
        initialized = true;
    }

    /** 此刻能问它人:会答表单、握手做完、开着 GET 流。 */
    synchronized boolean canAsk() {
        return askable && initialized && listening != null && !ended;
    }

    /** 它开着的 GET 流;没开是 null。 */
    synchronized Stream listening() {
        return listening;
    }

    /** 客户端开了 GET 流。已经开着一条的拒绝(返回 false):一条消息只走一条流,不在两条上都发。 */
    synchronized boolean listen(Stream stream) {
        if (listening != null || ended) {
            return false;
        }
        listening = stream;
        return true;
    }

    /** GET 流收场了:经它发出、还没答的请求(问人)作废,交给它们的发起方。 */
    void unlisten(Stream stream) {
        synchronized (this) {
            if (listening != stream) {
                return;
            }
            listening = null;
        }
        peer.failPending(new IOException("the client's stream closed"));
    }

    synchronized void requestStarted(long now) {
        active++;
        lastSeen = now;
    }

    synchronized void requestFinished(long now) {
        active--;
        lastSeen = now;
    }

    /** 没有请求在处理、也没开着流,安静超过 {@code idleMs}。 */
    synchronized boolean idle(long now, long idleMs) {
        return active <= 0 && listening == null && now - lastSeen > idleMs;
    }

    /** 结束会话:GET 流关掉,经它发出的请求作废。之后对这个会话号的请求答 404。 */
    void terminate() {
        Stream stream;
        synchronized (this) {
            ended = true;
            stream = listening;
        }
        if (stream != null) {
            stream.close();
            unlisten(stream);
        }
    }
}
