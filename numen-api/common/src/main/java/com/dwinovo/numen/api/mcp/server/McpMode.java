package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.permission.ConsentDesk;
import com.dwinovo.numen.api.permission.ConsentVenues;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 远程端点宿主的唯一状态源——界面、驾驶席闸门、命令、HTTP 服务器共读的一处真相。
 *
 * <h2>端点 = 服务器开关</h2>
 * {@link McpConfig#enabled} 开着,HTTP 服务器就在跑;关着,没人连得上。设置里拨动开关 → {@link #setEnabled}
 * 即时起停并写回配置文件。专用服默认关,服主自己开。
 *
 * <h2>谁在驾驶:{@link #driving}</h2>
 * 驾驶席按同伴算:她主人名下有连着的、身份是大脑的会话,且其令牌的作用域管着她,外部大脑就坐在她的驾驶席上,
 * 内置大脑一轮都不开(循环内核每次推进都现问它,在它手里时停牌 {@code EXTERNAL})。会话断开了,
 * 驾驶席现算地交还内脑——"连着"的口径只在 {@link McpSessions} 一处。
 *
 * <h2>线程</h2>
 * {@link #enabled()} 被渲染线程和游戏主线程高频读,故用 volatile 裸字段;会话由 HTTP 线程写入、主线程读。
 */
public final class McpMode {

    private static final McpMode INSTANCE = new McpMode();

    /** 谁连上了、谁断开了:装载方(服务端)据此告诉主人,并重算他在不在。每刻在主线程上查一次边沿。 */
    public interface Watch {

        /** 这位主人的外部大脑连上了。 */
        void connected(UUID owner);

        /** 这位主人的外部大脑断开了(最后一个会话也不再连着)。 */
        void gone(UUID owner);
    }

    private volatile boolean enabled;
    private volatile String lastError;       // 起服失败的原因,给 UI 显示

    private Home home;
    private McpConfig config = McpConfig.disabledDefault();
    private McpServer server;
    private McpTokens tokens;
    private McpSessions sessions;
    private McpLimits limits;
    private McpChannels channels;
    private McpAudit audit;
    private McpStrangers strangers;
    private Function<UUID, ExternalHands> hands;
    private Watch watch;
    /** 上一刻有大脑连着的主人,找连上、断开的边沿。只在主线程碰。 */
    private Set<UUID> connectedOwners = Set.of();

    /** 外部大脑客户端上的人:经 elicitation 问。服务器在跑才有;跟着服务器起停。 */
    private final ConsentVenues.Venue elicitation = new ConsentVenues.Venue() {
        @Override
        public void show(UUID owner, ConsentVenues.Offer offer) {
            McpServer s = server;
            if (s != null) {
                s.elicit(owner, offer);
            }
        }

        @Override
        public void clear(UUID owner, long id, ConsentDesk.Withdrawal why) {
            McpServer s = server;
            if (s != null) {
                s.withdraw(id);
            }
        }
    };

    private McpMode() {}

    public static McpMode instance() {
        return INSTANCE;
    }

    // ---- 生命周期 ----

    /** 装载配置:记住服务器的家、同伴的手和通知口,enabled 则立刻起服。大脑所在的那一端启动时调一次。 */
    void bootstrap(Home serverHome, Function<UUID, ExternalHands> hands, Watch watch) {
        this.home = serverHome;
        this.hands = hands;
        this.watch = watch;
        this.config = McpConfig.load(serverHome.file(CONFIG_FILE), serverHome.io());
        this.tokens = new McpTokens(serverHome, System::currentTimeMillis);
        this.sessions = new McpSessions(System::currentTimeMillis);
        this.limits = new McpLimits(config.limits(), System::currentTimeMillis);
        this.audit = new McpAudit(serverHome);
        this.strangers = new McpStrangers(audit, System::currentTimeMillis);
        this.channels = new McpChannels();
        this.connectedOwners = Set.of();
        if (config.enabled()) {
            startServer();
        } else {
            this.enabled = false;
            Constants.LOG.info("[numen-mcp] 远程端点关闭(服主在 {} 里开)", CONFIG_FILE);
        }
    }

    /** 配置文件名,在服务器的家里。 */
    static final String CONFIG_FILE = "mcp_server.json";

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
        config.save(home.file(CONFIG_FILE), home.io());
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
    public boolean applySettings(String host, int port, int callTimeoutSeconds, java.util.List<String> hiddenTools) {
        boolean wasRunning = enabled;
        if (wasRunning) {
            stopServer();
        }
        config = config.withEndpoint(host, port, callTimeoutSeconds).withHiddenTools(hiddenTools);
        boolean ok = true;
        if (wasRunning) {
            ok = startServer();
        }
        config = config.withEnabled(enabled);
        config.save(home.file(CONFIG_FILE), home.io());
        return ok;
    }

    /** 当前配置的只读快照——设置页据此填初值。 */
    public McpConfig config() {
        return config;
    }

    /** 令牌库;宿主没装载时是 null。 */
    public McpTokens tokens() {
        return tokens;
    }

    /** 大脑所在的那一端关停时放手:服务器停掉,会话结束,不碰配置文件里的开关(下次起来照旧)。 */
    public void shutdown() {
        stopServer();
        sessions = null;
        tokens = null;
        limits = null;
        audit = null;
        strangers = null;
        channels = null;
        hands = null;
        watch = null;
        home = null;
        connectedOwners = Set.of();
        config = McpConfig.disabledDefault();
        Constants.LOG.info("[numen-mcp] 远程端点宿主随这一端关停");
    }

    private boolean startServer() {
        lastError = null;
        limits.configure(config.limits());
        McpServer s = new McpServer(config, tokens, limits, sessions, audit, strangers, channels, hands);
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
        ConsentVenues.register(elicitation);
        Constants.LOG.info("[numen-mcp] 远程端点已开启,{};每张令牌由 /numen token 发", endpoint());
        if (config.lanExposed()) {
            Constants.LOG.warn("[numen-mcp] 端点绑在 {},是明文 HTTP:令牌会在网上明文传输。"
                    + "建议在前面放反向代理加 HTTPS,端点本身只绑回环", config.host());
        }
        return true;
    }

    private void stopServer() {
        ConsentVenues.unregister(elicitation);
        if (server != null) {
            server.stop();
            server = null;
        }
        enabled = false;
        Constants.LOG.info("[numen-mcp] 远程端点已关闭");
    }

    // ---- 状态查询(UI / 闸门) ----

    /** 端点(=服务器)是否开启。 */
    public boolean enabled() {
        return enabled;
    }

    /** 这只同伴的驾驶席此刻在外部大脑手里吗。 */
    public boolean driving(UUID owner, UUID companion) {
        McpSessions s = sessions;
        return enabled && s != null && s.drives(owner, companion);
    }

    /** 这位主人有外部大脑连着(任何一只同伴上):"主人在不在"的一半,另一半是人在不在游戏里。 */
    public boolean brainConnected(UUID owner) {
        McpSessions s = sessions;
        return enabled && s != null && s.brainConnected(owner);
    }

    /**
     * 每刻一次(大脑所在的那一端驱动):令牌作废、过期的会话结束,外部大脑连上、断开的边沿。
     * 主人有知情权——外部大脑没了得说一声,不能让她无声变成"已读不回"。
     */
    public void tick() {
        McpSessions s = sessions;
        if (s == null) {
            return;
        }
        s.sweep(tokens::valid);
        strangers.flushClosed();
        Set<UUID> now = enabled ? s.brainOwners() : Set.of();
        for (UUID owner : now) {
            if (!connectedOwners.contains(owner)) {
                watch.connected(owner);
            }
        }
        for (UUID owner : new HashSet<>(connectedOwners)) {
            if (!now.contains(owner)) {
                watch.gone(owner);
            }
        }
        connectedOwners = now;
    }

    /** 端点的地址,给界面与提示词。 */
    public String endpoint() {
        return "http://" + config.host() + ":" + config.port() + "/mcp";
    }

    /** 最近一次接入的客户端自报的名字,或 null(还没人接入)。 */
    public String clientName() {
        McpSessions s = sessions;
        if (s == null) return null;
        McpSessions.Session latest = null;
        for (McpSessions.Session one : s.all()) {
            if (latest == null || one.lastSeen() > latest.lastSeen()) latest = one;
        }
        return latest == null ? null : latest.client;
    }

    /** 最近一次收到请求的时刻(毫秒),0 = 从未。 */
    public long lastActivityMs() {
        McpSessions s = sessions;
        long last = 0;
        if (s != null) {
            for (McpSessions.Session one : s.all()) last = Math.max(last, one.lastSeen());
        }
        return last;
    }

    /** 最近一次起服失败的原因,或 null。 */
    public String lastError() {
        return lastError;
    }

    /** 「复制接入提示词」的内容:内嵌当前端点,令牌由主人在游戏里另发。 */
    public String accessPrompt() {
        return McpAccessPrompt.build(endpoint());
    }
}
