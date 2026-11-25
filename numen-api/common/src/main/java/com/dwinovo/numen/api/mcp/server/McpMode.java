package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.mcp.server.McpTokens;
import com.dwinovo.numen.mcp.server.McpLimits;
import com.dwinovo.numen.mcp.server.McpAudit;
import com.dwinovo.numen.mcp.server.McpStrangers;
import com.dwinovo.numen.mcp.server.McpConfig;
import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.permission.ConsentAnswer;
import com.dwinovo.numen.api.permission.ConsentDesk;
import com.dwinovo.numen.api.mind.ConsentVenues;
import com.dwinovo.numen.mcp.server.McpEndpoint;
import com.dwinovo.numen.mcp.server.McpPresence;
import com.dwinovo.numen.mcp.server.McpQuestion;

import java.util.UUID;

/**
 * 外部大脑的端点宿主,在主人的客户端上:这台电脑上的 HTTP 服务器(默认绑 127.0.0.1)、令牌、渠道信箱,和谁在驾驶的唯一判据。
 * 外部大脑经它驱动她,走的是内置大脑同一条座位({@code LoopHost});服务端不知道驾驶的是哪一个。
 *
 * <h2>端点 = 开关</h2>
 * {@link McpConfig#enabled} 开着,HTTP 服务器就在跑;关着,没人连得上。设置里拨动开关 → {@link #setEnabled}
 * 即时起停并写回配置文件。默认关。
 *
 * <h2>谁在驾驶:{@link #driving}</h2>
 * 驾驶席按同伴算:主人名下有连着的、身份是大脑的令牌,且其作用域管着她,外部大脑就坐在她的驾驶席上,
 * 内置大脑一轮都不开({@code LoopHost.tick} 每刻现问它,在它手里时停牌 {@code EXTERNAL})。外部大脑断开了,
 * 驾驶席现算地交还内脑——"连着"的口径只在 {@link McpPresence} 一处。
 *
 * <p>线程:{@link #enabled()} 被渲染线程和客户端主线程高频读,故用 volatile 裸字段;在场记录由 HTTP 线程写入、主线程读。
 */
public final class McpMode {

    private volatile boolean enabled;
    private volatile String lastError;       // 起服失败的原因,给 UI 显示

    private final Home home;
    private final UUID owner;
    private final ExternalHands hands;
    private final ConsentVenues venues;
    private final String version;
    private final McpTokens tokens;
    private final McpPresence presence;
    private final McpLimits limits;
    private final McpChannels channels = new McpChannels();
    private final McpAudit audit;
    private final McpStrangers strangers;
    private McpConfig config;
    private McpEndpoint endpoint;

    /** 配置文件名,在端点的家里。 */
    static final String CONFIG_FILE = "mcp_server.json";

    /** 选项的值:问人的表单里三个选项,答复回来按它们认。 */
    private static final String ALLOW = "allow";
    private static final String ALLOW_AND_REMEMBER = "allow_and_remember";
    private static final String DENY = "deny";

    /**
     * 外部大脑客户端上的人:经 elicitation 问。端点在跑才有;跟着端点起停。
     * 外部大脑驾驶时,要问主人的事问的是坐在电脑前的人,不是驾驶席上的模型:经大脑令牌的客户端开着的流去问
     * ({@link McpEndpoint#ask}),主人在别处答了或到期就撤回;人的选择走和答复框同一条路({@link ExternalHands#answer})。
     * 客户端没声明会答 elicitation 的,征询就挂在答复框里,到期按拒绝。这里没有任何让模型自己答的口。
     */
    private final ConsentVenues.Venue elicitation = new ConsentVenues.Venue() {
        @Override
        public void show(ConsentVenues.Offer offer) {
            McpEndpoint e = endpoint;
            if (e != null) {
                e.ask(offer.id(), owner, offer.companion(), question(offer), answer -> settle(offer, answer));
            }
        }

        @Override
        public void clear(long id, ConsentDesk.Withdrawal why) {
            McpEndpoint e = endpoint;
            if (e != null) {
                e.withdraw(id);
            }
        }
    };

    private static McpQuestion question(ConsentVenues.Offer offer) {
        StringBuilder message = new StringBuilder(offer.companionName()).append(" asks for your permission (#")
                .append(offer.id()).append("):\n").append(offer.doing());
        if (!offer.remembers().isEmpty()) {
            message.append("\n\"Allow and remember\" also adds: ").append(String.join("; ", offer.remembers()));
        }
        return new McpQuestion(message.toString(), java.util.List.of(
                new McpQuestion.Choice(ALLOW, "Allow once"),
                new McpQuestion.Choice(ALLOW_AND_REMEMBER, "Allow and remember"),
                new McpQuestion.Choice(DENY, "Deny")), "Your answer", "Tell her what to do instead (only when denying)");
    }

    /**
     * 人的选择交给答复的口:选了哪项就是哪项,明确拒绝是拒绝,关掉弹窗没选不算答复——征询仍挂着,主人可以在答复框里答,到期按拒绝。
     * 附言只随拒绝。
     */
    private void settle(ConsentVenues.Offer offer, McpQuestion.Answer answer) {
        ConsentAnswer.Decision decision;
        if (answer.declined()) {
            decision = ConsentAnswer.Decision.DENY;
        } else if (answer.choice() == null) {
            return;
        } else {
            decision = switch (answer.choice()) {
                case ALLOW -> ConsentAnswer.Decision.ALLOW_ONCE;
                case ALLOW_AND_REMEMBER -> ConsentAnswer.Decision.ALLOW_REMEMBER;
                case DENY -> ConsentAnswer.Decision.DENY;
                default -> null;
            };
        }
        if (decision != null) {
            hands.answer(offer.companion(), offer.id(), decision,
                    decision == ConsentAnswer.Decision.DENY ? answer.note() : "");
        }
    }

    /**
     * @param home    端点的家:配置、令牌库、审计都在这里
     * @param owner   这台电脑上的玩家:令牌只认他名下的同伴
     * @param hands   她们的手,端点对她们的全部接触都经它
     * @param venues  征询的场所:端点在跑时,外部大脑客户端上的人也是一个
     * @param version Numen API 这个模组的版本,serverInfo 里报
     */
    public McpMode(Home home, UUID owner, ExternalHands hands, ConsentVenues venues, String version) {
        this.home = home;
        this.owner = owner;
        this.hands = hands;
        this.venues = venues;
        this.version = version;
        this.config = McpConfig.load(home.file(CONFIG_FILE), home.io());
        this.tokens = new McpTokens(home.dir(), home.io(), System::currentTimeMillis);
        this.presence = new McpPresence(System::currentTimeMillis);
        this.limits = new McpLimits(config.limits(), System::currentTimeMillis);
        this.audit = new McpAudit(home.dir(), home.io());
        this.strangers = new McpStrangers(audit, System::currentTimeMillis);
    }

    // ---- 生命周期 ----

    /** 配置里开着就起服。连上服务器、大脑建起来之后调一次。 */
    public void start() {
        if (config.enabled()) {
            startServer();
        } else {
            Constants.LOG.info("[numen-mcp] the external brain endpoint is off (turn it on in the settings or {})", CONFIG_FILE);
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

    /** 令牌库。 */
    public McpTokens tokens() {
        return tokens;
    }

    /** 断线时放手:服务器停掉,会话结束,不碰配置文件里的开关(下次起来照旧)。 */
    public void shutdown() {
        stopServer();
        Constants.LOG.info("[numen-mcp] the external brain endpoint shuts down with this connection");
    }

    private boolean startServer() {
        lastError = null;
        limits.configure(config.limits());
        McpEndpoint s = new McpEndpoint(config, tokens, limits, audit, strangers, presence,
                new ExternalHost(hands, channels, version));
        try {
            s.start();
        } catch (Exception ex) {
            lastError = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            Constants.LOG.error("[numen-mcp] failed to start on {}:{} — {}", config.host(), config.port(), ex.toString());
            enabled = false;
            return false;
        }
        endpoint = s;
        enabled = true;
        venues.register(elicitation);
        Constants.LOG.info("[numen-mcp] the remote endpoint is on, {}; each token is issued with /token in the companion chat", endpoint());
        if (config.lanExposed()) {
            Constants.LOG.warn("[numen-mcp] the endpoint is bound to {} over plain HTTP: tokens travel the network in clear text. "
                    + "Put a reverse proxy with HTTPS in front of it and bind the endpoint itself to loopback only", config.host());
        }
        return true;
    }

    private void stopServer() {
        venues.unregister(elicitation);
        if (endpoint != null) {
            endpoint.stop();
            endpoint = null;
        }
        enabled = false;
        Constants.LOG.info("[numen-mcp] the remote endpoint is closed");
    }

    // ---- 状态查询(UI / 闸门) ----

    /** 端点(=服务器)是否开启。 */
    public boolean enabled() {
        return enabled;
    }

    /** 这只同伴的驾驶席此刻在外部大脑手里吗。 */
    public boolean driving(UUID companion) {
        return enabled && presence.drives(owner, companion);
    }

    /** 每个客户端刻一次:令牌作废、过期的会话结束。 */
    public void tick() {
        presence.sweep(tokens::valid);
        McpEndpoint e = endpoint;
        if (e != null) {
            e.sweep();
        }
        strangers.flushClosed();
    }

    /** 端点的地址,给界面与提示词。 */
    public String endpoint() {
        return "http://" + config.host() + ":" + config.port() + "/mcp";
    }

    /** 最近一次接入的客户端自报的名字,或 null(还没人接入)。 */
    public String clientName() {
        McpPresence.Entry latest = null;
        for (McpPresence.Entry one : presence.all()) {
            if (latest == null || one.lastSeen() > latest.lastSeen()) latest = one;
        }
        return latest == null ? null : latest.client();
    }

    /** 最近一次收到请求的时刻(毫秒),0 = 从未。 */
    public long lastActivityMs() {
        long last = 0;
        for (McpPresence.Entry one : presence.all()) last = Math.max(last, one.lastSeen());
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
