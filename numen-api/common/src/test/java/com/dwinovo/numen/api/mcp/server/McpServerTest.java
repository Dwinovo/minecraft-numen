package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.agent.inbox.ExternalWait;
import com.dwinovo.numen.api.brain.Visits;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.permission.ConsentAnswer;
import com.dwinovo.numen.api.permission.ConsentDesk;
import com.dwinovo.numen.api.permission.ConsentVenues;
import com.dwinovo.numen.api.store.Io;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 远程端点走一遍真的 HTTP(进程内,假的手):令牌认人、会话、作用域、限流、审计、断开、线程收干净。
 * 每条都在对应的东西坏掉时变红——没带令牌能进、别人的会话能用、作用域外的同伴能点到、超了限还放行、
 * 审计里漏出内容、停服后线程还在,都会有一条挂。
 */
class McpServerTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000c002");

    /** 假的手:记下 say 说了什么;名册是固定的两只。 */
    private static final class Hands implements ExternalHands {
        final List<String> said = new ArrayList<>();
        final List<String> answers = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> heard = new ArrayList<>();

        @Override
        public CompletableFuture<List<Companion>> companions() {
            return CompletableFuture.completedFuture(List.of(new Companion(ALICE, "alice"), new Companion(BOB, "bob")));
        }

        @Override
        public CompletableFuture<Boolean> create(String name) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> delete(UUID companion) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<String> takeEvents(UUID companion) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public ExternalWait.Parked awaitEvents(UUID companion) {
            return new ExternalWait.Parked(new CompletableFuture<>(), () -> null);
        }

        @Override
        public CompletableFuture<Boolean> say(UUID companion, String text) {
            said.add(companion + ":" + text);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> hear(UUID companion, String text, Visits.Channel channel) {
            heard.add(companion + ":" + text);
            channel.said(companion, "heard: " + text);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<ConsentDesk.Reply> answer(UUID companion, long id,
                                                           ConsentAnswer.Decision decision, String note) {
            answers.add(companion + "#" + id + ":" + decision + ":" + note);
            return CompletableFuture.completedFuture(ConsentDesk.Reply.ANSWERED);
        }

        @Override
        public CompletableFuture<String> invoke(UUID companion, String toolName, String argsJson) {
            return CompletableFuture.completedFuture("{}");
        }
    }

    @TempDir
    Path dir;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final Hands hands = new Hands();
    private final HttpClient http = HttpClient.newHttpClient();
    private Home home;
    private McpTokens tokens;
    private McpSessions sessions;
    private McpServer server;
    private final McpChannels channels = new McpChannels();
    private McpConfig config;
    private McpAudit audit;
    private McpStrangers strangers;

    @BeforeEach
    void start() throws Exception {
        home = new Home(dir, Io.direct(e -> { throw e; }));
        config = new McpConfig(true, McpConfig.LOOPBACK, 0, 30, List.of(), McpConfig.Limits.DEFAULT);
        tokens = new McpTokens(home, now::get);
        sessions = new McpSessions(now::get);
        launch(config);
    }

    private void launch(McpConfig with) throws Exception {
        if (server != null) {
            server.stop();
        }
        config = with;
        audit = new McpAudit(home);
        strangers = new McpStrangers(audit, now::get);
        server = new McpServer(with, tokens, new McpLimits(with.limits(), now::get), sessions, audit, strangers,
                channels, owner -> hands);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    // ---- 小件 ----

    private McpTokens.Issued brain(McpToken.Scope scope) {
        return tokens.issue(OWNER, McpToken.Role.BRAIN, scope, "", 0);
    }

    private HttpResponse<String> post(String bearer, String session, JsonObject body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        if (session != null) {
            b.header("Mcp-Session-Id", session);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject rpc(int id, String method, JsonObject params) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("id", id);
        o.addProperty("method", method);
        o.add("params", params);
        return o;
    }

    private static JsonObject initParams(boolean elicitation) {
        JsonObject p = new JsonObject();
        p.addProperty("protocolVersion", "2025-06-18");
        JsonObject caps = new JsonObject();
        if (elicitation) {
            caps.add("elicitation", new JsonObject());
        }
        p.add("capabilities", caps);
        JsonObject info = new JsonObject();
        info.addProperty("name", "test-client");
        info.addProperty("version", "1");
        p.add("clientInfo", info);
        return p;
    }

    /** 接入,返回会话号。 */
    private String connect(String secret) throws Exception {
        HttpResponse<String> r = post(secret, null, rpc(1, "initialize", initParams(false)));
        assertEquals(200, r.statusCode(), r.body());
        return r.headers().firstValue("Mcp-Session-Id").orElseThrow();
    }

    private JsonObject call(String secret, String session, String tool, JsonObject args) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", tool);
        params.add("arguments", args);
        HttpResponse<String> r = post(secret, session, rpc(2, "tools/call", params));
        assertEquals(200, r.statusCode(), r.body());
        return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("result");
    }

    private static String textOf(JsonObject result) {
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    private static JsonObject args(String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }

    // ---- 认人 ----

    @Test
    void noTokenAWrongTokenAndATokenInTheQueryStringAreAllRefused() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();

        assertEquals(401, post(null, null, rpc(1, "initialize", initParams(false))).statusCode());
        assertEquals(401, post("numen_not-a-real-token", null, rpc(1, "initialize", initParams(false))).statusCode());
        HttpResponse<String> viaQuery = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/mcp?token=" + secret))
                .POST(HttpRequest.BodyPublishers.ofString(rpc(1, "initialize", initParams(false)).toString()))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, viaQuery.statusCode(), "令牌不许走查询串");
    }

    @Test
    void anExpiredTokenStopsWorking() throws Exception {
        McpTokens.Issued issued = tokens.issue(OWNER, McpToken.Role.BRAIN, McpToken.Scope.ALL, "", 60_000);
        connect(issued.secret());

        now.addAndGet(60_001);

        assertEquals(401, post(issued.secret(), null, rpc(1, "initialize", initParams(false))).statusCode());
    }

    @Test
    void aSessionIsNeededAndBelongsToTheTokenThatOpenedIt() throws Exception {
        String a = brain(McpToken.Scope.ALL).secret();
        String b = brain(McpToken.Scope.ALL).secret();
        String sessionOfA = connect(a);

        assertEquals(400, post(a, null, rpc(2, "tools/list", new JsonObject())).statusCode(), "没带会话号");
        assertEquals(404, post(a, "no-such-session", rpc(2, "tools/list", new JsonObject())).statusCode());
        assertEquals(404, post(b, sessionOfA, rpc(2, "tools/list", new JsonObject())).statusCode(),
                "别的令牌不能用这个会话");
        assertEquals(200, post(a, sessionOfA, rpc(2, "tools/list", new JsonObject())).statusCode());
    }

    @Test
    void deletingTheSessionEndsIt() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        String session = connect(secret);

        HttpResponse<String> deleted = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                .header("Authorization", "Bearer " + secret).header("Mcp-Session-Id", session)
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, deleted.statusCode());
        assertEquals(404, post(secret, session, rpc(2, "ping", new JsonObject())).statusCode());
        assertFalse(sessions.brainConnected(OWNER));
    }

    // ---- 作用域 ----

    @Test
    void aScopedTokenSeesAndReachesOnlyItsOwnCompanions() throws Exception {
        String secret = brain(McpToken.Scope.of(Set.of(ALICE))).secret();
        String session = connect(secret);

        String listed = textOf(call(secret, session, "list_companions", new JsonObject()));
        assertTrue(listed.contains("alice") && !listed.contains("bob"), listed);

        JsonObject toBob = args("companion", "bob");
        toBob.addProperty("text", "hi");
        JsonObject refused = call(secret, session, "say", toBob);
        assertTrue(refused.get("isError").getAsBoolean(), "作用域外的同伴不该点得到");
        assertTrue(hands.said.isEmpty());

        JsonObject toAlice = args("companion", "alice");
        toAlice.addProperty("text", "hi");
        assertEquals("said", textOf(call(secret, session, "say", toAlice)));
        assertEquals(List.of(ALICE + ":hi"), hands.said);
    }

    @Test
    void onlyATokenCoveringEveryoneMaySummon() throws Exception {
        String scoped = brain(McpToken.Scope.of(Set.of(ALICE))).secret();
        String everyone = brain(McpToken.Scope.ALL).secret();

        assertTrue(call(scoped, connect(scoped), "create_companion", args("name", "carol")).get("isError")
                .getAsBoolean());
        assertFalse(call(everyone, connect(everyone), "create_companion", args("name", "carol")).get("isError")
                .getAsBoolean());
    }

    // ---- 驾驶席 ----

    @Test
    void theSeatIsTakenWhileASessionCoversHerAndReturnedWhenItEnds() throws Exception {
        String secret = brain(McpToken.Scope.of(Set.of(ALICE))).secret();
        assertFalse(sessions.drives(OWNER, ALICE));

        String session = connect(secret);

        assertTrue(sessions.drives(OWNER, ALICE));
        assertFalse(sessions.drives(OWNER, BOB), "令牌管不着的同伴驾驶席不动");
        assertFalse(sessions.drives(UUID.randomUUID(), ALICE), "别的主人的令牌不算");

        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                .header("Authorization", "Bearer " + secret).header("Mcp-Session-Id", session)
                .DELETE().build(), HttpResponse.BodyHandlers.discarding());
        assertFalse(sessions.drives(OWNER, ALICE));
    }

    @Test
    void aSilentSessionStopsCountingAsConnectedButARevokedTokenEndsAtOnce() throws Exception {
        McpTokens.Issued issued = brain(McpToken.Scope.ALL);
        connect(issued.secret());
        assertTrue(sessions.brainConnected(OWNER));

        now.addAndGet(McpSessions.IDLE_MS + 1);
        assertFalse(sessions.brainConnected(OWNER), "安静太久不再算连着");

        connect(issued.secret());
        assertTrue(sessions.brainConnected(OWNER));
        assertTrue(tokens.revoke(OWNER, issued.token().id()));
        sessions.sweep(tokens::valid);
        assertFalse(sessions.brainConnected(OWNER), "令牌作废,会话随即断开");
    }

    @Test
    void aTokenCannotBeRevokedByAnotherOwner() {
        McpTokens.Issued issued = brain(McpToken.Scope.ALL);

        assertFalse(tokens.revoke(UUID.randomUUID(), issued.token().id()));
        assertTrue(tokens.authenticate(issued.secret()).isPresent());
    }

    // ---- 限流 ----

    @Test
    void callsPastThePerTokenRateAreAnsweredWithAWaitNotRun() throws Exception {
        launch(new McpConfig(true, McpConfig.LOOPBACK, 0, 30, List.of(),
                new McpConfig.Limits(8, 2, 64, 1200)));
        String secret = brain(McpToken.Scope.ALL).secret();
        String session = connect(secret);   // 接入也占一次

        JsonObject first = call(secret, session, "list_companions", new JsonObject());
        JsonObject second = call(secret, session, "list_companions", new JsonObject());

        assertFalse(first.get("isError").getAsBoolean());
        assertTrue(second.get("isError").getAsBoolean());
        assertTrue(textOf(second).contains("rate limited"), textOf(second));

        now.addAndGet(60_000);
        assertFalse(call(secret, session, "list_companions", new JsonObject()).get("isError").getAsBoolean(),
                "一分钟后额度回来");
    }

    @Test
    void oneTokensRateDoesNotEatAnothers() throws Exception {
        launch(new McpConfig(true, McpConfig.LOOPBACK, 0, 30, List.of(), new McpConfig.Limits(8, 2, 64, 1200)));
        String a = brain(McpToken.Scope.ALL).secret();
        String b = brain(McpToken.Scope.ALL).secret();
        String sessionA = connect(a);
        call(a, sessionA, "list_companions", new JsonObject());

        String sessionB = connect(b);

        assertFalse(call(b, sessionB, "list_companions", new JsonObject()).get("isError").getAsBoolean());
    }

    @Test
    void theServerWideCapHoldsAcrossTokens() throws Exception {
        launch(new McpConfig(true, McpConfig.LOOPBACK, 0, 30, List.of(), new McpConfig.Limits(8, 100, 64, 3)));
        String a = brain(McpToken.Scope.ALL).secret();
        String b = brain(McpToken.Scope.ALL).secret();
        String sessionA = connect(a);
        String sessionB = connect(b);

        assertFalse(call(a, sessionA, "list_companions", new JsonObject()).get("isError").getAsBoolean());
        JsonObject over = call(b, sessionB, "list_companions", new JsonObject());
        assertTrue(over.get("isError").getAsBoolean());
        assertTrue(textOf(over).contains("rate limited"), textOf(over));
    }

    // ---- 审计 ----

    @Test
    void theAuditLogNamesWhoCalledWhatAndNothingElse() throws Exception {
        McpTokens.Issued issued = brain(McpToken.Scope.ALL);
        String session = connect(issued.secret());
        JsonObject say = args("companion", "alice");
        say.addProperty("text", "SECRET-WORDS-不该进日志");
        call(issued.secret(), session, "say", say);

        String audit = Files.readString(dir.resolve(McpAudit.FILE), StandardCharsets.UTF_8);

        assertTrue(audit.contains("\"type\":\"connect\"") && audit.contains("\"type\":\"call\""), audit);
        assertTrue(audit.contains("\"tool\":\"say\"") && audit.contains(issued.token().id()), audit);
        assertTrue(audit.contains("\"ok\":true"), audit);
        assertFalse(audit.contains("SECRET-WORDS"), "审计不记内容");
        assertFalse(audit.contains(issued.secret()), "审计不记令牌");
        assertFalse(Files.readString(dir.resolve(McpTokens.FILE)).contains(issued.secret()), "令牌库只存哈希");
    }

    @Test
    void aRefusedConnectionIsAuditedOnceItsWindowCloses() throws Exception {
        post("numen_wrong", null, rpc(1, "initialize", initParams(false)));
        post("numen_wrong", null, rpc(1, "initialize", initParams(false)));

        now.addAndGet(McpStrangers.WINDOW_MS);
        strangers.flushClosed();

        String audit = Files.readString(dir.resolve(McpAudit.FILE));
        assertEquals(1, audit.lines().filter(l -> l.contains("\"type\":\"refused\"")).count(), audit);
        assertTrue(audit.contains("\"count\":2") && audit.contains("\"address\":\"127.0.0.1\""), audit);
    }

    @Test
    void aFloodOfStrangersIsThrottledAndLeavesOneAuditLine() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        int total = McpStrangers.FAILURES_PER_WINDOW + 20;

        int refused = 0;
        int throttled = 0;
        for (int i = 0; i < total; i++) {
            int status = post("numen_guess" + i, null, rpc(1, "initialize", initParams(false))).statusCode();
            if (status == 401) refused++;
            if (status == 429) throttled++;
        }

        assertEquals(McpStrangers.FAILURES_PER_WINDOW, refused);
        assertEquals(20, throttled, "窗里失败满了,之后的不再查令牌就拒绝");
        assertEquals(200, post(secret, null, rpc(1, "initialize", initParams(false))).statusCode(),
                "被限期间对的令牌照常通过(代理后面共用地址的合法用户不受连累)");
        assertEquals(429, post("numen_still-wrong", null, rpc(1, "initialize", initParams(false))).statusCode());
        now.addAndGet(McpStrangers.WINDOW_MS);
        assertEquals(401, post("numen_wrong-after-window", null, rpc(1, "initialize", initParams(false))).statusCode(),
                "窗过了又从头数");
        strangers.flushClosed();
        String audit = Files.readString(dir.resolve(McpAudit.FILE));
        assertEquals(1, audit.lines().filter(l -> l.contains("\"type\":\"refused\"")).count(),
                "洪水只合成一条审计:" + audit);
        assertTrue(audit.contains("\"count\":" + (total + 1)), audit);   // 洪水加一次被限的错令牌
    }

    // ---- 流与线程 ----

    @Test
    void theServerToClientStreamOpensAndStopClosesItAndItsThreads() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        String session = connect(secret);
        HttpResponse<java.io.InputStream> stream = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                .header("Authorization", "Bearer " + secret).header("Mcp-Session-Id", session)
                .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, stream.statusCode());
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8));
        assertNotNull(reader.readLine(), "流一开就有一行");
        awaitStream(session);

        Thread dispatcher = dispatcherThread();
        assertNotNull(dispatcher, "服务在跑就有调度线程");
        assertTrue(dispatcher.isDaemon(), "调度线程是守护线程:没来得及 stop 也拖不住 JVM 退出");

        server.stop();

        assertTrue(streamEnded(reader), "停服关掉流");
        dispatcher.join(5_000);
        assertFalse(dispatcher.isAlive(), "停服后调度线程收掉了");
        assertFalse(sessions.brainConnected(OWNER));
    }

    private static boolean streamEnded(BufferedReader reader) {
        try {
            while (reader.readLine() != null) {
                // 把已经写出的保活行读完
            }
            return true;
        } catch (java.io.IOException reset) {
            return true;   // 对方关得急,读到的是复位
        }
    }

    private void awaitStream(String id) throws InterruptedException {
        for (int i = 0; i < 100 && !sessions.find(id).hasStream(); i++) {
            Thread.sleep(20);
        }
        assertTrue(sessions.find(id).hasStream());
    }

    private static Thread dispatcherThread() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("HTTP-Dispatcher") && t.isAlive()).findFirst().orElse(null);
    }

    // ---- 征询经客户端问人 ----

    private static final ConsentVenues.Offer OFFER = new ConsentVenues.Offer(7, ALICE, "alice",
            "break 6 oak_log (placed by a player)", List.of("allow break(minecraft:oak_log)"));

    /** 以会声明 elicitation 的客户端接入,并开着流;返回流的读端。 */
    private BufferedReader eliciting(String secret, String[] sessionOut) throws Exception {
        HttpResponse<String> init = post(secret, null, rpc(1, "initialize", initParams(true)));
        sessionOut[0] = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        HttpResponse<java.io.InputStream> stream = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                .header("Authorization", "Bearer " + secret).header("Mcp-Session-Id", sessionOut[0])
                .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        awaitStream(sessionOut[0]);
        return new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8));
    }

    /** 读到下一条 SSE 消息(跳过注释行与保活)。 */
    private static JsonObject nextMessage(BufferedReader reader) throws Exception {
        for (String line; (line = reader.readLine()) != null; ) {
            if (line.startsWith("data: ")) {
                return JsonParser.parseString(line.substring(6)).getAsJsonObject();
            }
        }
        throw new AssertionError("the stream ended");
    }

    private void reply(String secret, String session, String id, JsonObject result) throws Exception {
        JsonObject frame = new JsonObject();
        frame.addProperty("jsonrpc", "2.0");
        frame.addProperty("id", id);
        frame.add("result", result);
        assertEquals(202, post(secret, session, frame).statusCode());
    }

    private static JsonObject accepted(String decision, String note) {
        JsonObject content = new JsonObject();
        content.addProperty("decision", decision);
        if (note != null) {
            content.addProperty("note", note);
        }
        JsonObject result = new JsonObject();
        result.addProperty("action", "accept");
        result.add("content", content);
        return result;
    }

    private void awaitAnswers(int n) throws InterruptedException {
        for (int i = 0; i < 100 && hands.answers.size() < n; i++) {
            Thread.sleep(20);
        }
    }

    @Test
    void aConsentIsPutToTheHumanAtTheExternalBrainsClientAndHisChoiceIsTheAnswer() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        String[] session = new String[1];
        BufferedReader stream = eliciting(secret, session);

        assertTrue(server.elicit(OWNER, OFFER));

        JsonObject ask = nextMessage(stream);
        assertEquals("elicitation/create", ask.get("method").getAsString());
        String message = ask.getAsJsonObject("params").get("message").getAsString();
        assertTrue(message.contains("alice") && message.contains("break 6 oak_log"), message);
        assertEquals(3, ask.getAsJsonObject("params").getAsJsonObject("requestedSchema").getAsJsonObject("properties")
                .getAsJsonObject("decision").getAsJsonArray("enum").size());

        reply(secret, session[0], ask.get("id").getAsString(), accepted("allow_and_remember", null));
        awaitAnswers(1);

        assertEquals(List.of(ALICE + "#7:ALLOW_REMEMBER:"), hands.answers);
    }

    @Test
    void decliningDeniesAndANoteRidesOnlyWithADenial() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        String[] session = new String[1];
        BufferedReader stream = eliciting(secret, session);

        server.elicit(OWNER, OFFER);
        JsonObject first = nextMessage(stream);
        JsonObject declined = new JsonObject();
        declined.addProperty("action", "decline");
        reply(secret, session[0], first.get("id").getAsString(), declined);
        server.elicit(OWNER, new ConsentVenues.Offer(8, ALICE, "alice", "x", List.of()));
        JsonObject second = nextMessage(stream);
        reply(secret, session[0], second.get("id").getAsString(), accepted("deny", "use the chest instead"));
        awaitAnswers(2);

        assertEquals(List.of(ALICE + "#7:DENY:", ALICE + "#8:DENY:use the chest instead"), hands.answers);
    }

    @Test
    void closingThePromptWithoutChoosingIsNotAnAnswer() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        String[] session = new String[1];
        BufferedReader stream = eliciting(secret, session);

        server.elicit(OWNER, OFFER);
        JsonObject ask = nextMessage(stream);
        JsonObject cancelled = new JsonObject();
        cancelled.addProperty("action", "cancel");
        reply(secret, session[0], ask.get("id").getAsString(), cancelled);
        Thread.sleep(200);

        assertTrue(hands.answers.isEmpty(), "没选就关掉,征询仍挂着等主人进游戏答: " + hands.answers);
    }

    @Test
    void aClientThatCannotElicitIsNeverAsked() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        String session = connect(secret);   // 没声明 elicitation

        assertFalse(server.elicit(OWNER, OFFER), "不支持就问不了,只能等主人进游戏答");
        assertFalse(sessions.find(session).elicits);
    }

    @Test
    void aClientWithoutAStreamCannotBeAskedEither() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        post(secret, null, rpc(1, "initialize", initParams(true)));

        assertFalse(server.elicit(OWNER, OFFER));
    }

    @Test
    void onlyTheOwnersOwnBrainCoveringHerIsAsked() throws Exception {
        String scopedElsewhere = brain(McpToken.Scope.of(Set.of(BOB))).secret();
        eliciting(scopedElsewhere, new String[1]);

        assertFalse(server.elicit(OWNER, OFFER), "令牌管不着这只同伴");
        assertFalse(server.elicit(UUID.randomUUID(), OFFER), "别的主人的外部大脑不该被问");
    }

    @Test
    void whenTheOwnerAnswersElsewhereThePromptIsCancelled() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        String[] session = new String[1];
        BufferedReader stream = eliciting(secret, session);
        server.elicit(OWNER, OFFER);
        JsonObject ask = nextMessage(stream);

        server.withdraw(OFFER.id());

        JsonObject cancelled = nextMessage(stream);
        assertEquals("notifications/cancelled", cancelled.get("method").getAsString());
        assertEquals(ask.get("id").getAsString(), cancelled.getAsJsonObject("params").get("requestId").getAsString());
        reply(secret, session[0], ask.get("id").getAsString(), accepted("allow", null));
        Thread.sleep(200);
        assertTrue(hands.answers.isEmpty(), "撤掉之后迟到的答复不再算");
    }

    // ---- 渠道 ----

    private McpTokens.Issued channel(McpToken.Scope scope) {
        return tokens.issue(OWNER, McpToken.Role.CHANNEL, scope, "", 0);
    }

    private static List<String> toolNames(JsonObject listResult) {
        List<String> names = new ArrayList<>();
        listResult.getAsJsonArray("tools").forEach(t -> names.add(t.getAsJsonObject().get("name").getAsString()));
        return names;
    }

    @Test
    void aChannelOnlySeesAndMayUseTheChannelTools() throws Exception {
        String secret = channel(McpToken.Scope.ALL).secret();
        String session = connect(secret);

        HttpResponse<String> listed = post(secret, session, rpc(2, "tools/list", new JsonObject()));
        List<String> names = toolNames(JsonParser.parseString(listed.body()).getAsJsonObject().getAsJsonObject("result"));
        assertEquals(List.of("list_companions", "send", "get_replies", "answer_consent"), names);

        JsonObject driving = args("companion", "alice");
        driving.addProperty("text", "hi");
        JsonObject refused = call(secret, session, "say", driving);
        assertTrue(refused.get("isError").getAsBoolean(), "渠道不能驾驶");
        assertTrue(hands.said.isEmpty());
        assertTrue(call(secret, session, "get_events", args("companion", "alice")).get("isError").getAsBoolean());
    }

    @Test
    void aChannelsWordsGoInAndHerAnswerComesBackToThatChannelOnly() throws Exception {
        String secret = channel(McpToken.Scope.of(Set.of(ALICE))).secret();
        String other = channel(McpToken.Scope.ALL).secret();
        String session = connect(secret);
        String otherSession = connect(other);

        JsonObject words = args("companion", "alice");
        words.addProperty("text", "你好");
        assertFalse(call(secret, session, "send", words).get("isError").getAsBoolean());
        assertEquals(List.of(ALICE + ":你好"), hands.heard);

        JsonObject wait = new JsonObject();
        wait.addProperty("wait_seconds", 1);
        JsonObject replies = JsonParser.parseString(textOf(call(secret, session, "get_replies", wait)))
                .getAsJsonObject();
        assertEquals(1, replies.getAsJsonArray("items").size());
        JsonObject item = replies.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("said", item.get("type").getAsString());
        assertEquals("heard: 你好", item.get("text").getAsString());

        JsonObject nothing = JsonParser.parseString(textOf(call(other, otherSession, "get_replies", wait)))
                .getAsJsonObject();
        assertEquals(0, nothing.getAsJsonArray("items").size(), "别的渠道不该收到这一份");
    }

    @Test
    void aChannelCannotSpeakToCompanionsOutsideItsScope() throws Exception {
        String secret = channel(McpToken.Scope.of(Set.of(ALICE))).secret();
        String session = connect(secret);
        JsonObject words = args("companion", "bob");
        words.addProperty("text", "hi");

        assertTrue(call(secret, session, "send", words).get("isError").getAsBoolean());
        assertTrue(hands.heard.isEmpty());
    }

    @Test
    void theOwnersAnswerOnAChannelGoesToTheDeskLikeAnyOther() throws Exception {
        String secret = channel(McpToken.Scope.ALL).secret();
        String session = connect(secret);
        JsonObject answer = args("companion", "alice");
        answer.addProperty("id", 7);
        answer.addProperty("decision", "remember");

        assertFalse(call(secret, session, "answer_consent", answer).get("isError").getAsBoolean());
        assertEquals(List.of(ALICE + "#7:ALLOW_REMEMBER:"), hands.answers);

        JsonObject allowWithNote = args("companion", "alice");
        allowWithNote.addProperty("id", 8);
        allowWithNote.addProperty("decision", "allow");
        allowWithNote.addProperty("note", "sneaky");
        assertTrue(call(secret, session, "answer_consent", allowWithNote).get("isError").getAsBoolean(),
                "附言只随拒绝");
        assertEquals(1, hands.answers.size());
    }

    @Test
    void aChannelSessionDoesNotMakeTheOwnerPresentOrTakeTheSeat() throws Exception {
        String secret = channel(McpToken.Scope.ALL).secret();
        connect(secret);

        assertFalse(sessions.brainConnected(OWNER));
        assertFalse(sessions.drives(OWNER, ALICE));
    }
}
