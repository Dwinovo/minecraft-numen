package com.dwinovo.numen.mcp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一台接进来的 MCP 服务器怎么连:一个 JSON 文件的内容,文件名就是服务器的名字,里面是——
 *
 * <ul>
 *   <li>{@code type} — {@code "http"}(缺省)或 {@code "stdio"}。</li>
 *   <li>{@code url} + {@code headers} — 给 {@code http}。</li>
 *   <li>{@code command} + {@code args} + {@code env} — 给 {@code stdio}(Windows 上裸的 {@code npx} 起不来,
 *       用 {@code "command":"cmd","args":["/c","npx", ...]})。</li>
 *   <li>{@code service} — 这台服务器用钥匙串里哪个服务的 key:有就作为 {@code Authorization: Bearer} 带上
 *       (先找主人的钥匙串、再找服主的)。key 不写在条目里。</li>
 *   <li>{@code client_id} — 这台服务器要登录(OAuth)时用的客户端 ID;写了就用它,不写就按规范的顺序找(见 {@code auth} 包)。</li>
 *   <li>{@code enabled} — 连不连(缺省连)。这是条目主人的开关;玩家对别人的条目关掉它只对自己生效,记在他的偏好里。</li>
 *   <li>{@code connect_timeout_seconds} / {@code call_timeout_seconds}。</li>
 * </ul>
 *
 * @param name 库里的名字(文件名),不在文件正文里
 */
public record McpServerSpec(
        String name,
        String type,
        String url,
        Map<String, String> headers,
        String command,
        List<String> args,
        Map<String, String> env,
        String service,
        String clientId,
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

    /** 一个条目文件的正文读成服务器;{@code name} 是文件名。写法不对抛 {@link RuntimeException}。 */
    public static McpServerSpec read(String name, String json) {
        return parse(name, JsonParser.parseString(json).getAsJsonObject());
    }

    /** 请求体或条目文件里的一台服务器;{@code name} 是库里的名字。 */
    public static McpServerSpec parse(String name, JsonObject o) {
        return new McpServerSpec(
                name,
                strOr(o, "type", "http"),
                strOr(o, "url", ""),
                map(o, "headers"),
                strOr(o, "command", ""),
                strings(o, "args"),
                map(o, "env"),
                strOr(o, "service", ""),
                strOr(o, "client_id", ""),
                !o.has("enabled") || o.get("enabled").getAsBoolean(),
                o.has("connect_timeout_seconds") ? o.get("connect_timeout_seconds").getAsInt() : 20,
                o.has("call_timeout_seconds") ? o.get("call_timeout_seconds").getAsInt() : 120);
    }

    /** 条目文件的正文:{@link #toJson} 不含名字的那一份。 */
    public String write() {
        JsonObject o = toJson();
        o.remove("name");
        return o.toString();
    }

    /** 同一台服务器,开关换成 {@code enabled}。 */
    public McpServerSpec withEnabled(boolean enabled) {
        return new McpServerSpec(name, type, url, headers, command, args, env, service, clientId, enabled,
                connectTimeoutSeconds, callTimeoutSeconds);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("type", type);
        if (isStdio()) {
            o.addProperty("command", command);
            JsonArray a = new JsonArray();
            args.forEach(a::add);
            o.add("args", a);
            o.add("env", strMapToJson(env));
        } else {
            o.addProperty("url", url);
            o.add("headers", strMapToJson(headers));
        }
        if (!service.isBlank()) {
            o.addProperty("service", service);
        }
        if (!clientId.isBlank()) {
            o.addProperty("client_id", clientId);
        }
        o.addProperty("enabled", enabled);
        o.addProperty("connect_timeout_seconds", connectTimeoutSeconds);
        o.addProperty("call_timeout_seconds", callTimeoutSeconds);
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
