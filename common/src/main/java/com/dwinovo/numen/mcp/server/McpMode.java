package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.Constants;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 「外接大脑」模式的唯一状态源——UI、内置大脑闸门、HTTP 服务器三方共读的一处真相。
 *
 * <h2>模式 = 服务器开关</h2>
 * 不另立"模式"概念:{@link McpConfig#enabled} 开着就是模式开着,服务器就在跑。
 * 设置里拨动开关 → {@link #setEnabled} 即时起停服务器并写回配置文件,下次进游戏
 * 自动恢复同一状态。
 *
 * <h2>为什么内置大脑要看这里</h2>
 * 模式开启期间 {@code EntityAgentLoop.tryStartTurn} 直接返回——内置大脑一轮都不开。
 * 这是"两个大脑抢一具身体"的总闸:聊天框禁用只是体验层(主人看得见),而弹幕/QQ 桥接
 * 送进来的 principal 事件 UI 够不着,只有这道闸拦得住。身体层面的互斥另有
 * {@code TaskDispatch} 的"一具身体一件活"闸门兜底,两者各管一层。
 *
 * <h2>线程</h2>
 * {@link #enabled()} 被渲染线程和游戏主线程高频读,故用 volatile 裸字段;
 * 握手信息与活动流由 HTTP 线程写入、渲染线程读快照,见 {@link ActivityFeed}。
 */
public final class McpMode {

    private static final McpMode INSTANCE = new McpMode();

    /** 活动流一行:哪个工具、什么参数、结果摘要。时间戳用于"N 秒前"。 */
    public record Activity(long timeMs, String tool, String args, String summary, boolean error) {}

    /**
     * MCP 工具调用的滚动记录。HTTP 线程写、渲染线程读,故全部方法同步;
     * 纯内存、有上限、不持久化——它是"外面那个大脑此刻在干嘛"的观察窗,
     * 不是对话记录(关游戏即弃是刻意的)。
     */
    public static final class ActivityFeed {

        private static final int CAP = 200;

        private final ArrayDeque<Activity> lines = new ArrayDeque<>();

        synchronized void push(Activity line) {
            lines.addLast(line);
            while (lines.size() > CAP) lines.removeFirst();
        }

        synchronized void clear() {
            lines.clear();
        }

        /** 渲染线程用的只读快照(拷贝,调用方随便遍历)。 */
        public synchronized List<Activity> snapshot() {
            return new ArrayList<>(lines);
        }

        public synchronized boolean isEmpty() {
            return lines.isEmpty();
        }
    }

    private final ActivityFeed feed = new ActivityFeed();

    private volatile boolean enabled;
    private volatile String clientName;      // initialize 握手报的对方名字,null = 还没人连过
    private volatile long lastActivityMs;
    private volatile String lastError;       // 起服失败的原因,给 UI 显示

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
        clientName = null;      // 连接状态随服务器一起归零
        feed.clear();
        Constants.LOG.info("[numen-mcp] 外接大脑模式已关闭,内置大脑恢复接管");
    }

    // ---- 状态查询(UI / 闸门) ----

    /** 模式是否开启——内置大脑的开轮闸门与聊天面板形态都读这个。 */
    public boolean enabled() {
        return enabled;
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

    public ActivityFeed feed() {
        return feed;
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

    void record(String tool, String args, String summary, boolean error) {
        feed.push(new Activity(System.currentTimeMillis(), tool, args, summary, error));
        touch();
    }
}
