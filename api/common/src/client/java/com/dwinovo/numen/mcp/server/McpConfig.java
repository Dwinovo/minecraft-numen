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
 *       (agent-internal bookkeeping the external brain has no business calling);</li>
 *   <li>{@code quiet_fallback} — when the external brain goes quiet (no request for
 *       {@link McpMode#QUIET_AFTER_MS} ms) the built-in brain takes over until it
 *       returns. Default false: the companion stands by silently — the owner turned
 *       this mode on deliberately.</li>
 * </ul>
 */
public record McpConfig(
        boolean enabled,
        String host,
        int port,
        String token,
        int callTimeoutSeconds,
        List<String> hiddenTools,
        boolean quietFallback) {

    /** Tools the built-in brain manages for itself — never handed to an external driver. */
    private static final List<String> DEFAULT_HIDDEN = List.of("todowrite", "load_skill");

    /**
     * 读配置;没有就播一份默认的。
     *
     * <p>默认<b>带一个现生成的令牌</b>。空令牌只在回环上无害,而这个文件是可以手改的——
     * 一旦有人把 host 改成 0.0.0.0(那是 issue 里被要求过的能力),空令牌就是无鉴权全开。
     * 与其指望他改地址时记得再设一个,不如一开始就有。
     */
    public static McpConfig load(Path file) {
        if (!Files.isRegularFile(file)) {
            McpConfig def = new McpConfig(false, LOOPBACK, 8765, mintToken(), 300, DEFAULT_HIDDEN, false);
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
                    o.has("hidden_tools") ? strings(o, "hidden_tools") : DEFAULT_HIDDEN,
                    o.has("quiet_fallback") && o.get("quiet_fallback").getAsBoolean());
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-mcp] unreadable config {} — server disabled: {}", file, ex.toString());
            return new McpConfig(false, "127.0.0.1", 8765, "", 300, DEFAULT_HIDDEN, false);
        }
    }

    public boolean isHidden(String toolName) {
        return hiddenTools.contains(toolName);
    }

    /** 只绑回环。默认值,也是"没开局域网"时的地址。 */
    public static final String LOOPBACK = "127.0.0.1";
    /** 绑所有网卡——局域网里别的机器就能连上来了。 */
    public static final String ANY_HOST = "0.0.0.0";

    /** 配置文件还没读到时的占位(模式关闭),避免 {@link McpMode} 持 null 配置。 */
    static McpConfig disabledDefault() {
        return new McpConfig(false, LOOPBACK, 8765, "", 300, DEFAULT_HIDDEN, false);
    }

    /** 只改开关的副本——设置面板拨动开关时用,其余字段保持用户手改的值。 */
    McpConfig withEnabled(boolean on) {
        return new McpConfig(on, host, port, token, callTimeoutSeconds, hiddenTools, quietFallback);
    }

    /** 改端点与超时的副本(设置页保存时用)。 */
    McpConfig withEndpoint(String host, int port, int callTimeoutSeconds) {
        return new McpConfig(enabled, host, port, token, callTimeoutSeconds, hiddenTools, quietFallback);
    }

    McpConfig withToken(String token) {
        return new McpConfig(enabled, host, port, token, callTimeoutSeconds, hiddenTools, quietFallback);
    }

    McpConfig withHiddenTools(List<String> hiddenTools) {
        return new McpConfig(enabled, host, port, token, callTimeoutSeconds, List.copyOf(hiddenTools), quietFallback);
    }

    /** 拨"失联后内脑接管"的副本。 */
    McpConfig withQuietFallback(boolean on) {
        return new McpConfig(enabled, host, port, token, callTimeoutSeconds, hiddenTools, on);
    }

    /** 绑的是所有网卡吗——局域网里别人够得着。 */
    public boolean lanExposed() {
        return !LOOPBACK.equals(host) && !"localhost".equalsIgnoreCase(host);
    }

    /**
     * 令牌为空且对局域网开放 = 无鉴权裸奔。
     *
     * <p>本类注释里那句 "Empty = no auth (fine on loopback)" 的前提是<b>回环</b>;地址一放开,
     * 那个 fine 就不成立了。设置页据此拦住保存。
     */
    public boolean unguarded() {
        return lanExposed() && (token == null || token.isBlank());
    }

    /**
     * 生成一个新令牌。
     *
     * <p>用 {@link java.security.SecureRandom}——这串东西是外部 AI 操控同伴的唯一凭据,
     * 拿可预测的随机数生成等于没有。
     */
    public static String mintToken() {
        byte[] raw = new byte[18];
        new java.security.SecureRandom().nextBytes(raw);
        return "numen-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
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
        o.addProperty("quiet_fallback", cfg.quietFallback());
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
