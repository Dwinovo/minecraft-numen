package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.mcp.protocol.CallToolResult;
import com.dwinovo.numen.mcp.protocol.Content;
import com.dwinovo.numen.mcp.protocol.Icon;
import com.dwinovo.numen.mcp.protocol.Implementation;
import com.dwinovo.numen.mcp.protocol.McpCodec;
import com.dwinovo.numen.mcp.protocol.McpMethods;
import com.dwinovo.numen.mcp.protocol.ProtocolVersion;
import com.dwinovo.numen.mcp.protocol.Tool;
import com.dwinovo.numen.mcp.protocol.ToolAnnotations;
import com.dwinovo.numen.mcp.rpc.RpcError;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.dwinovo.numen.mcp.rpc.RpcMessage;
import com.dwinovo.numen.mcp.rpc.RpcPeer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 端点作为 MCP 服务器做的事:握手、ping、列工具、调工具。协议之外的都交给各自的组件:工具从哪来问 {@link McpHost}(经
 * {@link ToolTable}),限不限流问 {@link McpLimits},记什么交 {@link McpAudit}。HTTP 与会话的事不在这里。
 *
 * <p>线程:握手在收到它的 HTTP 线程上;别的请求在 {@link RpcPeer} 给的线程上,调工具占着线程等宿主做完。
 */
final class ServerMethods {

    /** 接入被限流:答 HTTP 429。 */
    static final class Busy extends Exception {
        Busy(String refusal) {
            super(refusal);
        }
    }

    /** 握手谈妥的:版本、对方会不会答表单、对方自报的名字,与 initialize 的答复正文。 */
    record Admitted(ProtocolVersion version, boolean askable, String client, JsonObject result) {}

    private final McpHost host;
    private final McpConfig config;
    private final ToolTable tools;
    private final McpLimits limits;
    private final McpAudit audit;
    private final McpPresence presence;
    private final Implementation serverInfo;
    private final Icon icon;

    ServerMethods(McpHost host, McpConfig config, ToolTable tools, McpLimits limits, McpAudit audit, McpPresence presence) {
        this.host = host;
        this.config = config;
        this.tools = tools;
        this.limits = limits;
        this.audit = audit;
        this.presence = presence;
        McpHost.Identity who = host.identity();
        this.icon = icon(who.iconPng());
        this.serverInfo = new Implementation(who.name(), who.title(), who.version(), who.description(), List.of(icon));
    }

    /**
     * 一次 initialize:受限流({@link Busy}),谈版本,并在会话开之前把这张令牌的工具表对好——会话一开就是对好的表,第一次列工具没有变化
     * 可通知;不这样的话,新令牌的第一次列表才逐个对上,刚开的会话收到"工具表变了",客户端白白重列一次。
     *
     * @throws RpcException 握手的参数不对,或宿主给不出工具表(如实带上原因)
     */
    Admitted initialize(McpToken token, RpcMessage.Request request) throws Busy, RpcException {
        try (McpLimits.Permit permit = limits.acquire(token.id())) {
            if (permit.refusal() != null) {
                audit.refused("rate limited", token.id());
                throw new Busy(permit.refusal());
            }
            McpCodec.InitializeParams params = McpCodec.initializeParams(request.params());
            ProtocolVersion version = ProtocolVersion.negotiate(params.protocolVersion());
            String client = label(params.clientInfo());
            presence.named(token, client);
            listTools(token);
            return new Admitted(version, params.formElicitation(), client,
                    McpCodec.initializeResult(version, serverInfo, host.instructions()));
        }
    }

    /** 给一个刚开的会话挂上它的请求处理。{@code initialized}:客户端说握手做完了。 */
    void install(ServerSession session, Runnable initialized) {
        RpcPeer peer = session.peer;
        peer.onRequest(McpMethods.PING, request -> new JsonObject());
        peer.onRequest(McpMethods.TOOLS_LIST, request -> McpCodec.toolsPage(session.version,
                listTools(session.token).stream().map(this::tool).toList()));
        peer.onRequest(McpMethods.TOOLS_CALL, request -> call(session, request));
        peer.onNotification(McpMethods.INITIALIZED, params -> {
            session.initialized();
            initialized.run();
        });
    }

    private List<McpHost.ToolInfo> listTools(McpToken token) throws RpcException {
        try {
            return tools.refresh(token);
        } catch (Exception failed) {
            throw new RpcException(RpcError.INTERNAL_ERROR, "The companion's tools cannot be listed right now: " + failed);
        }
    }

    private Tool tool(McpHost.ToolInfo info) {
        McpHost.Hints h = info.hints();
        return new Tool(info.name(), info.title(), info.description(),
                JsonParser.parseString(info.inputSchemaJson()).getAsJsonObject(),
                new ToolAnnotations(h.readOnly(), h.destructive(), h.idempotent(), h.openWorld()), List.of(icon));
    }

    /** 一次 tools/call:只认这张令牌列出的工具;限流、交给宿主做、记审计,结果装成文本块。宿主出的岔子是工具错误,模型读得到。 */
    private JsonElement call(ServerSession session, RpcPeer.Incoming request) throws Exception {
        McpCodec.CallToolParams params = McpCodec.callToolParams(request.params());
        McpToken token = session.token;
        String name = params.name();
        if (!tools.has(token, name)) {
            throw new RpcException(RpcError.INVALID_PARAMS, "Unknown tool: " + name);
        }
        String argsJson = params.arguments().toString();
        int bytesIn = argsJson.length();
        McpHost.Result result;
        try (McpLimits.Permit permit = limits.acquire(token.id())) {
            if (permit.refusal() != null) {
                audit.called(session.id, token, name, null, false, bytesIn, 0);
                return encode(McpHost.Result.text("rate limited: " + permit.refusal() + ". Wait a few seconds and call again.",
                        true));
            }
            try {
                result = host.call(token, name, argsJson, config.callTimeoutSeconds(), progress(request, params.progressToken()));
            } catch (TimeoutException late) {
                result = McpHost.Result.text("timed out waiting for the action to finish", true);
            } catch (InterruptedException stopping) {
                if (request.cancelled()) {
                    throw stopping;   // 客户端撤回了:不答复
                }
                Thread.currentThread().interrupt();
                result = McpHost.Result.text("the endpoint is shutting down", true);
            } catch (Exception failed) {
                result = McpHost.Result.text("call failed: " + failed.getMessage(), true);
            }
        }
        audit.called(session.id, token, name, result.subject(), !result.error(), bytesIn,
                result.texts().stream().mapToInt(String::length).sum());
        return encode(result);
    }

    /** 客户端带了进度令牌才报进度;{@code progress} 必须逐条增大,总量不知道,不写。 */
    private static McpHost.Progress progress(RpcPeer.Incoming request, JsonPrimitive progressToken) {
        if (progressToken == null) {
            return McpHost.Progress.NONE;
        }
        AtomicLong sent = new AtomicLong();
        return message -> {
            try {
                request.notify(McpMethods.PROGRESS, McpCodec.progress(progressToken, sent.incrementAndGet(), message));
            } catch (IOException gone) {
                // 这次调用的流已经断了(客户端走了):进度没处可送,调用本身照常做完
            }
        };
    }

    private static JsonObject encode(McpHost.Result r) {
        List<Content> content = r.texts().stream().<Content>map(Content.Text::new).toList();
        return McpCodec.callToolResult(new CallToolResult(content, null, r.error()));
    }

    /** {@code clientInfo} → "Claude Desktop 1.2.3"。 */
    private static String label(Implementation client) {
        return client.version().isBlank() ? client.name() : client.name() + " " + client.version();
    }

    private static Icon icon(byte[] png) {
        // PNG 的 IHDR:第 16 字节起是宽高(各 4 字节,大端)
        int w = ((png[16] & 0xff) << 24) | ((png[17] & 0xff) << 16) | ((png[18] & 0xff) << 8) | (png[19] & 0xff);
        int h = ((png[20] & 0xff) << 24) | ((png[21] & 0xff) << 16) | ((png[22] & 0xff) << 8) | (png[23] & 0xff);
        return new Icon("data:image/png;base64," + Base64.getEncoder().encodeToString(png), "image/png", List.of(w + "x" + h));
    }
}
