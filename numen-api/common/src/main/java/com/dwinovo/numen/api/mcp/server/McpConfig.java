package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.NumenIo;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 远程端点的服务器配置,{@code mcp_server.json}。没有这个文件就按默认值写一份。
 *
 * <ul>
 *   <li>{@code enabled} — 总开关,默认关(服主自己开);</li>
 *   <li>{@code host}/{@code port} — 端点绑在哪。默认只绑回环;开放给局域网或公网时端点是明文 HTTP,
 *       建议前面放反向代理加 HTTPS({@link #lanExposed});</li>
 *   <li>{@code call_timeout_seconds} — 一次 {@code tools/call} 最久等身体做完多久;</li>
 *   <li>{@code hidden_tools} — 不给外部大脑看的工具;</li>
 *   <li>{@code limits} — 限流,见 {@link Limits}。</li>
 * </ul>
 * 谁能连不在这里:每张令牌带着自己的主人、作用域与身份({@link McpToken}),端点永远要令牌。
 */
public record McpConfig(
        boolean enabled,
        String host,
        int port,
        int callTimeoutSeconds,
        List<String> hiddenTools,
        Limits limits) {

    /**
     * 限流。每张令牌限同时在飞的调用数与每分钟的调用数;总量是所有令牌加起来的上限,服主按机器的承受力调。
     * 长等待的 {@code get_events} 一次占一个并发名额(最长 50 秒),所以每张令牌的并发要留够同时驱动几只同伴的量。
     */
    public record Limits(int perTokenConcurrent, int perTokenPerMinute, int totalConcurrent, int totalPerMinute) {

        public static final Limits DEFAULT = new Limits(8, 240, 64, 1200);

        public Limits {
            if (perTokenConcurrent < 1 || perTokenPerMinute < 1 || totalConcurrent < 1 || totalPerMinute < 1) {
                throw new IllegalArgumentException("limits must be positive");
            }
        }
    }

    private static final List<String> DEFAULT_HIDDEN = List.of();
    /** 只绑回环。默认值,也是"没开局域网"时的地址。 */
    public static final String LOOPBACK = "127.0.0.1";
    /** 绑所有网卡——局域网里别的机器就能连上来了。 */
    public static final String ANY_HOST = "0.0.0.0";

    /** 读配置;没有就播一份默认的。读不懂就当关着,不带着一份读坏的表起服。 */
    public static McpConfig load(Path file, com.dwinovo.numen.api.store.Io io) {
        try {
            JsonObject o = doc(file, io).read().orElse(null);
            if (o == null) {
                McpConfig def = disabledDefault();
                write(file, io, def);
                return def;
            }
            JsonObject lim = o.has("limits") && o.get("limits").isJsonObject() ? o.getAsJsonObject("limits")
                    : new JsonObject();
            Limits d = Limits.DEFAULT;
            return new McpConfig(
                    o.has("enabled") && o.get("enabled").getAsBoolean(),
                    strOr(o, "host", LOOPBACK),
                    o.has("port") ? o.get("port").getAsInt() : 8765,
                    o.has("call_timeout_seconds") ? o.get("call_timeout_seconds").getAsInt() : 300,
                    o.has("hidden_tools") ? strings(o, "hidden_tools") : DEFAULT_HIDDEN,
                    new Limits(intOr(lim, "per_token_concurrent", d.perTokenConcurrent()),
                            intOr(lim, "per_token_per_minute", d.perTokenPerMinute()),
                            intOr(lim, "total_concurrent", d.totalConcurrent()),
                            intOr(lim, "total_per_minute", d.totalPerMinute())));
        } catch (RuntimeException ex) {
            Constants.LOG.warn("[numen-mcp] unreadable config {} — server disabled: {}", file, ex.toString());
            return disabledDefault();
        }
    }

    public boolean isHidden(String toolName) {
        return hiddenTools.contains(toolName);
    }

    /** 默认配置(关着)。 */
    static McpConfig disabledDefault() {
        return new McpConfig(false, LOOPBACK, 8765, 300, DEFAULT_HIDDEN, Limits.DEFAULT);
    }

    McpConfig withEnabled(boolean on) {
        return new McpConfig(on, host, port, callTimeoutSeconds, hiddenTools, limits);
    }

    McpConfig withEndpoint(String host, int port, int callTimeoutSeconds) {
        return new McpConfig(enabled, host, port, callTimeoutSeconds, hiddenTools, limits);
    }

    McpConfig withHiddenTools(List<String> hiddenTools) {
        return new McpConfig(enabled, host, port, callTimeoutSeconds, List.copyOf(hiddenTools), limits);
    }

    /** 绑的是回环之外吗——别的机器够得着,流量不再只在本机里走。 */
    public boolean lanExposed() {
        return !LOOPBACK.equals(host) && !"localhost".equalsIgnoreCase(host);
    }

    /** 写回配置文件:游戏内拨的开关要跨会话记住。 */
    void save(Path file, com.dwinovo.numen.api.store.Io io) {
        write(file, io, this);
    }

    private static void write(Path file, com.dwinovo.numen.api.store.Io io, McpConfig cfg) {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", cfg.enabled());
        o.addProperty("host", cfg.host());
        o.addProperty("port", cfg.port());
        o.addProperty("call_timeout_seconds", cfg.callTimeoutSeconds());
        JsonArray hidden = new JsonArray();
        cfg.hiddenTools().forEach(hidden::add);
        o.add("hidden_tools", hidden);
        JsonObject lim = new JsonObject();
        lim.addProperty("per_token_concurrent", cfg.limits().perTokenConcurrent());
        lim.addProperty("per_token_per_minute", cfg.limits().perTokenPerMinute());
        lim.addProperty("total_concurrent", cfg.limits().totalConcurrent());
        lim.addProperty("total_per_minute", cfg.limits().totalPerMinute());
        o.add("limits", lim);
        doc(file, io).write(o);
    }

    private static JsonDoc doc(Path file, com.dwinovo.numen.api.store.Io io) {
        return new JsonDoc(file, JsonDoc.Format.of(1), io);
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray(key)) out.add(el.getAsString());
        }
        return List.copyOf(out);
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        return o.has(key) ? o.get(key).getAsInt() : fallback;
    }

    private static String strOr(JsonObject o, String key, String fallback) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? fallback : el.getAsString();
    }
}
