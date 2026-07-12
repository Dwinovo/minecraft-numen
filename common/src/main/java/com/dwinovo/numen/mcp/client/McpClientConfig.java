package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client config at {@code config/numen/mcp_clients.json} — the list of external
 * MCP servers a companion's built-in brain may reach out to and borrow tools
 * from. Plain Gson-over-file, created with a self-documenting (all-disabled)
 * example on first launch. Mirrors the style of numen-mcp's {@code McpConfig}:
 * per-key defaults, and an unreadable file degrades to disabled rather than
 * throwing.
 *
 * <ul>
 *   <li>{@code enabled} — master switch (default true).</li>
 *   <li>{@code servers} — one entry per external MCP server.</li>
 * </ul>
 *
 * Each server:
 * <ul>
 *   <li>{@code name} — short id; prefixes every borrowed tool ({@code name__tool}).</li>
 *   <li>{@code type} — {@code "http"} (default) or {@code "stdio"}.</li>
 *   <li>{@code url} + {@code headers} — for {@code http}.</li>
 *   <li>{@code command} + {@code args} + {@code env} — for {@code stdio}
 *       (on Windows a bare {@code npx} won't launch; use
 *       {@code "command":"cmd","args":["/c","npx", ...]}).</li>
 *   <li>{@code enabled} — per-server switch.</li>
 *   <li>{@code connect_timeout_seconds} / {@code call_timeout_seconds}.</li>
 * </ul>
 */
public record McpClientConfig(boolean enabled, List<ServerSpec> servers) {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    /** One external MCP server the client may connect to. */
    public record ServerSpec(
            String name,
            String type,
            String url,
            Map<String, String> headers,
            String command,
            List<String> args,
            Map<String, String> env,
            boolean enabled,
            int connectTimeoutSeconds,
            int callTimeoutSeconds) {

        public boolean isStdio() {
            return "stdio".equalsIgnoreCase(type);
        }

        /** Default when {@code type} is absent/blank/"http". */
        public boolean isHttp() {
            return !isStdio();
        }
    }

    public static McpClientConfig load(Path file) {
        if (!Files.isRegularFile(file)) {
            McpClientConfig def = defaultConfig();
            writeDefault(file, def);
            return def;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            boolean enabled = !o.has("enabled") || o.get("enabled").getAsBoolean();
            List<ServerSpec> servers = new ArrayList<>();
            if (o.has("servers") && o.get("servers").isJsonArray()) {
                for (JsonElement el : o.getAsJsonArray("servers")) {
                    if (el.isJsonObject()) servers.add(parseServer(el.getAsJsonObject()));
                }
            }
            return new McpClientConfig(enabled, List.copyOf(servers));
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-mcp-client] unreadable config {} — client disabled: {}", file, ex.toString());
            return new McpClientConfig(false, List.of());
        }
    }

    private static ServerSpec parseServer(JsonObject o) {
        return new ServerSpec(
                strOr(o, "name", "unnamed"),
                strOr(o, "type", "http"),
                strOr(o, "url", ""),
                map(o, "headers"),
                strOr(o, "command", ""),
                strings(o, "args"),
                map(o, "env"),
                !o.has("enabled") || o.get("enabled").getAsBoolean(),
                o.has("connect_timeout_seconds") ? o.get("connect_timeout_seconds").getAsInt() : 20,
                o.has("call_timeout_seconds") ? o.get("call_timeout_seconds").getAsInt() : 120);
    }

    private static McpClientConfig defaultConfig() {
        ServerSpec http = new ServerSpec(
                "example-http", "http", "http://127.0.0.1:9000/mcp",
                Map.of(), "", List.of(), Map.of(), false, 20, 120);
        ServerSpec stdio = new ServerSpec(
                "example-stdio", "stdio", "", Map.of(),
                "npx", List.of("-y", "@modelcontextprotocol/server-everything"), Map.of(),
                false, 20, 120);
        return new McpClientConfig(true, List.of(http, stdio));
    }

    /**
     * Write the config back to disk (pretty-printed). Used both for the first-launch
     * default and to persist live enable/disable toggles from the panel. The record
     * is immutable, so callers rebuild it with the changed {@link ServerSpec} before
     * saving. Best-effort — a write failure is logged, not thrown.
     */
    public static void save(Path file, McpClientConfig cfg) {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", cfg.enabled());
        JsonArray servers = new JsonArray();
        for (ServerSpec s : cfg.servers()) servers.add(serverToJson(s));
        root.add("servers", servers);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, PRETTY.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-mcp-client] failed to write config {}: {}", file, ex.toString());
        }
    }

    private static void writeDefault(Path file, McpClientConfig def) {
        save(file, def);
        Constants.LOG.info("[numen-mcp-client] wrote default config {}", file);
    }

    /** A copy of {@code s} with its {@code enabled} flag set to {@code enabled}. */
    public static ServerSpec withEnabled(ServerSpec s, boolean enabled) {
        return new ServerSpec(s.name(), s.type(), s.url(), s.headers(), s.command(),
                s.args(), s.env(), enabled, s.connectTimeoutSeconds(), s.callTimeoutSeconds());
    }

    private static JsonObject serverToJson(ServerSpec s) {
        JsonObject o = new JsonObject();
        o.addProperty("name", s.name());
        o.addProperty("type", s.type());
        if (s.isStdio()) {
            o.addProperty("command", s.command());
            JsonArray a = new JsonArray();
            s.args().forEach(a::add);
            o.add("args", a);
            o.add("env", strMapToJson(s.env()));
        } else {
            o.addProperty("url", s.url());
            o.add("headers", strMapToJson(s.headers()));
        }
        o.addProperty("enabled", s.enabled());
        o.addProperty("connect_timeout_seconds", s.connectTimeoutSeconds());
        o.addProperty("call_timeout_seconds", s.callTimeoutSeconds());
        return o;
    }

    private static JsonObject strMapToJson(Map<String, String> m) {
        JsonObject o = new JsonObject();
        if (m != null) m.forEach(o::addProperty);
        return o;
    }

    private static Map<String, String> map(JsonObject o, String key) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o.has(key) && o.get(key).isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(key).entrySet()) {
                if (!e.getValue().isJsonNull()) out.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return Map.copyOf(out);
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
