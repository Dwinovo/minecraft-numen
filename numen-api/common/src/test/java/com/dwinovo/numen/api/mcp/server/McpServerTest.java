package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.agent.inbox.ExternalWait;
import com.dwinovo.numen.api.home.Home;
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
    private McpConfig config;

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
        server = new McpServer(with, tokens, new McpLimits(with.limits(), now::get), sessions, new McpAudit(home),
                owner -> hands);
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
    void aRefusedConnectionIsAuditedToo() throws Exception {
        post("numen_wrong", null, rpc(1, "initialize", initParams(false)));

        assertTrue(Files.readString(dir.resolve(McpAudit.FILE)).contains("\"type\":\"refused\""));
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
}
