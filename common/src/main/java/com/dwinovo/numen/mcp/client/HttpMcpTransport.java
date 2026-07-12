package com.dwinovo.numen.mcp.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP transport for MCP's streamable-HTTP servers: POST each JSON-RPC frame to
 * the server URL, read the response back on the same POST. Captures the
 * {@code Mcp-Session-Id} the server hands out on {@code initialize} and echoes
 * it on every later request. If the server answers with {@code text/event-stream}
 * (a streamable server), the JSON-RPC message is pulled out of the SSE
 * {@code data:} frames; a plain {@code application/json} body is parsed directly.
 *
 * <p>Scope: request/response only — no long-lived server→client GET stream. That
 * is unnecessary for the {@code tools/list} + {@code tools/call} flow and would
 * add a persistent connection to manage; left for a later revision if a server
 * needs to push notifications.
 */
public final class HttpMcpTransport implements McpTransport {

    private final URI url;
    private final Map<String, String> headers;
    private final HttpClient http;
    private volatile String sessionId;

    public HttpMcpTransport(String url, Map<String, String> headers, int connectTimeoutSeconds) {
        this.url = URI.create(url);
        this.headers = headers == null ? Map.of() : headers;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, connectTimeoutSeconds)))
                .build();
    }

    @Override
    public CompletableFuture<JsonObject> request(JsonObject frame, long timeoutMs) {
        HttpRequest req = build(frame, timeoutMs);
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(this::parseResponse);
    }

    @Override
    public void notify(JsonObject frame) {
        http.sendAsync(build(frame, 10_000), HttpResponse.BodyHandlers.discarding());
    }

    @Override
    public void close() {
        // Nothing to tear down — HttpClient owns no persistent connection here.
    }

    private HttpRequest build(JsonObject frame, long timeoutMs) {
        HttpRequest.Builder b = HttpRequest.newBuilder(url)
                .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(frame.toString(), StandardCharsets.UTF_8));
        headers.forEach(b::header);
        String sid = sessionId;
        if (sid != null) b.header("Mcp-Session-Id", sid);
        return b.build();
    }

    private JsonObject parseResponse(HttpResponse<String> resp) {
        resp.headers().firstValue("Mcp-Session-Id").ifPresent(v -> sessionId = v);
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
        }
        String contentType = resp.headers().firstValue("Content-Type").orElse("").toLowerCase();
        String body = resp.body() == null ? "" : resp.body();
        JsonObject msg = contentType.contains("text/event-stream") ? extractSse(body) : tryParse(body);
        if (msg == null) {
            throw new RuntimeException("no JSON-RPC message in response: " + truncate(body));
        }
        return msg;
    }

    /** Pull the first JSON-RPC response ({@code result}/{@code error}) out of an SSE body. */
    private static JsonObject extractSse(String body) {
        StringBuilder data = new StringBuilder();
        for (String raw : body.split("\n")) {
            String line = raw.replace("\r", "");
            if (line.startsWith("data:")) {
                data.append(line.substring(5).trim());
            } else if (line.isEmpty() && data.length() > 0) {
                JsonObject o = tryParse(data.toString());
                data.setLength(0);
                if (isResponse(o)) return o;
            }
        }
        JsonObject tail = tryParse(data.toString());
        return isResponse(tail) ? tail : null;
    }

    private static boolean isResponse(JsonObject o) {
        return o != null && (o.has("result") || o.has("error"));
    }

    private static JsonObject tryParse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return JsonParser.parseString(s).getAsJsonObject();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
