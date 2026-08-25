package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.Constants;

import java.nio.file.Path;

/**
 * 「外接大脑」模式的唯一状态源——UI、内置大脑闸门、HTTP 服务器三方共读的一处真相。
 *
 * <h2>模式 = 服务器开关</h2>
 * 不另立"模式"概念:{@link McpConfig#enabled} 开着就是模式开着,服务器就在跑。
 * 设置里拨动开关 → {@link #setEnabled} 即时起停服务器并写回配置文件,下次进游戏
 * 自动恢复同一状态。
 *
 * <h2>谁在驱动这具身体:{@link #driving()}</h2>
 * "两个大脑抢一具身体"的总闸口径:外脑驱动期间内置大脑一轮都不开
 * ({@code EntityAgentLoop} 每刻按它同步队列锁)。主人在游戏里照样说话——话进
 * 事件队列,由外脑经 {@code get_events} 取走并用 {@code say} 回话;弹幕/QQ 桥接
 * 送进来的消息也走同一条线。身体层面的互斥另有 {@code TaskDispatch} 的
 * "一具身体一件活"闸门兜底,两者各管一层。
 *
 * <p>失联回退({@link McpConfig#quietFallback}):开着时外脑安静超过
 * {@value #QUIET_AFTER_MS} 毫秒就视作不在场,{@code driving()} 翻假、内置大脑
 * 接管;外脑一有动静立即交还。默认关——主人开这个模式是刻意的,她安静待命。
 *
 * <h2>线程</h2>
 * {@link #enabled()} 被渲染线程和游戏主线程高频读,故用 volatile 裸字段;
 * 握手信息由 HTTP 线程写入、渲染线程读。现场缓冲见 {@link McpTranscript}。
 */
public final class McpMode {

    private static final McpMode INSTANCE = new McpMode();

    /**
     * 外脑安静多久算"不在场"(失联播报与 quietFallback 的同一判据)。
     * get_events 长轮询最长 50 秒,正常在场的外脑请求间隔不会超过它——
     * 两倍再留余量,误报比漏报伤:错误的"失联"会让内脑抢答。
     */
    public static final long QUIET_AFTER_MS = 120_000L;

    private volatile boolean enabled;
    private volatile String clientName;      // initialize 握手报的对方名字,null = 还没人连过
    private volatile long lastActivityMs;
    private volatile String lastError;       // 起服失败的原因,给 UI 显示
    private boolean announcedQuiet;          // 失联已播报过(边沿检测,只在主线程碰)

    private Path configFile;
    private McpConfig config = McpConfig.disabledDefault();
    private McpServer server;

    private McpMode() {}

    public static McpMode instance() {
        return INSTANCE;
    }

    // ---- 生命周期 ----

    /**
     * 客户端启动时装载配置:记住配置文件位置,enabled 则立刻起服。
     * 只调一次(两个 loader 的 client 入口各自调本平台的)。
     */
    void bootstrap(Path file, McpConfig loaded) {
        this.configFile = file;
        this.config = loaded;
        if (loaded.enabled()) {
            startServer();
        } else {
            this.enabled = false;
            Constants.LOG.info("[numen-mcp] 外接大脑模式关闭(config/numen/mcp_server.json)");
        }
    }

    /**
     * 拨动开关:即时起停服务器并写回配置文件。
     *
     * @return 是否达成目标状态(起服失败时返回 false,原因见 {@link #lastError()})
     */
    public boolean setEnabled(boolean on) {
        if (on == enabled) return true;
        if (on) {
            if (!startServer()) return false;
        } else {
            stopServer();
        }
        config = config.withEnabled(enabled);
        if (configFile != null) config.save(configFile);
        return true;
    }

    /**
     * 保存设置页改动。服务器在跑就<b>原地重开</b>——端点变了,旧的那个还占着老端口。
     *
     * <p>为什么是"保存"而不是边改边生效:端口是一个字符一个字符敲进来的,{@code 8}→{@code 87}
     * →{@code 876}→{@code 8765},即时生效等于每敲一下真做一次 {@code bind()},前三次几乎必然
     * 失败,错误提示被刷成噪音。占系统资源的字段只能攒着一次落地。
     *
     * @return 起服失败时 {@code false},原因见 {@link #lastError()}
     */
    public boolean applySettings(String host, int port, int callTimeoutSeconds,
                                 java.util.List<String> hiddenTools, String token) {
        boolean wasRunning = enabled;
        if (wasRunning) {
            stopServer();
        }
        config = config.withEndpoint(host, port, callTimeoutSeconds)
                .withHiddenTools(hiddenTools)
                .withToken(token);
        boolean ok = true;
        if (wasRunning) {
            ok = startServer();
        }
        config = config.withEnabled(enabled);
        if (configFile != null) config.save(configFile);
        return ok;
    }

    /** 当前配置的只读快照——设置页据此填初值。 */
    public McpConfig config() {
        return config;
    }

    private boolean startServer() {
        lastError = null;
        McpServer s = new McpServer(config);
        try {
            s.start();
        } catch (Exception ex) {
            lastError = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            Constants.LOG.error("[numen-mcp] 起服失败 {}:{} — {}", config.host(), config.port(), ex.toString());
            enabled = false;
            return false;
        }
        server = s;
        enabled = true;
        Constants.LOG.info("[numen-mcp] 外接大脑模式已开启,端点 {} — 外部客户端经 `npx mcp-remote {}` 接入",
                endpoint(), endpoint());
        return true;
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        enabled = false;
        clientName = null;      // 连接状态随服务器一起归零(现场缓冲留着:那是主人看过的对话)
        Constants.LOG.info("[numen-mcp] 外接大脑模式已关闭,内置大脑恢复接管");
    }

    // ---- 状态查询(UI / 闸门) ----

    /** 模式(=服务器)是否开启——面板状态、设置页读这个;两脑仲裁读 {@link #driving()}。 */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 外脑此刻是否驱动着身体——内置大脑的开轮闸门、聊天区形态、现场缓冲挂点
     * 全读这一处口径。模式开着即驱动;仅当失联回退开着且外脑安静超时,才交还内脑。
     */
    public boolean driving() {
        return enabled && !(config.quietFallback() && quietNow());
    }

    /** 外脑安静超时(或从未有人连过)。 */
    private boolean quietNow() {
        return clientName == null || System.currentTimeMillis() - lastActivityMs > QUIET_AFTER_MS;
    }

    /**
     * 每 client tick 一次(两个 loader 的 client 入口驱动):失联/回归的边沿检测。
     * 主人有知情权——外脑没动静了得说一声,不能让她无声变成"已读不回"。
     */
    public void clientTick() {
        if (!enabled || clientName == null) {
            announcedQuiet = false;
            return;
        }
        boolean quiet = System.currentTimeMillis() - lastActivityMs > QUIET_AFTER_MS;
        if (quiet && !announcedQuiet) {
            announcedQuiet = true;
            com.dwinovo.numen.client.chat.ChatLines.notice(
                    net.minecraft.client.resources.language.I18n.get("numen.brain.title"),
                    net.minecraft.client.resources.language.I18n.get(config.quietFallback()
                            ? "numen.brain.quiet_fallback" : "numen.brain.quiet_standby"));
        } else if (!quiet && announcedQuiet) {
            announcedQuiet = false;
            com.dwinovo.numen.client.chat.ChatLines.notice(
                    net.minecraft.client.resources.language.I18n.get("numen.brain.title"),
                    net.minecraft.client.resources.language.I18n.get("numen.brain.back_active"));
        }
    }

    /** 拨"失联后内脑接管"开关:只写配置,即时生效({@link #driving()} 现算)。 */
    public void setQuietFallback(boolean on) {
        config = config.withQuietFallback(on);
        if (configFile != null) config.save(configFile);
    }

    public String endpoint() {
        return "http://" + config.host() + ":" + config.port() + "/mcp";
    }

    public String token() {
        return config.token();
    }

    /**
     * 打码后的令牌,给 UI 显示——明文 token 不上屏(截图/录屏泄露过一次就永久泄露),
     * 要用整份的地方走复制按钮。
     */
    public String maskedToken() {
        String t = config.token();
        if (t == null || t.isBlank()) return "";
        return t.length() <= 8 ? "•".repeat(t.length()) : t.substring(0, 8) + "•".repeat(6);
    }

    /** 最近一次 initialize 握手报上来的客户端名字,或 null(还没人接入)。 */
    public String clientName() {
        return clientName;
    }

    /** 最近一次收到请求的时刻(毫秒),0 = 从未。 */
    public long lastActivityMs() {
        return lastActivityMs;
    }

    /** 最近一次起服失败的原因,或 null。 */
    public String lastError() {
        return lastError;
    }

    /**
     * 「复制接入提示词」的内容:内嵌当前端点与令牌,用户复制后发给自己的 AI,
     * 由那个 AI 去配本机 MCP。含明文令牌,故只走剪贴板、不上屏。
     */
    public String accessPrompt() {
        return McpAccessPrompt.build(endpoint(), token());
    }

    // ---- HTTP 线程回调 ----

    /** 收到任何请求都刷新活跃时刻(ping 也算——它正是客户端用来证明自己还在的)。 */
    void touch() {
        lastActivityMs = System.currentTimeMillis();
    }

    /** initialize 握手:记下对方是谁("Claude Desktop 1.2.3")。 */
    void handshake(String name) {
        clientName = name;
        touch();
    }
}
