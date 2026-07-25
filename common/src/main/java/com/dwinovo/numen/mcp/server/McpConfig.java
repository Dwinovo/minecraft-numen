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
 *   <li>{@code enabled} — master switch (default false; owner opts in);</li>
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
            McpConfig def = new McpConfig(false, "127.0.0.1", 8765, "", 300, DEFAULT_HIDDEN);
            writeDefault(file, def);
            return def;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return new McpConfig(
                    o.has("enabled") ? o.get("enabled").getAsBoolean() : false,
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

    /** 配置文件还没读到时的占位(模式关闭),避免 {@link McpMode} 持 null 配置。 */
    static McpConfig disabledDefault() {
        return new McpConfig(false, "127.0.0.1", 8765, "", 300, DEFAULT_HIDDEN);
    }

    /** 只改开关的副本——设置面板拨动开关时用,其余字段保持用户手改的值。 */
    McpConfig withEnabled(boolean on) {
        return new McpConfig(on, host, port, token, callTimeoutSeconds, hiddenTools);
    }

    /** 写回配置文件:游戏内拨的开关要跨会话记住。 */
    void save(Path file) {
        write(file, this, "saved config");
    }

    private static void writeDefault(Path file, McpConfig def) {
        write(file, def, "wrote default config");
    }

    private static void write(Path file, McpConfig cfg, String what) {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", cfg.enabled());
        o.addProperty("host", cfg.host());
        o.addProperty("port", cfg.port());
        o.addProperty("token", cfg.token());
        o.addProperty("call_timeout_seconds", cfg.callTimeoutSeconds());
        JsonArray hidden = new JsonArray();
        cfg.hiddenTools().forEach(hidden::add);
        o.add("hidden_tools", hidden);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
            Constants.LOG.info("[numen-mcp] {} {}", what, file);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-mcp] failed to write config {}: {}", file, ex.toString());
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
