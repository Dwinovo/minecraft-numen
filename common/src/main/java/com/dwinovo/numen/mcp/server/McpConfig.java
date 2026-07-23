package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.Constants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Server config at {@code config/numen/mcp_server.json}. Plain Gson-over-file,
 * created with defaults on first launch.
 *
 * <ul>
 *   <li>{@code enabled} — master switch (default true);</li>
 *   <li>{@code host}/{@code port} — where the MCP HTTP endpoint binds. Loopback
 *       by default; an external agent reaches it through the {@code mcp-remote}
 *       stdio bridge in {@code claude_desktop_config.json};</li>
 *   <li>{@code token} — optional bearer token; when set, requests must present it
 *       (Authorization header or {@code ?token=}). Empty = no auth (fine on
 *       loopback);</li>
 *   <li>{@code call_timeout_seconds} — how long one {@code tools/call} waits for a
 *       body action to finish before reporting a timeout;</li>
 *   <li>{@code hidden_tools} — engine tools NOT exposed to the external agent
 *       (agent-internal bookkeeping the external brain has no business calling).</li>
 * </ul>
 */
public record McpConfig(
        boolean enabled,
        String host,
        int port,
        String token,
        int callTimeoutSeconds,
        List<String> hiddenTools) {

    /** Tools the built-in brain manages for itself — never handed to an external driver. */
    private static final List<String> DEFAULT_HIDDEN = List.of("todowrite", "load_skill");

    public static McpConfig load(Path file) {
        if (!Files.isRegularFile(file)) {
            McpConfig def = new McpConfig(true, "127.0.0.1", 8765, "", 300, DEFAULT_HIDDEN);
            writeDefault(file, def);
            return def;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return new McpConfig(
                    o.has("enabled") ? o.get("enabled").getAsBoolean() : true,
                    strOr(o, "host", "127.0.0.1"),
                    o.has("port") ? o.get("port").getAsInt() : 8765,
                    strOr(o, "token", ""),
                    o.has("call_timeout_seconds") ? o.get("call_timeout_seconds").getAsInt() : 300,
                    o.has("hidden_tools") ? strings(o, "hidden_tools") : DEFAULT_HIDDEN);
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-mcp] unreadable config {} — server disabled: {}", file, ex.toString());
            return new McpConfig(false, "127.0.0.1", 8765, "", 300, DEFAULT_HIDDEN);
        }
    }

    public boolean isHidden(String toolName) {
        return hiddenTools.contains(toolName);
    }

    private static void writeDefault(Path file, McpConfig def) {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", def.enabled());
        o.addProperty("host", def.host());
        o.addProperty("port", def.port());
        o.addProperty("token", def.token());
        o.addProperty("call_timeout_seconds", def.callTimeoutSeconds());
        JsonArray hidden = new JsonArray();
        def.hiddenTools().forEach(hidden::add);
        o.add("hidden_tools", hidden);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
            Constants.LOG.info("[numen-mcp] wrote default config {}", file);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-mcp] failed to write default config {}: {}", file, ex.toString());
        }
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray(key)) out.add(el.getAsString());
        }
        return List.copyOf(out);
    }

    private static String strOr(JsonObject o, String key, String fallback) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? fallback : el.getAsString();
    }
}
