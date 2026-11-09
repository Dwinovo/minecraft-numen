package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.mcp.rpc.RpcError;
import com.dwinovo.numen.mcp.rpc.RpcMessage;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 端点收到的一个 HTTP 请求,沿着处理链({@link Stage})往下走;认出的令牌由认人那一环记在这里给后面的环节。
 */
final class HttpCall {

    private final HttpExchange exchange;
    /** 认出的令牌;没认出是 null,原因在 {@link #refusal}。 */
    McpToken token;
    String refusal;

    HttpCall(HttpExchange exchange) {
        this.exchange = exchange;
    }

    String method() {
        return exchange.getRequestMethod().toUpperCase(Locale.ROOT);
    }

    /** 请求头;没有是 null。 */
    String header(String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    String address() {
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    /** 读请求体,最多 {@code max} 字节;超了返回 null。 */
    byte[] body(int max) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(max + 1);
        return body.length > max ? null : body;
    }

    void setHeader(String name, String value) {
        exchange.getResponseHeaders().set(name, value);
    }

    /** 只有状态码、没有正文的答复。 */
    void respond(int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    /** 一条 JSON-RPC 消息作为 {@code application/json} 正文。 */
    void respond(int status, RpcMessage message) throws IOException {
        byte[] body = message.encode().getBytes(StandardCharsets.UTF_8);
        setHeader("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** 不属于哪个请求的错误(编号为 null 的 JSON-RPC 错误答复)。 */
    void refuse(int status, int code, String message) throws IOException {
        respond(status, RpcMessage.Response.failed(null, new RpcError(code, message)));
    }

    /** 开一条 SSE 流:答复头发出去,之后往 {@link #out} 写事件。 */
    void openEventStream() throws IOException {
        setHeader("Content-Type", "text/event-stream");
        setHeader("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().flush();
    }

    /** 往开着的 SSE 流写一段并立刻送出。 */
    void write(String text) throws IOException {
        OutputStream out = exchange.getResponseBody();
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    void close() {
        exchange.close();
    }
}
