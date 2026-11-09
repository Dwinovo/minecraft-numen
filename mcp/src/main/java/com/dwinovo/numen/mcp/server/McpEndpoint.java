package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.mcp.McpThreads;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 远程端点:JDK 自带的 HTTP 服务器上一个 MCP 端点 {@code /mcp},地址与端口取自 {@link McpConfig}。这里只把各个组件拼起来、管起停:
 *
 * <ul>
 *   <li><b>处理链</b>({@link Stage}):查来源({@link OriginGuard})→ 认令牌({@link BearerAuth})→ 管陌生地址({@link StrangerThrottle})
 *       → Streamable HTTP 传输({@link StreamableHttp});</li>
 *   <li><b>会话</b>({@link SessionRegistry}):每个会话属于开它的那张令牌,会话不跨令牌;谁连着汇总在 {@link McpPresence};</li>
 *   <li><b>MCP 方法</b>({@link ServerMethods}):握手、列工具、调工具;工具表按令牌({@link ToolTable}),一批增删只通知一次;
 *       限流 {@link McpLimits},审计 {@link McpAudit};</li>
 *   <li><b>问人</b>({@link Questions}):{@link #ask} 经这位主人某个会答 elicitation 的客户端开的流弹给坐在电脑前的人,{@link #withdraw} 撤回。</li>
 * </ul>
 *
 * <p>线程:每个 HTTP 请求与每个请求的处理各一条线程(都在这里的执行器上);{@link McpHost} 的调用占着一条等到做完。HTTP 服务器的
 * 分派线程是守护线程:崩溃、被强杀时 {@link #stop} 到不了,也不会拖住 JVM 退出。{@link #stop} 先结束所有会话(流随之收场),再关监听,
 * 最后打断还在等宿主的处理线程并等它们散完。
 */
public final class McpEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger("Numen");
    private static final String PATH = "/mcp";
    /** 停服时最久等处理线程散完多久。 */
    private static final long STOP_WAIT_SECONDS = 5;

    private final McpConfig config;
    private final McpTokens tokens;
    private final McpLimits limits;
    private final McpAudit audit;
    private final McpStrangers strangers;
    private final McpPresence presence;
    private final McpHost host;
    private final LongSupplier clock = System::currentTimeMillis;

    private ExecutorService executor;
    private HttpServer server;
    private ToolTable tools;
    private SessionRegistry sessions;
    private Questions questions;

    public McpEndpoint(McpConfig config, McpTokens tokens, McpLimits limits, McpAudit audit, McpStrangers strangers,
                       McpPresence presence, McpHost host) {
        this.config = config;
        this.tokens = tokens;
        this.limits = limits;
        this.audit = audit;
        this.strangers = strangers;
        this.presence = presence;
        this.host = host;
    }

    // ---- 起停 ----

    /**
     * 绑到配置的地址与端口并开始接请求。端口被占用时抛 {@link BindException},原因写明哪个端口、去哪改;不换别的端口。
     */
    public void start() throws IOException, InterruptedException {
        ExecutorService pool = McpThreads.perTask("numen-mcp-");
        ToolTable table = new ToolTable(host, config, tokenId -> sessions.toolsChanged(tokenId));
        SessionRegistry registry = new SessionRegistry(new ServerMethods(host, config, table, limits, audit, presence),
                presence, pool, clock);
        Questions asked = new Questions(presence, registry::of);
        registry.questions(asked);
        Stage.Next chain = Stage.chain(List.of(new OriginGuard(config.lanExposed()), new BearerAuth(tokens),
                new StrangerThrottle(strangers)), new StreamableHttp(registry, presence, audit, clock));
        HttpServer http;
        try {
            http = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        } catch (BindException taken) {
            pool.shutdownNow();
            BindException explained = new BindException("Port " + config.port() + " on " + config.host()
                    + " is already in use by another program. Change \"port\" in mcp_server.json (or on the settings page),"
                    + " or stop the program that uses it.");
            explained.initCause(taken);
            throw explained;
        }
        http.createContext(PATH, exchange -> {
            HttpCall call = new HttpCall(exchange);
            try {
                if (exchange.getRequestURI().getPath().equals(PATH)) {
                    chain.handle(call);
                } else {
                    call.respond(404);
                }
            } catch (RuntimeException bug) {
                // 我们自己的缺陷:如实记下,答 500(答复头还没发出去时),不让客户端对着一个没有答复的连接干等
                LOG.error("[numen-mcp] {} {} failed", exchange.getRequestMethod(), PATH, bug);
                if (exchange.getResponseCode() < 0) {
                    call.respond(500);
                }
            } finally {
                call.close();
            }
        });
        http.setExecutor(pool);
        this.executor = pool;
        this.tools = table;
        this.sessions = registry;
        this.questions = asked;
        this.server = http;
        // 分派线程的守护属性随起它的线程:从 McpThreads 起的(守护)线程上起
        Thread starter = McpThreads.start("numen-mcp-start", http::start);
        starter.join();
    }

    /** 实际绑到的端口(配置里写 0 让系统挑时用,测试用)。 */
    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        strangers.flushAll();
        if (sessions != null) {
            sessions.closeAll();
        }
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    LOG.warn("[numen-mcp] 停服后还有处理线程没散:宿主的调用不响应打断");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }

    /** 令牌作废、过期的会话结束,闲置太久的会话结束。宿主每刻调一次。 */
    public void sweep() {
        sessions.sweep(tokens::valid);
        tools.sweep(tokens::valid);
    }

    // ---- 问人 ----

    /**
     * 问这位主人坐在客户端前的人:经这位主人某张作用域管着 {@code subject} 的大脑令牌、会答表单式 elicitation、开着流的会话去问;
     * 人的答复交给 {@code answered}。此刻没有能问的就挂着,有了再问。
     *
     * @param id 这条问题的号,撤回({@link #withdraw})用
     */
    public void ask(long id, UUID owner, UUID subject, McpQuestion question, Consumer<McpQuestion.Answer> answered) {
        questions.ask(id, owner, subject, question, answered);
    }

    /** 这一条不用问了(主人在别处答了、到期):还没问出去的撤掉,已经弹给人的照规范撤回。 */
    public void withdraw(long id) {
        questions.withdraw(id);
    }

    /** 已经弹给人、答复还没交出去的问题有几条:只读。 */
    public int asking() {
        return questions.asking();
    }
}
