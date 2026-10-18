package com.dwinovo.numen.api.mcp.client;

import com.dwinovo.numen.api.library.LibraryKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接进来的 MCP 服务器({@link McpClientManager})是一种库({@link #KIND}):一台服务器一个 JSON 文件——
 * {@code mcp/<命名空间>/<名字>.json},同伴的大脑能向它借工具。玩家看到"内置"(服务器目录里,管理员配的)与"我的"两组;
 * 玩家只能加 HTTP 类的,stdio 类起的是服务器机器上的进程,只有管理员能加。文件名就是服务器的名字,里面是这台服务器怎么连:
 *
 * <ul>
 *   <li>{@code type} — {@code "http"}(缺省)或 {@code "stdio"}。</li>
 *   <li>{@code url} + {@code headers} — 给 {@code http}。</li>
 *   <li>{@code command} + {@code args} + {@code env} — 给 {@code stdio}(Windows 上裸的 {@code npx} 起不来,
 *       用 {@code "command":"cmd","args":["/c","npx", ...]})。</li>
 *   <li>{@code service} — 这台服务器用钥匙串里哪个服务的 key:有就作为 {@code Authorization: Bearer} 带上
 *       (先找主人的钥匙串、再找服主的)。key 不写在条目里。</li>
 *   <li>{@code enabled} — 连不连(缺省连)。这是条目主人的开关;玩家对别人的条目关掉它只对自己生效,记在他的偏好里。</li>
 *   <li>{@code connect_timeout_seconds} / {@code call_timeout_seconds}。</li>
 * </ul>
 */
public final class McpClientConfig {

    /** 接进来的 MCP 服务器这种库:一条一个 {@code .json}。 */
    public static final LibraryKind KIND = LibraryKind.files("mcp", ".json");

    private McpClientConfig() {}

    /** 一台接进来的 MCP 服务器怎么连。 */
    public record ServerSpec(
            String name,
            String type,
            String url,
            Map<String, String> headers,
            String command,
            List<String> args,
            Map<String, String> env,
            String service,
            boolean enabled,
            int connectTimeoutSeconds,
            int callTimeoutSeconds) {

        public boolean isStdio() {
            return "stdio".equalsIgnoreCase(type);
        }

        /** 缺省(没写、空、"http")是 http。 */
        public boolean isHttp() {
            return !isStdio();
        }
    }

    /** 一个条目文件的正文读成服务器;{@code name} 是文件名。写法不对抛 {@link RuntimeException}。 */
    public static ServerSpec read(String name, String json) {
        return parseServer(name, JsonParser.parseString(json).getAsJsonObject());
    }

    /** 请求体或条目文件里的一台服务器;{@code name} 是库里的名字。 */
    public static ServerSpec parseServer(String name, JsonObject o) {
        return new ServerSpec(
                name,
                strOr(o, "type", "http"),
                strOr(o, "url", ""),
                map(o, "headers"),
                strOr(o, "command", ""),
                strings(o, "args"),
                map(o, "env"),
                strOr(o, "service", ""),
                !o.has("enabled") || o.get("enabled").getAsBoolean(),
                o.has("connect_timeout_seconds") ? o.get("connect_timeout_seconds").getAsInt() : 20,
                o.has("call_timeout_seconds") ? o.get("call_timeout_seconds").getAsInt() : 120);
    }

    /** 条目文件的正文:{@link #serverToJson} 不含名字的那一份。 */
    public static String write(ServerSpec spec) {
        JsonObject o = serverToJson(spec);
        o.remove("name");
        return o.toString();
    }

    /** A copy of {@code s} with its {@code enabled} flag set to {@code enabled}. */
    public static ServerSpec withEnabled(ServerSpec s, boolean enabled) {
        return new ServerSpec(s.name(), s.type(), s.url(), s.headers(), s.command(),
                s.args(), s.env(), s.service(), enabled, s.connectTimeoutSeconds(), s.callTimeoutSeconds());
    }

    public static JsonObject serverToJson(ServerSpec s) {
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
        if (!s.service().isBlank()) {
            o.addProperty("service", s.service());
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
