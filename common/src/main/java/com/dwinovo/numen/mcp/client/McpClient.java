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

    public McpClient(String name, McpTransport transport) {
        this.name = name;
        this.transport = transport;
    }

    public String name() {
        return name;
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

        resultOrThrow(blockingRequest("initialize", params, connectTimeoutMs));
        transport.notify(frame(null, "notifications/initialized", new JsonObject()));
    }

    /** Fetch the server's tool definitions ({@code name}/{@code description}/{@code inputSchema}/{@code annotations}). */
    public List<JsonObject> listTools(long timeoutMs) {
        JsonObject result = resultOrThrow(blockingRequest("tools/list", new JsonObject(), timeoutMs));
        List<JsonObject> out = new ArrayList<>();
        if (result.has("tools") && result.get("tools").isJsonArray()) {
            for (JsonElement el : result.getAsJsonArray("tools")) {
                if (el.isJsonObject()) out.add(el.getAsJsonObject());
            }
        }
        return out;
    }

    /** Call one tool; the future resolves to a model-facing result string (or a {@code TaskResult.fail} JSON). */
    public CompletableFuture<String> callTool(String toolName, JsonObject args, long timeoutMs) {
        JsonObject params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", args == null ? new JsonObject() : args);
        long id = seq.incrementAndGet();
        return transport.request(frame(id, "tools/call", params), timeoutMs)
                .thenApply(McpClient::toResultString);
    }

    // ---- internals ----

    private JsonObject blockingRequest(String method, JsonObject params, long timeoutMs) {
        long id = seq.incrementAndGet();
        try {
            return transport.request(frame(id, method, params), timeoutMs).join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause() == null ? ce : ce.getCause();
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
                    case "image" -> sb.append("[image]");
                    case "audio" -> sb.append("[audio]");
                    case "resource", "resource_link" -> sb.append("[resource]");
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

    private static JsonObject frame(Long id, String method, JsonObject params) {
        JsonObject f = new JsonObject();
        f.addProperty("jsonrpc", "2.0");
        if (id != null) f.addProperty("id", id);
        f.addProperty("method", method);
        if (params != null) f.add("params", params);
        return f;
    }
}
