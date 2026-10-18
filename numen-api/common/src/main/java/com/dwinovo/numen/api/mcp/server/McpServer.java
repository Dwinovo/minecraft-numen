package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.inbox.ExternalWait;
import com.dwinovo.numen.api.agent.llm.ToolOutcome;
import com.dwinovo.numen.api.agent.prompt.NumenPrompts;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.brain.Visits;
import com.dwinovo.numen.api.permission.ConsentAnswer;
import com.dwinovo.numen.api.permission.ConsentDesk;
import com.dwinovo.numen.api.permission.ConsentVenues;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * 远程端点:MCP 的 Streamable HTTP(规范 2025-06-18),手写的 JSON-RPC 2.0,跑在 JDK 自带的 HTTP 服务器上——
 * 没有第三方 MCP SDK。一个端点 {@code /mcp}:
 * <ul>
 *   <li>{@code POST}:客户端的请求、通知与对我们请求的答复。{@code initialize} 开一个会话,答复里带 {@code Mcp-Session-Id},
 *       之后每个请求都要带;回复都是 {@code application/json};</li>
 *   <li>{@code GET}:客户端开的服务端→客户端的 SSE 流,征询(elicitation)等服务端主动发的东西走它;</li>
 *   <li>{@code DELETE}:客户端结束会话。</li>
 * </ul>
 *
 * <h2>认人</h2>
 * 每个请求都要 {@code Authorization: Bearer <令牌>}({@link McpTokens}),令牌不进查询串。令牌带着主人、作用域和身份
 * ({@link McpToken}):这个会话只能指挥那位主人名下被作用域管着的同伴,不会因为令牌变得能做权限层不让做的事——
 * 外部大脑的程序走的是她自己的工具口,过的是同一个权限层。
 *
 * <h2>工具</h2>
 * 身份是大脑的令牌看到:{@code list_companions}/{@code create_companion}/{@code delete_companion}/{@code get_events}/
 * {@code say},加上 {@link ToolRegistry} 里的每个工具(各带一个 {@code companion} 参数),调用路由到
 * {@link ExternalHands#invoke}:程序在她自己的派发器里跑,做完回执。没有"接管"握手——会话连着,驾驶席就是它的
 * ({@link McpMode#driving})。
 *
 * <h2>限流与审计</h2>
 * 工具调用与接入受 {@link McpLimits} 限;每次接入与调用记一条审计({@link McpAudit}),只记编号、令牌编号、工具名、
 * 成败与字节数。没认出令牌的请求按来源地址限流并合并审计({@link McpStrangers})。
 */
public final class McpServer {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    /** 认得的协议版本;客户端要的在其中就照它答,否则答最新的。 */
    private static final Set<String> PROTOCOL_VERSIONS = Set.of("2025-06-18", "2025-03-26", "2024-11-05");
    private static final String SERVER_VERSION = "0.2.0";
    /** Roster / create / delete are fast; only tool actions use the config timeout. */
    private static final int CONTROL_TIMEOUT_SECONDS = 10;
    /** 请求体上限:一段程序或一句话远用不到,挡住灌内存的。 */
    private static final int MAX_BODY_BYTES = 1 << 20;
    /** 服务端→客户端的流多久写一次保活;写不出去就是对方走了。 */
    private static final long KEEPALIVE_SECONDS = 15;
    /** get_events 长等待：外脑每调一次工具就是一次完整的模型请求，所以没事就一直等，有事（会叫醒她的条目，
     *  判据见 {@code ExternalWait}）立刻返回。最长 50 秒，给桥接层（mcp-remote 等）和客户端的请求超时留余量
     *  （Codex 的工具超时默认 60 秒）；到点返回空，外脑再调一次接着等。 */
    private static final int MAX_WAIT_SECONDS = 50;

    /**
     * 外接大脑读的那条身体规则:内脑读的同一句({@link NumenPrompts#ONE_BODY}),接上这一侧怎么把几件活排开——外接大脑的
     * 调用没有"同一轮",每一条当场执行,要一件做完再派下一件就自己等身体空闲。说明与接入提示词({@link McpAccessPrompt})
     * 都用这一句。
     */
    static final String ONE_BODY = NumenPrompts.ONE_BODY + " A program waits for each body task it starts, so jobs "
            + "written one after another in one program run one after another.";

    /**
     * Sent to the connecting agent in the {@code initialize} handshake (MCP's
     * {@code instructions} field) — what Numen is and how to drive it, so any
     * client gets the essentials without a separately-installed skill.
     */
    private static final String INSTRUCTIONS = """
            Numen companions are AI-controlled, player-like characters inside a live Minecraft game. \
            Through this server you take control of a companion's body and play the game as it — perceive, \
            move, mine, build, craft, fight. You ARE its brain while this session is connected: the built-in AI \
            stands aside, and even the owner's in-game chat reaches you (via get_events) instead \
            of it. Drive the body directly — there is no 'take control' handshake.

            Loop: (1) list_companions to see who you can drive — create_companion by name to summon a new one, \
            delete_companion to dismiss one for good; (2) everything else is one tool, %s: a program \
            whose functions are the companion's API — perceive with numen.status.self(), numen.scan.blocks(...), \
            numen.scan.entities(...); act with numen.move.to(...), numen.work.dig(...), numen.build.place(...), numen.fight.attack(...), \
            numen.inv.craft(...), …; numen.api.help("numen.work") lists a group's functions and numen.api.help("numen.work.dig") gives one \
            function's full help. A program returns one receipt when it ends: how it ended, then stderr \
            (what the body did and what failed, one entry per call that had something to say), what it returned \
            and stdout (what it printed). It waits for each body task it starts to finish, so a long \
            job returns when it is done, with the task's account of what it changed in its stderr entry. Every call takes a 'companion' argument (name or id), so each call targets \
            one companion; just drive it, there is no take-control step. The %s tool loads one of the \
            companion's skills — the workflow guide for one kind of task — and returns its text; a name it \
            does not know answers with the list of the skills it can use. The %s tool writes down your plan for \
            work of several phases (the whole plan each call, replacing the last one); the owner sees it as a \
            checklist. The %s tool keeps the companion's own notes (remember, recall, forget); their index \
            comes with every turn of its own brain.

            Rules: survival mode — the API does only what a real player can (mine to get stone; there is no \
            give or setblock). You are blind between calls, so perceive before and after acting. %s You can \
            drive several companions in parallel. Modded blocks, items, and GUIs (Create, AE2, Mekanism) \
            work natively. Your programs go through the same permission layer as the companion's own: \
            when it needs the owner's approval, the owner is asked (not you) and the answer arrives as the \
            program's outcome.

            You also carry the companion's conversation. To wait for the owner to speak or for something to happen, \
            call get_events(companion): it returns as soon as there is news and otherwise waits up to %d seconds, \
            then answers that nothing came, so call it again to keep waiting. While you run programs, events that \
            arrive come back with the program's receipt, after the receipt text. The owner speaking to the companion \
            (in-game chat or voice) arrives as a <query> and stops a running program between two calls; the task it \
            was waiting for keeps running, and its end arrives as a task_finished <event> among the world \
            happenings. Reply with say(companion, text): the words appear in-game as the companion's chat line, \
            speech bubble, and voice. Keep your own conversation history — the game stores none for you; events \
            queue up until you take them.""".formatted(
            com.dwinovo.numen.script.ScriptEngine.IN_USE.toolName(), com.dwinovo.numen.api.agent.tool.SkillTool.NAME,
            com.dwinovo.numen.api.agent.tool.TodoTool.NAME, com.dwinovo.numen.api.agent.tool.MemoryTool.NAME, ONE_BODY,
            MAX_WAIT_SECONDS);

    private final McpConfig config;
    private final McpTokens tokens;
    private final McpLimits limits;
    private final McpSessions sessions;
    private final McpAudit audit;
    private final McpStrangers strangers;
    private final McpChannels channels;
    private final Function<UUID, ExternalHands> handsOf;
    private final Gson gson = new Gson();
    private HttpServer http;
    private ExecutorService workers;

    McpServer(McpConfig config, McpTokens tokens, McpLimits limits, McpSessions sessions, McpAudit audit,
              McpStrangers strangers,
              McpChannels channels, Function<UUID, ExternalHands> handsOf) {
        this.config = config;
        this.tokens = tokens;
        this.limits = limits;
        this.sessions = sessions;
        this.audit = audit;
        this.strangers = strangers;
        this.channels = channels;
        this.handsOf = handsOf;
    }

    void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        http.createContext("/mcp", this::handle);
        // get_events 长轮询与动作类工具都会让一条线程停靠很久,GET 流更是一直占着:每个请求一条虚拟线程,
        // 不设一个会被长轮询占满的池;停服时整个执行器一并收掉。
        workers = Executors.newVirtualThreadPerTaskExecutor();
        http.setExecutor(workers);
        startOnDaemonThread(http);
    }

    /**
     * JDK 的 HTTP 服务器在 {@code start()} 里开调度线程 {@code HTTP-Dispatcher},它的守护属性继承自调用线程——
     * 从服务器主线程(非守护)调,调度线程就是非守护的。正常关服会 {@link #stop},但崩溃、被强杀时 stop 到不了,
     * 一个非守护线程会拖住 JVM 退出,所以在守护线程里起。
     */
    private static void startOnDaemonThread(HttpServer http) throws IOException {
        Thread starter = new Thread(http::start, "numen-mcp-start");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while starting the endpoint", e);
        }
    }

    /** 实际绑到的端口(配置里写 0 让系统挑时用,测试用)。 */
    int port() {
        return http.getAddress().getPort();
    }

    void stop() {
        strangers.flushAll();
        for (McpSessions.Session session : sessions.all()) {
            sessions.end(session);
        }
        if (http != null) {
            http.stop(0);
            http = null;
        }
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
    }

    // ---- HTTP layer ----

    private void handle(HttpExchange ex) throws IOException {
        boolean keepOpen = false;
        try {
            if (!originAllowed(ex)) {
                respond(ex, 403, "");
                return;
            }
            String address = ex.getRemoteAddress().getAddress().getHostAddress();
            // 每个请求都照常验令牌;验过的不受地址牵连(反向代理后面所有人共用一个地址),验不过的才记到地址上,
            // 这个地址的失败满了,它的未认证请求回 429
            boolean throttled = strangers.blocked(address);
            McpToken token = authenticate(ex, address);
            if (token == null) {
                if (throttled) {
                    ex.getResponseHeaders().add("Retry-After", "60");
                    respond(ex, 429, "");
                } else {
                    ex.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"numen\"");
                    respond(ex, 401, "");
                }
                return;
            }
            switch (ex.getRequestMethod().toUpperCase(java.util.Locale.ROOT)) {
                case "POST" -> post(ex, token);
                case "GET" -> keepOpen = openStream(ex, token);
                case "DELETE" -> delete(ex, token);
                default -> respond(ex, 405, "");
            }
        } catch (RuntimeException fatal) {
            Constants.LOG.warn("[numen-mcp] request failed: {}", fatal.toString());
            try { respond(ex, 500, ""); } catch (IOException ignored) {}
        } finally {
            if (!keepOpen) {
                ex.close();
            }
        }
    }

    /**
     * 浏览器发的请求带 {@code Origin}:规范要求服务端核对,防 DNS 重绑定。合法的客户端是命令行或桌面程序,不带它;
     * 带了就必须是访问这个地址的同一个源。
     */
    private static boolean originAllowed(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) {
            return true;
        }
        String host = ex.getRequestHeaders().getFirst("Host");
        try {
            URI uri = URI.create(origin);
            String originHost = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
            return host != null && host.equalsIgnoreCase(originHost);
        } catch (IllegalArgumentException bad) {
            return false;
        }
    }

    private McpToken authenticate(HttpExchange ex, String address) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            strangers.failed(address, "no bearer token");
            return null;
        }
        return tokens.authenticate(auth.substring(7).trim()).orElseGet(() -> {
            strangers.failed(address, "unknown or expired token");
            return null;
        });
    }

    private void post(HttpExchange ex, McpToken token) throws IOException {
        byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (raw.length > MAX_BODY_BYTES) {
            respond(ex, 413, "");
            return;
        }
        JsonObject req;
        try {
            JsonElement parsed = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {   // 2025-06-18 起不再有 JSON-RPC 批
                respondJson(ex, 400, errorResponse(null, -32600, "a request must be one JSON object"));
                return;
            }
            req = parsed.getAsJsonObject();
        } catch (RuntimeException parseErr) {
            respondJson(ex, 400, errorResponse(null, -32700, "parse error: " + parseErr.getMessage()));
            return;
        }
        String method = req.has("method") ? req.get("method").getAsString() : "";
        if ("initialize".equals(method)) {
            initialize(ex, token, req);
            return;
        }
        McpSessions.Session session = sessionOf(ex, token);
        if (session == null) {
            return;
        }
        session.touch();
        JsonElement id = req.get("id");
        if (method.isEmpty()) {   // 对我们请求的答复(征询)
            if (id != null && id.isJsonPrimitive()) {
                session.answered(id.getAsString(), req);
            }
            respond(ex, 202, "");
            return;
        }
        if (id == null || id.isJsonNull()) {   // 通知:不回话
            respond(ex, 202, "");
            return;
        }
        JsonObject response = dispatch(session, req, id, method, raw.length);
        respondJson(ex, 200, response);
    }

    /** 请求带的会话:没带 400,不认识或属于别的令牌 404(规范:404 让客户端重新 initialize)。已回过话则返回 null。 */
    private McpSessions.Session sessionOf(HttpExchange ex, McpToken token) throws IOException {
        String header = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (header == null) {
            respond(ex, 400, "");
            return null;
        }
        McpSessions.Session session = sessions.find(header);
        if (session == null || !session.token.hash().equals(token.hash())) {
            respond(ex, 404, "");
            return null;
        }
        String version = ex.getRequestHeaders().getFirst("MCP-Protocol-Version");
        if (version != null && !PROTOCOL_VERSIONS.contains(version)) {
            respond(ex, 400, "");
            return null;
        }
        return session;
    }

    private void initialize(HttpExchange ex, McpToken token, JsonObject req) throws IOException {
        JsonElement id = req.get("id");
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params") : new JsonObject();
        McpLimits.Permit permit = limits.acquire(token.id());
        try {
            if (permit.refusal() != null) {
                audit.refused("rate limited", token.id());
                ex.getResponseHeaders().add("Retry-After", "5");
                respond(ex, 429, permit.refusal());
                return;
            }
            String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : "";
            boolean elicits = params.has("capabilities") && params.get("capabilities").isJsonObject()
                    && params.getAsJsonObject("capabilities").has("elicitation");
            McpSessions.Session session = sessions.open(token, clientLabel(params), elicits);
            if (session == null) {
                audit.refused("too many sessions", token.id());
                respond(ex, 429, "this token already has " + McpSessions.MAX_PER_TOKEN + " sessions open");
                return;
            }
            audit.connected(session.id, token, session.client);
            JsonObject result = new JsonObject();
            result.addProperty("protocolVersion", PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSION);
            JsonObject caps = new JsonObject();
            caps.add("tools", new JsonObject());
            result.add("capabilities", caps);
            JsonObject info = new JsonObject();
            info.addProperty("name", "numen-mcp");
            info.addProperty("version", SERVER_VERSION);
            result.add("serverInfo", info);
            result.addProperty("instructions", INSTRUCTIONS);
            ex.getResponseHeaders().add("Mcp-Session-Id", session.id);
            respondJson(ex, 200, okResponse(id, result));
        } finally {
            permit.close();
        }
    }

    /** {@code GET}:开服务端→客户端的流,一直开到对方走了或会话结束。返回 true 表示交换留着不关。 */
    private boolean openStream(HttpExchange ex, McpToken token) throws IOException {
        String accept = ex.getRequestHeaders().getFirst("Accept");
        if (accept == null || !accept.contains("text/event-stream")) {
            respond(ex, 406, "");
            return false;
        }
        McpSessions.Session session = sessionOf(ex, token);
        if (session == null) {
            return false;
        }
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        SseSink sink = new SseSink(ex.getResponseBody());
        session.attach(sink);
        try {
            sink.comment("stream open");
            while (!sink.closed.await(KEEPALIVE_SECONDS, TimeUnit.SECONDS)) {
                if (!sink.comment("keepalive")) {
                    break;
                }
            }
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        } finally {
            session.detach(sink);
            ex.close();
        }
        return true;
    }

    /** 一条 SSE 流的出口;写不出去就是对方走了,流随之结束。 */
    private static final class SseSink implements McpSessions.Sink {
        final CountDownLatch closed = new CountDownLatch(1);
        private final OutputStream out;

        SseSink(OutputStream out) {
            this.out = out;
        }

        @Override
        public boolean send(String event) {
            return write("data: " + event + "\n\n");
        }

        /** SSE 注释行:不是消息,客户端忽略,只用来保活和发现对方已走。 */
        boolean comment(String text) {
            return write(": " + text + "\n\n");
        }

        @Override
        public void close() {
            closed.countDown();
        }

        private synchronized boolean write(String text) {
            try {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException gone) {
                closed.countDown();
                return false;
            }
        }
    }

    private void delete(HttpExchange ex, McpToken token) throws IOException {
        McpSessions.Session session = sessionOf(ex, token);
        if (session != null) {
            sessions.end(session);
            respond(ex, 200, "");
        }
    }

    private void respondJson(HttpExchange ex, int status, JsonElement json) throws IOException {
        byte[] bytes = gson.toJson(json).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ---- JSON-RPC dispatch ----

    private JsonObject dispatch(McpSessions.Session session, JsonObject req, JsonElement id, String method,
                                int bytesIn) {
        try {
            return switch (method) {
                case "ping" -> okResponse(id, new JsonObject());
                case "tools/list" -> okResponse(id, toolsListResult(session.token));
                case "tools/call" -> okResponse(id, toolsCallResult(session, req, bytesIn));
                default -> errorResponse(id, -32601, "method not found: " + method);
            };
        } catch (RuntimeException ex) {
            return errorResponse(id, -32603, "internal error: " + ex.getMessage());
        }
    }

    /** {@code params.clientInfo} → "Claude Desktop 1.2.3";没报名字就叫 unknown client。 */
    private static String clientLabel(JsonObject params) {
        if (!params.has("clientInfo") || !params.get("clientInfo").isJsonObject()) return "unknown client";
        JsonObject info = params.getAsJsonObject("clientInfo");
        String name = info.has("name") ? info.get("name").getAsString() : "unknown client";
        String version = info.has("version") ? info.get("version").getAsString() : "";
        return version.isBlank() ? name : name + " " + version;
    }

    // ---- tools/list ----

    private JsonObject toolsListResult(McpToken token) {
        JsonArray tools = new JsonArray();
        if (token.role() == McpToken.Role.CHANNEL) {
            channelTools(tools);
            JsonObject result = new JsonObject();
            result.add("tools", tools);
            return result;
        }

        tools.add(toolDef("list_companions",
                "List the live Minecraft companions this token may drive (name + id). Call this first to see who you can drive.",
                objectSchema(null, false)));
        tools.add(toolDef("create_companion",
                "Summon a new companion into the world by name (3–16 letters, digits, or underscore). It arrives "
                        + "in a moment; call list_companions to confirm, then drive it directly — no 'take control' step. "
                        + "Needs a token that covers all of the owner's companions, and the owner in the game.",
                requiredStringSchema("name",
                        "The new companion's name (3–16 letters, digits, or underscore).")));
        tools.add(toolDef("delete_companion",
                "Permanently dismiss a companion — it drops its inventory, armor and accessories and is gone for good. "
                        + "Takes its name or id. Needs the owner in the game.",
                requiredStringSchema("companion",
                        "Which companion to dismiss — its name or id (see list_companions).")));
        tools.add(toolDef("get_events",
                "Wait on the companion's inbox: returns as soon as there is news (the owner speaking to it shows "
                        + "up as a <query>; world happenings as <event>s), or after up to " + MAX_WAIT_SECONDS
                        + " seconds with whatever accumulated. Events are consumed on read and nothing is "
                        + "lost between calls. Call it when you want to wait for the owner or for something to happen; "
                        + "events that arrive while you run programs come with the program's receipt.",
                getEventsSchema()));
        tools.add(toolDef("say",
                "Speak as the companion: the text appears in-game as its chat line, speech bubble, and voice "
                        + "(if one is bound). Use it to answer the owner's <query> events and to narrate what "
                        + "you are doing. Consecutive calls queue up and play in order.",
                saySchema()));

        // 全量给外脑,不做渐进披露:那是我们自己请求里的省法,外脑的上下文预算归它自己管,
        // 而且 MCP 客户端(Claude Code 之类)本来就会把借来的工具再延迟一次。
        for (NumenTool tool : ToolRegistry.all()) {
            if (config.isHidden(tool.name())) continue;
            tools.add(toolDef(tool.name(), tool.description(), withCompanion(tool.parameterSchema())));
        }

        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    /** 渠道看到的四个工具:点名、把主人的话送进收件箱、收她的回话与征询、答征询。 */
    private void channelTools(JsonArray tools) {
        tools.add(toolDef("list_companions",
                "List the live companions this token may speak to (name + id).", objectSchema(null, false)));
        JsonObject send = objectSchema("companion", true);
        JsonObject text = new JsonObject();
        text.addProperty("type", "string");
        text.addProperty("description", "What the owner said, as the owner said it.");
        send.getAsJsonObject("properties").add("text", text);
        send.getAsJsonArray("required").add("text");
        tools.add(toolDef("send",
                "Hand the owner's words to a companion, from this channel. Her own brain thinks about it (with the "
                        + "owner's model key) and her answer and any question for the owner come back through "
                        + "get_replies. When the round is done and the owner is not in the game, she goes back to "
                        + "standing by.", send));
        JsonObject wait = new JsonObject();
        wait.addProperty("type", "integer");
        wait.addProperty("description", "The longest to wait, in seconds (0–" + MAX_WAIT_SECONDS
                + ", default " + MAX_WAIT_SECONDS + "). 0 = return at once.");
        JsonObject replies = objectSchema(null, false);
        replies.getAsJsonObject("properties").add("wait_seconds", wait);
        tools.add(toolDef("get_replies",
                "Wait for what her brain has for this channel: {\"items\":[...]} where an item is "
                        + "{\"type\":\"said\",\"companion\",\"text\"}, {\"type\":\"consent\",\"id\",\"companion\","
                        + "\"name\",\"doing\",\"remember\",\"choices\"} (a question for the owner: show it, then call "
                        + "answer_consent) or {\"type\":\"consent_withdrawn\",\"id\"} (no need to answer any more). "
                        + "Returns as soon as something is there, or after the wait with an empty list.", replies));
        JsonObject answer = objectSchema("companion", true);
        JsonObject id = new JsonObject();
        id.addProperty("type", "integer");
        id.addProperty("description", "The id of the consent item.");
        JsonObject decision = new JsonObject();
        decision.addProperty("type", "string");
        JsonArray choices = new JsonArray();
        choices.add("allow");
        choices.add("remember");
        choices.add("deny");
        decision.add("enum", choices);
        JsonObject note = new JsonObject();
        note.addProperty("type", "string");
        note.addProperty("description", "Only with deny: what the owner wants her to do instead.");
        JsonObject props = answer.getAsJsonObject("properties");
        props.add("id", id);
        props.add("decision", decision);
        props.add("note", note);
        answer.getAsJsonArray("required").add("id");
        answer.getAsJsonArray("required").add("decision");
        tools.add(toolDef("answer_consent",
                "Pass on the owner's answer to a consent item from get_replies, exactly as he gave it. Only the "
                        + "owner's own choice counts here; do not answer for him.", answer));
    }

    private JsonObject toolDef(String name, String description, JsonObject inputSchema) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", description);
        t.add("inputSchema", inputSchema);
        return t;
    }

    /** An object schema with just an optional required {@code companion} string. */
    private JsonObject objectSchema(String companionKey, boolean required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        if (companionKey != null) props.add(companionKey, companionProp());
        schema.add("properties", props);
        if (companionKey != null && required) {
            JsonArray req = new JsonArray();
            req.add(companionKey);
            schema.add("required", req);
        }
        return schema;
    }

    /** An object schema with a single required string property (key + its description). */
    private JsonObject requiredStringSchema(String key, String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "string");
        prop.addProperty("description", description);
        props.add(key, prop);
        schema.add("properties", props);
        JsonArray req = new JsonArray();
        req.add(key);
        schema.add("required", req);
        return schema;
    }

    /** A Numen API tool's own schema, with a required {@code companion} argument injected. */
    private JsonObject withCompanion(java.util.Map<String, Object> parameterSchema) {
        JsonObject schema = parameterSchema == null
                ? new JsonObject()
                : gson.toJsonTree(parameterSchema).getAsJsonObject();
        schema.addProperty("type", "object");
        JsonObject props = schema.has("properties") && schema.get("properties").isJsonObject()
                ? schema.getAsJsonObject("properties") : new JsonObject();
        props.add("companion", companionProp());
        schema.add("properties", props);
        JsonArray required = schema.has("required") && schema.get("required").isJsonArray()
                ? schema.getAsJsonArray("required") : new JsonArray();
        boolean has = false;
        for (JsonElement e : required) if ("companion".equals(e.getAsString())) has = true;
        if (!has) required.add("companion");
        schema.add("required", required);
        return schema;
    }

    private JsonObject companionProp() {
        JsonObject p = new JsonObject();
        p.addProperty("type", "string");
        p.addProperty("description", "Which companion to act with — its name or id (see list_companions).");
        return p;
    }

    private JsonObject getEventsSchema() {
        JsonObject schema = objectSchema("companion", true);
        JsonObject wait = new JsonObject();
        wait.addProperty("type", "integer");
        wait.addProperty("description", "The longest to wait for news, in seconds (0–" + MAX_WAIT_SECONDS
                + ", default " + MAX_WAIT_SECONDS + "). 0 = return at once with whatever is queued.");
        schema.getAsJsonObject("properties").add("wait_seconds", wait);
        return schema;
    }

    private JsonObject saySchema() {
        JsonObject schema = objectSchema("companion", true);
        JsonObject text = new JsonObject();
        text.addProperty("type", "string");
        text.addProperty("description", "What the companion says out loud.");
        schema.getAsJsonObject("properties").add("text", text);
        schema.getAsJsonArray("required").add("text");
        return schema;
    }

    // ---- tools/call ----

    /** 派发一次工具调用,先过限流,记一条审计。 */
    private JsonObject toolsCallResult(McpSessions.Session session, JsonObject req, int bytesIn) {
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params") : new JsonObject();
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        McpToken token = session.token;
        McpLimits.Permit permit = limits.acquire(token.id());
        if (permit.refusal() != null) {
            audit.called(session.id, token, name, null, false, bytesIn, 0);
            return content("rate limited: " + permit.refusal() + ". Wait a few seconds and call again.", true);
        }
        session.started();
        try {
            UUID[] target = new UUID[1];
            JsonObject result = dispatchToolCall(session, name, args, target);
            audit.called(session.id, token, name, target[0], !result.get("isError").getAsBoolean(), bytesIn,
                    result.toString().length());
            return result;
        } finally {
            session.finished();
            permit.close();
        }
    }

    private JsonObject dispatchToolCall(McpSessions.Session session, String name, JsonObject args, UUID[] target) {
        ExternalHands hands = handsOf.apply(session.token.owner());
        Call call = new Call(session.token, hands, target);
        try {
            if (session.token.role() == McpToken.Role.CHANNEL) {
                return switch (name) {
                    case "list_companions" -> content(call.listCompanions(), false);
                    case "send" -> call.send(args, channels.channel(session.token.id()));
                    case "get_replies" -> call.getReplies(args, session.token.id());
                    case "answer_consent" -> call.answerConsent(args);
                    default -> content("a channel token can only use list_companions, send, get_replies and "
                            + "answer_consent", true);
                };
            }
            return switch (name) {
                case "list_companions" -> content(call.listCompanions(), false);
                case "create_companion" -> call.create(args);
                case "delete_companion" -> call.delete(args);
                case "get_events" -> call.getEvents(args);
                case "say" -> call.say(args);
                default -> call.invoke(name, args);
            };
        } catch (TimeoutException te) {
            return content("timed out waiting for the action to finish", true);
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            return content("the endpoint is shutting down", true);
        } catch (Exception ex) {
            return content("call failed: " + ex.getMessage(), true);
        }
    }

    /** 一次调用:这张令牌、这位主人的手;解析到的同伴记进 {@code target} 给审计。 */
    private final class Call {
        private final McpToken token;
        private final ExternalHands hands;
        private final UUID[] target;

        Call(McpToken token, ExternalHands hands, UUID[] target) {
            this.token = token;
            this.hands = hands;
            this.target = target;
        }

        /** 作用域管得着的、此刻在世界里的同伴。 */
        private List<ExternalHands.Companion> visible() throws Exception {
            return hands.companions().get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS).stream()
                    .filter(c -> token.scope().covers(c.uuid())).toList();
        }

        String listCompanions() throws Exception {
            List<ExternalHands.Companion> list = visible();
            if (list.isEmpty()) {
                return "No companions this token may drive are live in the world right now.";
            }
            StringBuilder sb = new StringBuilder("Live companions:\n");
            for (ExternalHands.Companion c : list) {
                sb.append("- ").append(c.name()).append("  (id: ").append(c.uuid()).append(")\n");
            }
            return sb.toString().stripTrailing();
        }

        JsonObject create(JsonObject args) throws Exception {
            if (!token.scope().all()) {
                return content("this token is limited to named companions; it cannot summon new ones", true);
            }
            String name = args.has("name") && !args.get("name").isJsonNull()
                    ? args.get("name").getAsString().trim() : "";
            if (name.isEmpty()) {
                return content("create_companion needs a 'name' (3–16 letters, digits, or underscore)", true);
            }
            boolean ok = hands.create(name).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!ok) {
                return content("could not summon — the owner has to be in the game", true);
            }
            return content("summoning '" + name + "' — it arrives in a moment; call list_companions to confirm, "
                    + "then drive it directly (no acquire needed)", false);
        }

        JsonObject delete(JsonObject args) throws Exception {
            UUID companion = resolve(args);
            if (companion == null) {
                return content("no such companion — call list_companions to see valid names/ids", true);
            }
            boolean ok = hands.delete(companion).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return content(ok ? "dismissed " + companion + " — it drops its inventory, armor and accessories and is gone for good"
                    : "could not dismiss " + companion + " — the owner has to be in the game", !ok);
        }

        /** 长等待取件：停靠在收件箱上，会叫醒她的条目一到立即整批返回；到点清扫，
         *  有什么给什么（捎带的也交），没有就如实说没有。叫醒只是提前，正确性由到点清扫兜底。 */
        JsonObject getEvents(JsonObject args) throws Exception {
            UUID companion = resolve(args);
            if (companion == null) {
                return content("get_events needs a 'companion' argument (a name or id from list_companions)", true);
            }
            int wait = MAX_WAIT_SECONDS;
            if (args.has("wait_seconds") && !args.get("wait_seconds").isJsonNull()) {
                wait = Math.max(0, Math.min(MAX_WAIT_SECONDS, args.get("wait_seconds").getAsInt()));
            }
            if (wait > 0) {
                ExternalWait.Parked parked = hands.awaitEvents(companion);
                String batch = null;
                try {
                    batch = parked.batch().get(wait, TimeUnit.SECONDS);
                } catch (TimeoutException deadline) {
                    // 到点，转清扫——叫醒只是提前，正确性由清扫兜底
                } finally {
                    // 撤销与叫醒谁先谁得：到点那一刻叫醒恰好取走的批次由撤销交回，取走的一定交到外脑手里
                    String late = parked.cancel().get();
                    if (batch == null) batch = late;
                }
                if (batch != null) return content(batch, false);
            }
            String rest = hands.takeEvents(companion).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return content(rest != null ? rest
                    : "(no new events — call get_events again to keep waiting; the owner's words and world happenings land here)", false);
        }

        /** 渠道:把主人的话送给她。 */
        JsonObject send(JsonObject args, Visits.Channel channel) throws Exception {
            UUID companion = resolve(args);
            String text = args.has("text") && !args.get("text").isJsonNull() ? args.get("text").getAsString().trim() : "";
            if (companion == null || text.isEmpty()) {
                return content("send needs a 'companion' (name or id from list_companions) and a non-empty 'text'", true);
            }
            boolean ok = hands.hear(companion, text, channel).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return content(ok ? "sent — her answer comes back through get_replies" : "could not send to her", !ok);
        }

        /** 渠道:收她的回话与征询。 */
        JsonObject getReplies(JsonObject args, String tokenId) throws Exception {
            int wait = MAX_WAIT_SECONDS;
            if (args.has("wait_seconds") && !args.get("wait_seconds").isJsonNull()) {
                wait = Math.max(0, Math.min(MAX_WAIT_SECONDS, args.get("wait_seconds").getAsInt()));
            }
            JsonArray items = new JsonArray();
            channels.take(tokenId, wait * 1000L).forEach(items::add);
            JsonObject out = new JsonObject();
            out.add("items", items);
            return content(out.toString(), false);
        }

        /** 渠道:把主人的选择交给征询台,和游戏里答复框同一个入口。 */
        JsonObject answerConsent(JsonObject args) throws Exception {
            UUID companion = resolve(args);
            if (companion == null || !args.has("id") || !args.has("decision")) {
                return content("answer_consent needs 'companion', 'id' and 'decision' (allow, remember or deny)", true);
            }
            ConsentAnswer.Decision decision = switch (args.get("decision").getAsString()) {
                case "allow" -> ConsentAnswer.Decision.ALLOW_ONCE;
                case "remember" -> ConsentAnswer.Decision.ALLOW_REMEMBER;
                case "deny" -> ConsentAnswer.Decision.DENY;
                default -> null;
            };
            String note = args.has("note") && !args.get("note").isJsonNull() ? args.get("note").getAsString() : "";
            if (decision == null || (decision != ConsentAnswer.Decision.DENY && !note.isBlank())) {
                return content("'decision' is allow, remember or deny; a note only goes with deny", true);
            }
            ConsentDesk.Reply reply = hands.answer(companion, args.get("id").getAsLong(), decision, note)
                    .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return content(reply == ConsentDesk.Reply.ANSWERED ? "answered"
                    : "nothing to answer: that request is not pending any more (answered, expired or replaced)",
                    reply != ConsentDesk.Reply.ANSWERED);
        }

        JsonObject say(JsonObject args) throws Exception {
            UUID companion = resolve(args);
            if (companion == null) {
                return content("say needs a 'companion' argument (a name or id from list_companions)", true);
            }
            String text = args.has("text") && !args.get("text").isJsonNull()
                    ? args.get("text").getAsString().trim() : "";
            if (text.isEmpty()) {
                return content("say needs a non-empty 'text'", true);
            }
            boolean ok = hands.say(companion, text).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return content(ok ? "said" : "could not say — is the companion live?", !ok);
        }

        JsonObject invoke(String toolName, JsonObject args) throws Exception {
            UUID companion = resolve(args);
            if (companion == null) {
                return content("this tool needs a 'companion' argument (a name or id from list_companions)", true);
            }
            JsonObject toolArgs = args.deepCopy();
            toolArgs.remove("companion");
            String result = hands.invoke(companion, toolName, toolArgs.toString())
                    .get(config.callTimeoutSeconds(), TimeUnit.SECONDS);
            // the external agent reads what the built-in brain reads: the result's message, never its structured data
            String events = hands.takeEvents(companion).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return receipt(ToolOutcome.modelText(result), ToolOutcome.failed(result), events);
        }

        /** 把 {@code companion} 参数(名字或 UUID)解析成这张令牌管得着的、活着的同伴;没有是 null。 */
        private UUID resolve(JsonObject args) throws Exception {
            if (!args.has("companion") || args.get("companion").isJsonNull()) return null;
            String raw = args.get("companion").getAsString().trim();
            if (raw.isEmpty()) return null;
            List<ExternalHands.Companion> list = visible();
            for (ExternalHands.Companion c : list) {
                if (c.uuid().toString().equalsIgnoreCase(raw)) return found(c);
            }
            for (ExternalHands.Companion c : list) {
                if (c.name().equals(raw)) return found(c);
            }
            for (ExternalHands.Companion c : list) {
                if (c.name().equalsIgnoreCase(raw)) return found(c);
            }
            return null;
        }

        private UUID found(ExternalHands.Companion c) {
            target[0] = c.uuid();
            return c.uuid();
        }
    }

    /**
     * 一次工具调用的回执：结果文本在前；这段时间到达的事件（{@link ExternalHands#takeEvents} 取走的那批，
     * 取走即不再由 get_events 交）作为另一段文本跟在后面，外脑干活时不必另外轮询。
     */
    static JsonObject receipt(String text, boolean isError, String events) {
        JsonObject out = content(text, isError);
        if (events != null) {
            out.getAsJsonArray("content").add(textItem(events));
        }
        return out;
    }

    // ---- 征询(elicitation) ----
    // 外部大脑驾驶时,要问主人的事问的是坐在电脑前的人,不是驾驶席上的模型:经这个会话里客户端开的流发
    // elicitation/create,客户端弹给人,人的选择作为 JSON-RPC 答复 POST 回来。客户端没声明会答 elicitation、
    // 或者没开流的会话问不了;没有会话问得了,征询就挂到主人进游戏答,到期按拒绝。服务端没有任何让模型自己答的口。

    /** 在途的 elicitation,按征询号;主人在别处答了要撤。 */
    private final java.util.Map<Long, Asked> asked = new java.util.concurrent.ConcurrentHashMap<>();

    private record Asked(McpSessions.Session session, java.util.concurrent.CompletableFuture<JsonObject> answer) {}

    /**
     * 经这位主人的外部大脑客户端问人。
     *
     * @return 有会话问了;没有一个会话问得了是 false
     */
    boolean elicit(UUID owner, ConsentVenues.Offer offer) {
        for (McpSessions.Session session : sessions.brains(owner)) {
            if (!session.elicits || !session.hasStream() || !session.token.scope().covers(offer.companion())) {
                continue;
            }
            var answer = session.request("elicitation/create", elicitationParams(offer));
            if (answer.isCompletedExceptionally()) {
                continue;   // 流刚好断了
            }
            asked.put(offer.id(), new Asked(session, answer));
            answer.whenComplete((response, gone) -> {
                asked.remove(offer.id());
                if (gone == null) {
                    settle(owner, offer, response);
                }
            });
            return true;
        }
        return false;
    }

    /** 这一条不用答了:告诉客户端撤掉那个弹窗(规范的取消通知,客户端可以不理)。 */
    void withdraw(long id) {
        Asked one = asked.remove(id);
        if (one != null) {
            String requestId = one.session.idOf(one.answer);
            if (requestId != null) {
                JsonObject params = new JsonObject();
                params.addProperty("requestId", requestId);
                params.addProperty("reason", "answered elsewhere or no longer needed");
                one.session.notifyClient("notifications/cancelled", params);
            }
            one.session.abandon(one.answer);
        }
    }

    private static JsonObject elicitationParams(ConsentVenues.Offer offer) {
        StringBuilder message = new StringBuilder(offer.companionName()).append(" asks for your permission (#")
                .append(offer.id()).append("):\n").append(offer.doing());
        if (!offer.remembers().isEmpty()) {
            message.append("\n\"Allow and remember\" also adds: ").append(String.join("; ", offer.remembers()));
        }
        JsonObject decision = new JsonObject();
        decision.addProperty("type", "string");
        decision.addProperty("title", "Your answer");
        JsonArray choices = new JsonArray();
        JsonArray names = new JsonArray();
        for (String[] choice : CHOICES) {
            choices.add(choice[0]);
            names.add(choice[1]);
        }
        decision.add("enum", choices);
        decision.add("enumNames", names);
        JsonObject note = new JsonObject();
        note.addProperty("type", "string");
        note.addProperty("title", "Tell her what to do instead (only when denying)");
        JsonObject props = new JsonObject();
        props.add("decision", decision);
        props.add("note", note);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("decision");
        schema.add("required", required);
        JsonObject params = new JsonObject();
        params.addProperty("message", message.toString());
        params.add("requestedSchema", schema);
        return params;
    }

    private static final String[][] CHOICES = {
            {"allow", "Allow once"}, {"allow_and_remember", "Allow and remember"}, {"deny", "Deny"}};

    /**
     * 人的选择交给征询台:accept 按选的那项,decline 是拒绝,cancel(关掉弹窗没选)不算答复——征询仍挂着,
     * 主人可以进游戏答,到期按拒绝。附言只随拒绝。
     */
    private void settle(UUID owner, ConsentVenues.Offer offer, JsonObject response) {
        JsonObject result = response.has("result") && response.get("result").isJsonObject()
                ? response.getAsJsonObject("result") : null;
        String action = result != null && result.has("action") ? result.get("action").getAsString() : "";
        ConsentAnswer.Decision decision;
        String note = "";
        switch (action) {
            case "accept" -> {
                JsonObject content = result.has("content") && result.get("content").isJsonObject()
                        ? result.getAsJsonObject("content") : new JsonObject();
                String picked = content.has("decision") ? content.get("decision").getAsString() : "";
                decision = switch (picked) {
                    case "allow" -> ConsentAnswer.Decision.ALLOW_ONCE;
                    case "allow_and_remember" -> ConsentAnswer.Decision.ALLOW_REMEMBER;
                    case "deny" -> ConsentAnswer.Decision.DENY;
                    default -> null;
                };
                if (decision == ConsentAnswer.Decision.DENY && content.has("note")) {
                    note = content.get("note").getAsString();
                }
            }
            case "decline" -> decision = ConsentAnswer.Decision.DENY;
            default -> decision = null;
        }
        if (decision != null) {
            handsOf.apply(owner).answer(offer.companion(), offer.id(), decision, note);
        }
    }

    // ---- result envelopes ----

    private static JsonObject textItem(String text) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", text);
        return item;
    }

    private static JsonObject content(String text, boolean isError) {
        JsonArray arr = new JsonArray();
        arr.add(textItem(text));
        JsonObject result = new JsonObject();
        result.add("content", arr);
        result.addProperty("isError", isError);
        return result;
    }

    private JsonObject okResponse(JsonElement id, JsonObject result) {
        JsonObject r = new JsonObject();
        r.addProperty("jsonrpc", "2.0");
        r.add("id", id);
        r.add("result", result);
        return r;
    }

    private JsonObject errorResponse(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject r = new JsonObject();
        r.addProperty("jsonrpc", "2.0");
        r.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        r.add("error", err);
        return r;
    }
}
