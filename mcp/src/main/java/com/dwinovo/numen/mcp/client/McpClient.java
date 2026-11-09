package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.mcp.McpThreads;
import com.dwinovo.numen.mcp.protocol.CallToolResult;
import com.dwinovo.numen.mcp.protocol.ElicitRequest;
import com.dwinovo.numen.mcp.protocol.ElicitResult;
import com.dwinovo.numen.mcp.protocol.Implementation;
import com.dwinovo.numen.mcp.protocol.InitializeResult;
import com.dwinovo.numen.mcp.protocol.McpCodec;
import com.dwinovo.numen.mcp.protocol.McpMethods;
import com.dwinovo.numen.mcp.protocol.ProtocolVersion;
import com.dwinovo.numen.mcp.protocol.Tool;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.dwinovo.numen.mcp.rpc.RpcMessage;
import com.dwinovo.numen.mcp.rpc.RpcPeer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * 到一台 MCP 服务器的一个客户端会话:握手谈版本({@link ProtocolVersion#LATEST} 起,对方答我们说的版本才继续)、列工具(翻完所有页)、
 * 调工具,对方说工具表变了交给 {@link ToolsChanged}。传输是给定的({@link ClientTransport}),协议的编号、等答复、撤回在
 * {@link RpcPeer}。
 *
 * <ul>
 *   <li>对方发来的请求:{@code ping} 照答;给了 {@link Elicitor} 才声明会答表单式的 elicitation、才答它;别的(采样、roots……)没声明,
 *       答"没有这个方法"。</li>
 *   <li>请求等满时限就撤回(发 {@code notifications/cancelled})并以超时失败。</li>
 *   <li>Streamable HTTP 的会话被服务端结束(404):照规范重新握手开新会话,把那个请求再发一次(404 说明服务端没处理它)。</li>
 *   <li>{@link #close} 了就是关了:之后的请求一律失败,不会因为关之后到的 404 又去开会话。</li>
 * </ul>
 *
 * <p>线程:任何线程;请求阻塞到答复或时限。对方的请求各在一条线程上答。
 */
public final class McpClient implements AutoCloseable {

    /** 替坐在客户端前的人答服务端要问的表单。 */
    @FunctionalInterface
    public interface Elicitor {
        ElicitResult answer(ElicitRequest request) throws Exception;
    }

    /** 对方说它的工具表变了;在收到通知的线程上调,不许阻塞(要重列就另起线程)。 */
    @FunctionalInterface
    public interface ToolsChanged {
        void changed();
    }

    private final ClientTransport transport;
    private final Implementation clientInfo;
    private final Elicitor elicitor;
    private final Duration requestTimeout;
    private final Duration initializeTimeout;
    private final RpcPeer peer = new RpcPeer(McpThreads.perTask("numen-mcp-client-"));
    private final ClientTransport.Inbound inbound = new ClientTransport.Inbound() {
        @Override
        public void message(RpcMessage message) {
            peer.receive(message, transport);
        }

        @Override
        public void lost(JsonPrimitive requestId, IOException why) {
            peer.fail(requestId, why);
        }

        @Override
        public void closed(IOException why) {
            peer.failPending(why);
        }
    };
    private volatile InitializeResult server;
    private volatile boolean closed;

    private McpClient(ClientTransport transport, Implementation clientInfo, Elicitor elicitor, ToolsChanged toolsChanged,
                      Duration requestTimeout, Duration initializeTimeout) {
        this.transport = transport;
        this.clientInfo = clientInfo;
        this.elicitor = elicitor;
        this.requestTimeout = requestTimeout;
        this.initializeTimeout = initializeTimeout;
        peer.onRequest(McpMethods.PING, request -> new JsonObject());
        if (elicitor != null) {
            peer.onRequest(McpMethods.ELICIT, request -> McpCodec.elicitResult(
                    elicitor.answer(McpCodec.elicitRequest(request.params()))));
        }
        peer.onNotification(McpMethods.TOOLS_LIST_CHANGED, params -> toolsChanged.changed());
    }

    /**
     * 连上并握手(阻塞)。失败时传输已关掉。
     *
     * @param elicitor          会答表单式 elicitation 的人;不答是 null(不声明这项能力)
     * @param requestTimeout    一个请求最久等多久
     * @param initializeTimeout 握手最久等多久(要登录的服务器,等人授权的时间也算在里面)
     */
    public static McpClient connect(ClientTransport transport, Implementation clientInfo, Elicitor elicitor,
                                    ToolsChanged toolsChanged, Duration requestTimeout, Duration initializeTimeout)
            throws IOException, RpcException, TimeoutException, InterruptedException {
        McpClient client = new McpClient(transport, clientInfo, elicitor, toolsChanged, requestTimeout, initializeTimeout);
        try {
            client.handshake();
        } catch (IOException | RpcException | TimeoutException | InterruptedException | RuntimeException failed) {
            client.close();
            throw failed;
        }
        return client;
    }

    private void handshake() throws IOException, RpcException, TimeoutException, InterruptedException {
        transport.open(inbound);
        InitializeResult result = McpCodec.initializeResult(peer.call(McpMethods.INITIALIZE,
                McpCodec.initializeParams(ProtocolVersion.LATEST, clientInfo, elicitor != null), transport, initializeTimeout));
        server = result;
        peer.notify(McpMethods.INITIALIZED, new JsonObject(), transport);
        transport.initialized(result.protocolVersion());
    }

    /** 握手谈定的:版本、对方的自我介绍、使用说明。 */
    public InitializeResult server() {
        return server;
    }

    /** 这台服务器现在有的全部工具(翻完所有页)。 */
    public List<Tool> listTools() throws IOException, RpcException, TimeoutException, InterruptedException {
        List<Tool> all = new ArrayList<>();
        String cursor = null;
        do {
            McpCodec.ToolsPage page = McpCodec.toolsPage(request(McpMethods.TOOLS_LIST, McpCodec.listToolsParams(cursor)));
            all.addAll(page.tools());
            cursor = page.nextCursor();
        } while (cursor != null);
        return all;
    }

    /** 调一次工具(阻塞,最久 {@code requestTimeout})。 */
    public CallToolResult callTool(String name, JsonObject arguments)
            throws IOException, RpcException, TimeoutException, InterruptedException {
        return McpCodec.callToolResult(request(McpMethods.TOOLS_CALL, McpCodec.callToolParams(name, arguments)));
    }

    private JsonElement request(String method, JsonObject params)
            throws IOException, RpcException, TimeoutException, InterruptedException {
        InitializeResult session = server;
        try {
            return call(method, params);
        } catch (SessionExpired expired) {
            synchronized (this) {
                if (closed) {
                    throw expired;
                }
                if (server == session) {
                    handshake();
                }
            }
            return call(method, params);
        }
    }

    private JsonElement call(String method, JsonObject params)
            throws IOException, RpcException, TimeoutException, InterruptedException {
        if (closed) {
            throw new IOException("the connection is closed");
        }
        return peer.call(method, params, transport, requestTimeout);
    }

    /** 还在等答复的请求有几个(只读)。 */
    public int pending() {
        return peer.pending();
    }

    /** 关掉:还在等的请求失败,传输关掉(HTTP 结束会话,stdio 收掉子进程)。 */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        peer.failPending(new IOException("the connection is closed"));
        transport.close();
    }
}
