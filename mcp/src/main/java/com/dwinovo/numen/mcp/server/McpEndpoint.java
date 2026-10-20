package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.mcp.McpJson;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 远程端点:MCP 官方 Java SDK 的 Streamable HTTP 传输挂在内嵌的 Tomcat 上,一个端点 {@code /mcp}。协议(版本协商、会话、SSE 流、
 * 进度、征询、工具注解)全是 SDK 的;这里只补 SDK 不管的:
 * <ul>
 *   <li><b>认人</b>:每个请求要 {@code Authorization: Bearer <令牌>}({@link McpTokens}),令牌不进查询串;没认出令牌的按来源地址限流
 *       并合并审计({@link McpStrangers});带 {@code Origin} 的必须是访问这个地址的同一个源(防 DNS 重绑定),否则 403;</li>
 *   <li><b>谁带着什么</b>:每张令牌一个 SDK 服务器(会话因此不会跨令牌),带着它这位主人、这个身份看得到的工具({@link McpHost#tools});
 *       客户端列工具时现算一次,变了就增删,SDK 会通知客户端工具表变了;</li>
 *   <li><b>限流与审计</b>:接入与每次工具调用受 {@link McpLimits} 限,每次接入与调用记一条审计({@link McpAudit}),只记编号、令牌编号、
 *       工具名、成败与字节数;</li>
 *   <li><b>谁连着</b>:{@link McpPresence};</li>
 *   <li><b>问人</b>:{@link #ask} 经这位主人某个声明了 elicitation 的客户端会话弹给坐在电脑前的人。</li>
 * </ul>
 *
 * <p>线程:SDK 的同步工具调用在它的弹性线程池上跑,可以阻塞;{@link McpHost} 的调用会占一条线程等到做完。
 */
public final class McpEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger("Numen");

    /** 请求体上限:一段程序或一句话远用不到,挡住灌内存的。 */
    private static final int MAX_BODY_BYTES = 1 << 20;
    /** 保活:SDK 隔这么久 ping 一次开着的会话,写不出去就是对方走了。 */
    private static final Duration KEEPALIVE = Duration.ofSeconds(15);
    /** 服务端向客户端发的请求(征询)最久等人多久;SDK 的默认值是给机器回的,人要看清弹窗。 */
    private static final Duration ASK_TIMEOUT = Duration.ofMinutes(10);
    /** 没有会话的闲置会话 SDK 多久清一次。 */
    private static final Duration SESSION_SWEEP = Duration.ofMinutes(1);
    private static final String TOKEN_ATTRIBUTE = "numen.mcp.token";

    private final McpConfig config;
    private final McpTokens tokens;
    private final McpLimits limits;
    private final McpAudit audit;
    private final McpStrangers strangers;
    private final McpPresence presence;
    private final McpHost host;
    private final McpJsonMapper json = McpJson.MAPPER;
    /** 令牌编号 → 它的 SDK 服务器。 */
    private final Map<String, Hosted> hosted = new ConcurrentHashMap<>();
    private Tomcat tomcat;
    private Path baseDir;

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

    public void start() throws Exception {
        // Tomcat 默认要往 JVM 里登记全局的 URL 流处理器工厂(JVM 里只能有一个);游戏的加载器已经登记过了,再登记会抛错。
        // 我们不用 Tomcat 的 jar:/war: 网址,关掉它。
        org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
        // Tomcat 用 JUL 记日志,默认的启动信息在游戏日志里是噪音
        java.util.logging.Logger.getLogger("org.apache").setLevel(java.util.logging.Level.WARNING);
        baseDir = Files.createTempDirectory("numen-mcp-");
        Tomcat t = new Tomcat();
        t.setBaseDir(baseDir.toString());
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(config.port());
        connector.setProperty("address", config.host());
        // Tomcat 的接收与工作线程默认就是守护线程:崩溃、被强杀时 stop 到不了,也不会拖住 JVM 退出
        t.setConnector(connector);
        Context context = t.addContext("", baseDir.toString());
        Tomcat.addServlet(context, "mcp", new Dispatch()).setAsyncSupported(true);
        context.addServletMappingDecoded("/mcp", "mcp");
        FilterDef def = new FilterDef();
        def.setFilterName("auth");
        def.setFilter(new Gate());
        def.setAsyncSupported("true");
        context.addFilterDef(def);
        FilterMap map = new FilterMap();
        map.setFilterName("auth");
        map.addURLPattern("/mcp");
        context.addFilterMap(map);
        try {
            t.start();
        } catch (Exception failed) {
            deleteBaseDir();
            throw failed;
        }
        tomcat = t;
    }

    /** 实际绑到的端口(配置里写 0 让系统挑时用,测试用)。 */
    public int port() {
        return tomcat.getConnector().getLocalPort();
    }

    public void stop() {
        strangers.flushAll();
        for (Hosted h : hosted.values()) {
            h.close();
        }
        hosted.clear();
        if (tomcat != null) {
            try {
                tomcat.stop();
                tomcat.destroy();
            } catch (Exception e) {
                LOG.warn("[numen-mcp] Tomcat 停不干净: {}", e.toString());
            }
            tomcat = null;
        }
        deleteBaseDir();
    }

    private void deleteBaseDir() {
        if (baseDir != null) {
            try (var walk = Files.walk(baseDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // 临时目录,系统会清
            }
            baseDir = null;
        }
    }

    /** 令牌作废、过期的,它的服务器关掉(会话随之结束)。 */
    public void sweep() {
        for (Map.Entry<String, Hosted> e : List.copyOf(hosted.entrySet())) {
            if (!tokens.valid(e.getValue().token)) {
                if (hosted.remove(e.getKey(), e.getValue())) {
                    e.getValue().close();
                }
            }
        }
    }

    // ---- 问人 ----
    // 服务端想问人,只能走客户端会话里的一条流发 elicitation/create;SDK 里一次 tools/call 的流在调用期间开着,调用一收场就关。所以问人
    // 借一次正在进行的调用(程序等着征询的答复时,发起它的那次调用就在飞;外脑空闲时也在 get_events 的长等待里)。此刻没有能借的,
    // 这条问题先挂着,下一次调用开始时再问;没有哪个客户端答得了(没声明 elicitation),它就一直挂到宿主撤掉(主人进游戏答或到期)。

    private record Waiting(UUID owner, UUID subject, McpQuestion question, Consumer<McpQuestion.Answer> answered) {}

    private final Map<Long, Waiting> waiting = new ConcurrentHashMap<>();

    /**
     * 问这位主人坐在客户端前的人。借这位主人某张作用域管着 {@code subject} 的大脑令牌、声明了会答表单式 elicitation 的客户端
     * 正在进行的调用去问;人的答复(或没答出来)交给 {@code answered}。
     *
     * @param id 这条问题的号,撤销({@link #withdraw})用
     */
    public void ask(long id, UUID owner, UUID subject, McpQuestion question, Consumer<McpQuestion.Answer> answered) {
        waiting.put(id, new Waiting(owner, subject, question, answered));
        deliver(id);
    }

    /** 这一条不用问了(主人在别处答了、到期):还没问出去的撤掉。已经弹给人的收不回——SDK 没有取消 elicitation 的口。 */
    public void withdraw(long id) {
        waiting.remove(id);
    }

    /** 一次调用开始了(它的流可以借):挂着的问题试着问出去。 */
    private void deliverAll() {
        for (Long id : List.copyOf(waiting.keySet())) {
            deliver(id);
        }
    }

    private void deliver(long id) {
        Waiting w = waiting.get(id);
        if (w == null) {
            return;
        }
        for (McpToken brain : presence.brains(w.owner())) {
            Hosted h = hosted.get(brain.id());
            if (h == null || !brain.scope().covers(w.subject())) {
                continue;
            }
            McpSyncServerExchange exchange = h.borrow();
            if (exchange == null || waiting.remove(id, w) == false) {
                continue;
            }
            Thread.ofVirtual().name("numen-mcp-ask").start(() -> {
                try {
                    w.answered().accept(answerOf(exchange.createElicitation(McpSchema.ElicitFormRequest
                            .builder(w.question().message(), schemaOf(w.question())).build())));
                } catch (RuntimeException unreachable) {
                    // 借来的那次调用在人答之前收场了(流关了):这条问题放回去,下一次调用再问
                    LOG.debug("[numen-mcp] 征询没问成,等下一次调用再问: {}", unreachable.toString());
                    waiting.putIfAbsent(id, w);
                }
            });
            return;
        }
    }

    // ---- 每张令牌一个 SDK 服务器 ----

    private Hosted hostedFor(McpToken token) {
        return hosted.computeIfAbsent(token.id(), id -> new Hosted(token));
    }

    private final class Hosted {
        final McpToken token;
        final HttpServletStreamableServerTransportProvider transport;
        final McpSyncServer server;
        /** 已登记给 SDK 的工具,按名字。 */
        final Map<String, McpHost.ToolInfo> registered = new LinkedHashMap<>();
        /** 此刻正在进行的调用里,客户端答得了征询的那些(它们的流开着,可以借来问人)。 */
        private final java.util.Set<McpSyncServerExchange> borrowable = ConcurrentHashMap.newKeySet();

        Hosted(McpToken token) {
            this.token = token;
            this.transport = HttpServletStreamableServerTransportProvider.builder()
                    .jsonMapper(json)
                    .mcpEndpoint("/mcp")
                    .contextExtractor(request -> io.modelcontextprotocol.common.McpTransportContext.create(
                            Map.of(TOKEN_ATTRIBUTE, request.getAttribute(TOKEN_ATTRIBUTE))))
                    .keepAliveInterval(KEEPALIVE)
                    .maxRequestSize(MAX_BODY_BYTES)
                    .sessionSweepInterval(SESSION_SWEEP)
                    .build();
            McpHost.Identity who = host.identity();
            McpSchema.Implementation info = new McpSchema.Implementation(who.name(), who.title(), who.version(),
                    who.description(), List.of(icon(who.iconPng())), null);
            this.server = McpServer.sync(transport)
                    .jsonMapper(json)
                    .jsonSchemaValidator(McpJson.SCHEMA_VALIDATOR)
                    .serverInfo(info)
                    .instructions(host.instructions())
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                    .requestTimeout(ASK_TIMEOUT)
                    // 参数在宿主那一头校验(错了作为工具执行错误回给模型),不在这里按登记的 schema 先挡一道
                    .validateToolInputs(false)
                    .build();
        }

        void close() {
            try {
                server.closeGracefully();
            } catch (RuntimeException e) {
                LOG.warn("[numen-mcp] 令牌 {} 的服务器关不干净: {}", token.id(), e.toString());
            }
        }

        /** 把这张令牌此刻该看到的工具对上 SDK 里登记的:多的撤掉,少的与变了的登记。 */
        synchronized void refresh() throws Exception {
            Map<String, McpHost.ToolInfo> now = new LinkedHashMap<>();
            for (McpHost.ToolInfo tool : host.tools(token)) {
                if (token.role() != McpToken.Role.BRAIN || !config.isHidden(tool.name())) {
                    now.put(tool.name(), tool);
                }
            }
            for (String name : new ArrayList<>(registered.keySet())) {
                if (!now.containsKey(name)) {
                    server.removeTool(name);
                    registered.remove(name);
                }
            }
            for (McpHost.ToolInfo tool : now.values()) {
                if (!tool.equals(registered.get(tool.name()))) {
                    server.addTool(spec(tool));
                    registered.put(tool.name(), tool);
                }
            }
        }

        McpSyncServerExchange borrow() {
            return borrowable.stream().findFirst().orElse(null);
        }

        private McpServerFeatures.SyncToolSpecification spec(McpHost.ToolInfo info) {
            McpHost.Hints h = info.hints();
            McpSchema.Tool tool = McpSchema.Tool.builder(info.name(), json, info.inputSchemaJson())
                    .title(info.title())
                    .description(info.description())
                    .annotations(new McpSchema.ToolAnnotations(null, h.readOnly(), h.destructive(), h.idempotent(),
                            h.openWorld(), null))
                    .icons(List.of(icon(host.identity().iconPng())))
                    .build();
            return McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                    .callHandler((exchange, request) -> call(this, exchange, request)).build();
        }
    }

    /** 一次 tools/call:限流、审计、交给宿主做,把结果装成 SDK 的结果。 */
    private McpSchema.CallToolResult call(Hosted h, McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        McpToken token = h.token;
        String name = request.name();
        String argsJson = request.arguments() == null ? "{}" : toJson(request.arguments());
        int bytesIn = argsJson.length();
        McpLimits.Permit permit = limits.acquire(token.id());
        if (permit.refusal() != null) {
            audit.called(exchange.sessionId(), token, name, null, false, bytesIn, 0);
            return result(McpHost.Result.text("rate limited: " + permit.refusal() + ". Wait a few seconds and call again.", true));
        }
        boolean askable = askableOf(exchange);
        try {
            if (askable) {
                h.borrowable.add(exchange);
                deliverAll();
            }
            McpHost.Result result;
            try {
                result = host.call(token, name, argsJson, config.callTimeoutSeconds(), progressOf(exchange, request));
            } catch (TimeoutException te) {
                result = McpHost.Result.text("timed out waiting for the action to finish", true);
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                result = McpHost.Result.text("the endpoint is shutting down", true);
            } catch (Exception failed) {
                result = McpHost.Result.text("call failed: " + failed.getMessage(), true);
            }
            McpSchema.CallToolResult out = result(result);
            audit.called(exchange.sessionId(), token, name, result.subject(), !result.error(), bytesIn,
                    result.texts().stream().mapToInt(String::length).sum());
            return out;
        } finally {
            h.borrowable.remove(exchange);
            permit.close();
        }
    }

    private static boolean askableOf(McpSyncServerExchange exchange) {
        McpSchema.ClientCapabilities caps = exchange.getClientCapabilities();
        McpSchema.ClientCapabilities.Elicitation e = caps == null ? null : caps.elicitation();
        return e != null && (e.form() != null || e.url() == null);
    }

    private McpHost.Progress progressOf(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Object progressToken = request.progressToken();
        if (progressToken == null) {
            return McpHost.Progress.NONE;
        }
        AtomicLong sent = new AtomicLong();
        return message -> exchange.progressNotification(new McpSchema.ProgressNotification(progressToken,
                (double) sent.incrementAndGet(), null, message, null));   // progress 必须逐条增大;总量不知道,不写
    }

    private static McpSchema.CallToolResult result(McpHost.Result r) {
        List<McpSchema.Content> content = new ArrayList<>();
        r.texts().forEach(text -> content.add(new McpSchema.TextContent(text)));
        return McpSchema.CallToolResult.builder().content(content).isError(r.error()).build();
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static McpSchema.Icon icon(byte[] png) {
        // PNG 的 IHDR:第 16 字节起是宽高(各 4 字节,大端)
        int w = ((png[16] & 0xff) << 24) | ((png[17] & 0xff) << 16) | ((png[18] & 0xff) << 8) | (png[19] & 0xff);
        int h = ((png[20] & 0xff) << 24) | ((png[21] & 0xff) << 16) | ((png[22] & 0xff) << 8) | (png[23] & 0xff);
        return new McpSchema.Icon("data:image/png;base64," + Base64.getEncoder().encodeToString(png), "image/png",
                List.of(w + "x" + h), null);
    }

    /** 选择题的表单:一栏带标题的单选(新版 EnumSchema 的 oneOf),一栏可选的附言。 */
    private static Map<String, Object> schemaOf(McpQuestion q) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (McpQuestion.Choice c : q.choices()) {
            options.add(Map.of("const", c.value(), "title", c.title()));
        }
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("type", "string");
        decision.put("title", q.choiceTitle());
        decision.put("oneOf", options);
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("type", "string");
        note.put("title", q.noteTitle());
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("decision", decision);
        props.put("note", note);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("decision"));
        return schema;
    }

    /** accept 按选的那项,decline 是明确拒绝,cancel(关掉弹窗没选)不算答复。 */
    private static McpQuestion.Answer answerOf(McpSchema.ElicitResult result) {
        return switch (result.action()) {
            case ACCEPT -> {
                Map<String, Object> content = result.content() == null ? Map.of() : result.content();
                Object picked = content.get("decision");
                Object note = content.get("note");
                yield new McpQuestion.Answer(picked instanceof String s ? s : null, false,
                        note instanceof String n ? n : "");
            }
            case DECLINE -> new McpQuestion.Answer(null, true, "");
            default -> new McpQuestion.Answer(null, false, "");
        };
    }

    // ---- HTTP 层 ----

    /** 一个 Servlet,把请求交给请求者那张令牌的 SDK 服务器。 */
    private final class Dispatch extends HttpServlet {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            McpToken token = (McpToken) req.getAttribute(TOKEN_ATTRIBUTE);
            hostedFor(token).transport.service(req, resp);
        }
    }

    /** 过滤器:认人、限流、审计、在场记录;SDK 看到的是已经认过人的请求。 */
    private final class Gate implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse resp = (HttpServletResponse) response;
            if (!originAllowed(req)) {
                resp.setStatus(403);
                return;
            }
            String address = req.getRemoteAddr();
            // 每个请求都照常验令牌;验过的不受地址牵连(反向代理后面所有人共用一个地址),验不过的才记到地址上,
            // 这个地址的失败满了,它的未认证请求回 429
            boolean throttled = strangers.blocked(address);
            McpToken token = authenticate(req, address);
            if (token == null) {
                if (throttled) {
                    resp.setHeader("Retry-After", "60");
                    resp.setStatus(429);
                } else {
                    resp.setHeader("WWW-Authenticate", "Bearer realm=\"numen\"");
                    resp.setStatus(401);
                }
                return;
            }
            req.setAttribute(TOKEN_ATTRIBUTE, token);
            presence.begin(token);
            try {
                switch (req.getMethod().toUpperCase(Locale.ROOT)) {
                    case "POST" -> post(req, resp, chain, token);
                    case "DELETE" -> {
                        chain.doFilter(req, resp);
                        presence.ended(token);
                    }
                    default -> chain.doFilter(req, resp);
                }
            } finally {
                presence.end(token);
            }
        }

        private void post(HttpServletRequest req, HttpServletResponse resp, FilterChain chain, McpToken token)
                throws IOException, ServletException {
            byte[] body = req.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                resp.setStatus(413);
                return;
            }
            Replay replay = new Replay(req, body);
            McpSchema.JSONRPCMessage message = parse(body);
            String method = message instanceof McpSchema.JSONRPCRequest r ? r.method() : "";
            switch (method) {
                case "initialize" -> initialize(replay, resp, chain, token, (McpSchema.JSONRPCRequest) message);
                case "tools/list" -> {
                    try {
                        hostedFor(token).refresh();
                    } catch (Exception failed) {
                        // 工具表取不到(宿主停着、主线程长时间不应、宿主自己抛了):如实把原因交给客户端,不拿旧表搪塞
                        LOG.warn("[numen-mcp] 取不到令牌 {} 的工具表: {}", token.id(), failed.toString());
                        listFailed(resp, (McpSchema.JSONRPCRequest) message, failed);
                        return;
                    }
                    chain.doFilter(replay, resp);
                }
                default -> chain.doFilter(replay, resp);
            }
        }

        private void listFailed(HttpServletResponse resp, McpSchema.JSONRPCRequest request, Exception failed)
                throws IOException {
            McpSchema.JSONRPCResponse error = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
                    new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
                            "The companion's tools cannot be listed right now: " + failed, null));
            resp.setStatus(200);
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().write(toJson(error));
        }

        private void initialize(HttpServletRequest req, HttpServletResponse resp, FilterChain chain, McpToken token,
                                McpSchema.JSONRPCRequest message) throws IOException, ServletException {
            McpLimits.Permit permit = limits.acquire(token.id());
            try {
                if (permit.refusal() != null) {
                    audit.refused("rate limited", token.id());
                    resp.setHeader("Retry-After", "5");
                    resp.setStatus(429);
                    resp.getWriter().write(permit.refusal());
                    return;
                }
                String client = clientLabel(message.params());
                presence.named(token, client);
                SessionCapture capture = new SessionCapture(resp);
                chain.doFilter(req, capture);
                audit.connected(capture.sessionId == null ? "-" : capture.sessionId, token, client);
            } finally {
                permit.close();
            }
        }
    }

    private McpToken authenticate(HttpServletRequest req, String address) {
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            strangers.failed(address, "no bearer token");
            return null;
        }
        return tokens.authenticate(auth.substring(7).trim()).orElseGet(() -> {
            strangers.failed(address, "unknown or expired token");
            return null;
        });
    }

    /**
     * 防 DNS 重绑定:只绑回环时,请求的 {@code Host} 必须是回环的名字(重绑定来的请求 Host 是攻击者的域名);
     * 浏览器发的请求带 {@code Origin},带了就必须是访问这个地址的同一个源。合法的客户端是命令行或桌面程序,不带 Origin。
     */
    private boolean originAllowed(HttpServletRequest req) {
        String host = req.getHeader("Host");
        if (!config.lanExposed() && !loopbackHost(host)) {
            return false;
        }
        String origin = req.getHeader("Origin");
        if (origin == null) {
            return true;
        }
        try {
            URI uri = URI.create(origin);
            String originHost = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
            return host != null && host.equalsIgnoreCase(originHost);
        } catch (IllegalArgumentException bad) {
            return false;
        }
    }

    /** {@code Host} 头的主机名(去掉端口)是回环的名字吗。 */
    private static boolean loopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String name = host.startsWith("[") ? host.substring(0, host.indexOf(']') + 1)
                : host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        return name.equalsIgnoreCase("localhost") || name.equals("127.0.0.1") || name.equals("[::1]");
    }

    private McpSchema.JSONRPCMessage parse(byte[] body) {
        try {
            return McpSchema.deserializeJsonRpcMessage(json, new String(body, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException notJsonRpc) {
            return null;   // 看不懂的交给 SDK 回它的错误
        }
    }

    /** {@code params.clientInfo} → "Claude Desktop 1.2.3";没报名字就叫 unknown client。 */
    private static String clientLabel(Object params) {
        if (params instanceof Map<?, ?> p && p.get("clientInfo") instanceof Map<?, ?> info) {
            String name = info.get("name") instanceof String s ? s : "unknown client";
            String version = info.get("version") instanceof String s ? s : "";
            return version.isBlank() ? name : name + " " + version;
        }
        return "unknown client";
    }

    /** 请求体已经被过滤器读走看过,原样重放给 SDK。 */
    private static final class Replay extends HttpServletRequestWrapper {
        private final byte[] body;

        Replay(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("the body is already read");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    /** 记下 initialize 的答复里 SDK 开的会话号,审计要。 */
    private static final class SessionCapture extends HttpServletResponseWrapper {
        String sessionId;

        SessionCapture(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setHeader(String name, String value) {
            capture(name, value);
            super.setHeader(name, value);
        }

        @Override
        public void addHeader(String name, String value) {
            capture(name, value);
            super.addHeader(name, value);
        }

        private void capture(String name, String value) {
            if ("Mcp-Session-Id".equalsIgnoreCase(name)) {
                sessionId = value;
            }
        }
    }
}
