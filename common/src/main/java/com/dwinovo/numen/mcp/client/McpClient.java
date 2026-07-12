package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * One connected external MCP server, speaking JSON-RPC 2.0 over a
 * {@link McpTransport}. Owns id generation and the three calls we need:
 * {@code initialize} (+ the {@code notifications/initialized} handshake),
 * {@code tools/list}, and {@code tools/call}.
 *
 * <p>{@link #connect} and {@link #listTools} block (they run on a background
 * connect thread, so blocking is fine). {@link #callTool} stays async — its
 * future completes on the transport's thread and is marshalled to the game main
 * thread by {@link RemoteMcpTool}.
 */
public final class McpClient {

    /** MCP protocol revision we speak — matches numen-mcp's server default. */
    public static final String PROTOCOL_VERSION = "2025-06-18";

    private final String name;
    private final McpTransport transport;
    private final AtomicLong seq = new AtomicLong();
    private volatile BiConsumer<String, JsonObject> notificationListener;

    public McpClient(String name, McpTransport transport) {
        this.name = name;
        this.transport = transport;
    }

    public String name() {
        return name;
    }

    /**
     * Route server→client notifications (method, params) to {@code listener} — e.g.
     * {@code notifications/tools/list_changed}. Wires the transport's message sink; call
     * {@link #startServerStream()} afterward so HTTP opens its GET SSE channel.
     */
    public void setNotificationListener(BiConsumer<String, JsonObject> listener) {
        this.notificationListener = listener;
        transport.setNotificationHandler(this::onTransportMessage);
    }

    /** Open the server→client channel (HTTP GET SSE; stdio already listening). */
    public void startServerStream() {
        transport.openServerStream();
    }

    private void onTransportMessage(JsonObject msg) {
        // Route notifications only (method present, no id). Server→client requests
        // (sampling/roots) are not supported in v1 and are ignored.
        if (!msg.has("method")) return;
        if (msg.has("id") && !msg.get("id").isJsonNull()) return;
        BiConsumer<String, JsonObject> l = notificationListener;
        if (l == null) return;
        String method = msg.get("method").getAsString();
        JsonObject params = msg.has("params") && msg.get("params").isJsonObject()
                ? msg.getAsJsonObject("params") : new JsonObject();
        try {
            l.accept(method, params);
        } catch (RuntimeException ex) {
            // never let a bad notification kill the reader thread
        }
    }

    /** {@code initialize} handshake, then the {@code notifications/initialized} nudge. Throws on failure. */
    public void connect(long connectTimeoutMs) {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);
        params.add("capabilities", new JsonObject());   // empty object — declares nothing, breaks nothing
        JsonObject info = new JsonObject();
        info.addProperty("name", "numen");
        info.addProperty("version", "0.0.2");
        params.add("clientInfo", info);

        JsonObject result = resultOrThrow(blockingRequest("initialize", params, connectTimeoutMs));
        // Negotiate: honour the version the SERVER chose (spec) — it becomes the
        // MCP-Protocol-Version header the transport sends on every later request.
        String negotiated = result.has("protocolVersion") && result.get("protocolVersion").isJsonPrimitive()
                ? result.get("protocolVersion").getAsString() : PROTOCOL_VERSION;
        transport.setProtocolVersion(negotiated);
        transport.notify(frame(null, "notifications/initialized", new JsonObject()));
    }

    /**
     * Fetch the server's tool definitions ({@code name}/{@code description}/{@code inputSchema}/
     * {@code annotations}), following {@code nextCursor} pagination so a server with many tools
     * doesn't get silently truncated to its first page.
     */
    public List<JsonObject> listTools(long timeoutMs) {
        List<JsonObject> out = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonObject params = new JsonObject();
            if (cursor != null) params.addProperty("cursor", cursor);
            JsonObject result = resultOrThrow(blockingRequest("tools/list", params, timeoutMs));
            if (result.has("tools") && result.get("tools").isJsonArray()) {
                for (JsonElement el : result.getAsJsonArray("tools")) {
                    if (el.isJsonObject()) out.add(el.getAsJsonObject());
                }
            }
            cursor = result.has("nextCursor") && result.get("nextCursor").isJsonPrimitive()
                    ? result.get("nextCursor").getAsString() : null;
        } while (cursor != null && ++pages < 50);   // backstop against a server that never ends
        return out;
    }

    /**
     * Call one tool; the future resolves to a model-facing result string (or a {@code TaskResult.fail}
     * JSON). If the server reports the session expired (HTTP 404), reconnect once and retry (spec).
     */
    public CompletableFuture<String> callTool(String toolName, JsonObject args, long timeoutMs) {
        return doCallTool(toolName, args, timeoutMs).exceptionallyCompose(err -> {
            if (!isSessionExpired(err)) return CompletableFuture.failedFuture(err);
            try {
                connect(timeoutMs);   // session gone → re-initialize (gets a fresh Mcp-Session-Id)
            } catch (RuntimeException re) {
                return CompletableFuture.failedFuture(re);
            }
            return doCallTool(toolName, args, timeoutMs);
        });
    }

    private CompletableFuture<String> doCallTool(String toolName, JsonObject args, long timeoutMs) {
        JsonObject params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", args == null ? new JsonObject() : args);
        long id = seq.incrementAndGet();
        return transport.request(frame(id, "tools/call", params), timeoutMs)
                .thenApply(McpClient::toResultString);
    }

    private static boolean isSessionExpired(Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof McpSessionExpired) return true;
        }
        return false;
    }

    // ---- internals ----

    private JsonObject blockingRequest(String method, JsonObject params, long timeoutMs) {
        long id = seq.incrementAndGet();
        try {
            return transport.request(frame(id, method, params), timeoutMs).join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause() == null ? ce : ce.getCause();
            // Let the auth / session-expiry signals through unwrapped so the manager can act on them.
            if (cause instanceof McpAuthRequired ar) throw ar;
            if (cause instanceof McpSessionExpired se) throw se;
            throw new RuntimeException(name + " " + method + " failed: " + cause.getMessage(), cause);
        }
    }

    /** Unwrap a JSON-RPC response into its {@code result} object, or throw on a JSON-RPC error. */
    private JsonObject resultOrThrow(JsonObject resp) {
        if (resp.has("error") && resp.get("error").isJsonObject()) {
            JsonObject err = resp.getAsJsonObject("error");
            String msg = err.has("message") ? err.get("message").getAsString() : err.toString();
            throw new RuntimeException(name + " error: " + msg);
        }
        return resp.has("result") && resp.get("result").isJsonObject()
                ? resp.getAsJsonObject("result")
                : new JsonObject();
    }

    /** MCP {@code tools/call} envelope → a plain string for the LLM (errors wrapped as {@code TaskResult.fail}). */
    private static String toResultString(JsonObject resp) {
        if (resp.has("error") && resp.get("error").isJsonObject()) {
            JsonObject err = resp.getAsJsonObject("error");
            String msg = err.has("message") ? err.get("message").getAsString() : err.toString();
            return TaskResult.fail(msg).toJson();
        }
        JsonObject result = resp.has("result") && resp.get("result").isJsonObject()
                ? resp.getAsJsonObject("result")
                : new JsonObject();
        boolean isError = result.has("isError") && result.get("isError").getAsBoolean();
        String text = joinContent(result);
        return isError ? TaskResult.fail(text).toJson() : text;
    }

    private static String joinContent(JsonObject result) {
        StringBuilder sb = new StringBuilder();
        if (result.has("content") && result.get("content").isJsonArray()) {
            JsonArray content = result.getAsJsonArray("content");
            for (JsonElement el : content) {
                if (!el.isJsonObject()) continue;
                JsonObject block = el.getAsJsonObject();
                String type = block.has("type") ? block.get("type").getAsString() : "";
                switch (type) {
                    case "text" -> sb.append(block.has("text") ? block.get("text").getAsString() : "");
                    // Embedded resources often carry text (a file's contents) — surface it to the model
                    // rather than a bare placeholder; binary blobs fall back to a uri/mime note.
                    case "resource" -> sb.append(describeResource(block));
                    case "resource_link" -> sb.append("[resource ")
                            .append(block.has("uri") ? block.get("uri").getAsString() : "").append(']');
                    case "image" -> sb.append("[image ")
                            .append(block.has("mimeType") ? block.get("mimeType").getAsString() : "binary").append(']');
                    case "audio" -> sb.append("[audio ")
                            .append(block.has("mimeType") ? block.get("mimeType").getAsString() : "binary").append(']');
                    default -> sb.append(block);
                }
                sb.append('\n');
            }
        }
        String joined = sb.toString().strip();
        if (!joined.isEmpty()) return joined;
        // No content array (some servers only return structuredContent) — hand that back verbatim.
        if (result.has("structuredContent")) return result.get("structuredContent").toString();
        return "(no content)";
    }

    /** An embedded-resource block → its inline text if present, else a uri/mime note. */
    private static String describeResource(JsonObject block) {
        JsonObject res = block.has("resource") && block.get("resource").isJsonObject()
                ? block.getAsJsonObject("resource") : null;
        if (res == null) return "[resource]";
        if (res.has("text") && res.get("text").isJsonPrimitive()) return res.get("text").getAsString();
        String uri = res.has("uri") ? res.get("uri").getAsString() : "";
        String mime = res.has("mimeType") ? " " + res.get("mimeType").getAsString() : "";
        return "[resource " + uri + mime + "]";
    }

    private static JsonObject frame(Long id, String method, JsonObject params) {
        JsonObject f = new JsonObject();
        f.addProperty("jsonrpc", "2.0");
        if (id != null) f.addProperty("id", id);
        f.addProperty("method", method);
        if (params != null) f.add("params", params);
        return f;
    }
}
