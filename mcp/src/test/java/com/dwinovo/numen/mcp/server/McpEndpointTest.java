package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.api.store.Io;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端点起在随机端口上,用官方 SDK 的客户端连上去走一遍:认人、工具表(注解、图标、serverInfo)、调用、进度、征询、限流、审计、
 * 谁连着、停服收干净。每条在对应的东西坏掉时变红——没带令牌能进、别人的会话能用、超了限还放行、审计里漏出内容、
 * 停服后线程还在,都会有一条挂。
 */
class McpEndpointTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000c002");
    /** 1x1 的 PNG。 */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEAHHgH5QAAAABJRU5ErkJggg==");

    /** 假的宿主:两个工具;lua 报两次进展;"fail" 抛异常;记下每次调用。 */
    private static final class Host implements McpHost {
        volatile List<ToolInfo> tools = List.of(
                new ToolInfo("lua", "Run program", "runs a program", "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"}}}",
                        new Hints(false, true, false, true)),
                new ToolInfo("get_events", "Wait for events", "waits", "{\"type\":\"object\"}",
                        new Hints(true, false, false, false)));
        final List<String> calls = new CopyOnWriteArrayList<>();
        /** "hold" 这个工具停在这里,直到用例放行。 */
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch holding = new CountDownLatch(1);

        @Override
        public Identity identity() {
            return new Identity("numen-test", "Numen", "A test host", "9.9.9", PNG);
        }

        @Override
        public String instructions() {
            return "drive carefully";
        }

        @Override
        public List<ToolInfo> tools(McpToken who) {
            return who.role() == McpToken.Role.CHANNEL
                    ? List.of(new ToolInfo("send", null, "sends", "{\"type\":\"object\"}", new Hints(false, false, false, false)))
                    : tools;
        }

        @Override
        public Result call(McpToken who, String tool, String argsJson, int timeoutSeconds, Progress progress) throws Exception {
            calls.add(tool + " " + argsJson);
            if (tool.equals("fail")) {
                throw new IllegalStateException("boom");
            }
            if (tool.equals("hold")) {
                holding.countDown();
                release.await(20, TimeUnit.SECONDS);
                return Result.text("released", false);
            }
            progress.report("step 1");
            progress.report("step 2");
            return new Result(List.of("ran " + tool, "<runtime_state/>"), false, ALICE);
        }
    }

    @TempDir
    Path dir;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final Host host = new Host();
    private final Io io = Io.direct(e -> { throw e; });
    private McpTokens tokens;
    private McpPresence presence;
    private McpAudit audit;
    private McpStrangers strangers;
    private McpEndpoint endpoint;
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<McpSyncClient> clients = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        tokens = new McpTokens(dir, io, now::get);
        presence = new McpPresence(now::get);
        launch(new McpConfig(true, McpConfig.LOOPBACK, 0, 30, List.of(), McpConfig.Limits.DEFAULT));
    }

    private void launch(McpConfig config) throws Exception {
        if (endpoint != null) {
            endpoint.stop();
        }
        audit = new McpAudit(dir, io);
        strangers = new McpStrangers(audit, now::get);
        endpoint = new McpEndpoint(config, tokens, new McpLimits(config.limits(), now::get), audit, strangers, presence, host);
        endpoint.start();
    }

    @AfterEach
    void stop() {
        for (McpSyncClient client : clients) {
            try {
                client.closeGracefully();
            } catch (RuntimeException alreadyGone) {
                // 测试里自己关过的
            }
        }
        endpoint.stop();
    }

    private McpTokens.Issued brain(McpToken.Scope scope) {
        return tokens.issue(OWNER, McpToken.Role.BRAIN, scope, "", 0);
    }

    private String url() {
        return "http://127.0.0.1:" + endpoint.port();
    }

    private McpClient.SyncSpec spec(String secret) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(url())
                .endpoint("/mcp").openConnectionOnStartup(true)
                .addHttpRequestCustomizer((builder, method, uri, body, context) ->
                        builder.header("Authorization", "Bearer " + secret))
                .build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20))
                .clientInfo(new McpSchema.Implementation("test-client", "1"));
    }

    private McpSyncClient connect(String secret) {
        McpSyncClient client = spec(secret).build();
        client.initialize();
        clients.add(client);
        return client;
    }

    private HttpResponse<String> post(String bearer, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url() + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
            + "\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"raw\",\"version\":\"1\"}}}";

    private static McpSchema.CallToolRequest call(String tool, Object progressToken) {
        McpSchema.CallToolRequest.Builder b = McpSchema.CallToolRequest.builder().name(tool).arguments(Map.of("code", "x"));
        if (progressToken != null) {
            b.progressToken(progressToken);
        }
        return b.build();
    }

    // ---- 认人 ----

    @Test
    void noTokenAWrongTokenAndATokenInTheQueryStringAreAllRefused() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();

        assertEquals(401, post(null, INIT).statusCode());
        assertEquals(401, post("numen_not-a-real-token", INIT).statusCode());
        HttpResponse<String> viaQuery = http.send(HttpRequest.newBuilder(URI.create(url() + "/mcp?token=" + secret))
                .POST(HttpRequest.BodyPublishers.ofString(INIT)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, viaQuery.statusCode(), "令牌不许走查询串");
    }

    @Test
    void aForeignOriginIsRefusedAndTheSameOriginPasses() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        String hostHeader = "127.0.0.1:" + endpoint.port();

        // JDK 的 HttpClient 不让设 Host/Origin 以外的受限头,Origin 可以设
        HttpRequest foreign = HttpRequest.newBuilder(URI.create(url() + "/mcp"))
                .header("Authorization", "Bearer " + secret).header("Origin", "http://evil.example")
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(INIT)).build();
        assertEquals(403, http.send(foreign, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, post(secret, INIT, "Origin", "http://" + hostHeader).statusCode());
    }

    @Test
    void aRebindingRequestWhoseHostIsNotLoopbackIsRefusedOnALoopbackEndpoint() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();

        // 重绑定来的请求 Host 与 Origin 都是攻击者的域名,同源检查拦不住,要看 Host 是不是回环的名字
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", endpoint.port())) {
            String body = INIT;
            socket.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: evil.example.com\r\n"
                    + "Origin: http://evil.example.com\r\nAuthorization: Bearer " + secret + "\r\n"
                    + "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                    + "Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body)
                    .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String status = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                    StandardCharsets.UTF_8)).readLine();
            assertTrue(status.contains("403"), status);
        }
    }

    @Test
    void anExpiredTokenStopsWorking() throws Exception {
        McpTokens.Issued issued = tokens.issue(OWNER, McpToken.Role.BRAIN, McpToken.Scope.ALL, "", 60_000);
        assertEquals(200, post(issued.secret(), INIT).statusCode());

        now.addAndGet(60_001);

        assertEquals(401, post(issued.secret(), INIT).statusCode());
    }

    @Test
    void aSessionBelongsToTheTokenThatOpenedIt() throws Exception {
        String a = brain(McpToken.Scope.ALL).secret();
        String b = brain(McpToken.Scope.ALL).secret();
        HttpResponse<String> opened = post(a, INIT);
        String session = opened.headers().firstValue("Mcp-Session-Id").orElseThrow();
        String list = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";

        assertEquals(200, post(a, list, "Mcp-Session-Id", session, "MCP-Protocol-Version", "2025-06-18").statusCode());
        assertEquals(404, post(b, list, "Mcp-Session-Id", session, "MCP-Protocol-Version", "2025-06-18").statusCode(),
                "别的令牌不能用这个会话");
    }

    // ---- 工具表与自我介绍 ----

    @Test
    void theServerIntroducesItselfAndListsItsToolsWithTheirNatureAndIcons() {
        McpSyncClient client = connect(brain(McpToken.Scope.ALL).secret());

        McpSchema.Implementation info = client.getServerInfo();
        assertEquals("numen-test", info.name());
        assertEquals("Numen", info.title());
        assertEquals("A test host", info.description());
        assertEquals("9.9.9", info.version());
        assertEquals(1, info.icons().size());
        assertTrue(info.icons().get(0).src().startsWith("data:image/png;base64,"));
        assertEquals(List.of("1x1"), info.icons().get(0).sizes());
        assertEquals("drive carefully", client.getCurrentInitializationResult().instructions());

        List<McpSchema.Tool> tools = client.listTools().tools();
        assertEquals(List.of("lua", "get_events"), tools.stream().map(McpSchema.Tool::name).toList(), "宿主给的顺序原样");
        McpSchema.Tool lua = tools.get(0);
        assertEquals("Run program", lua.title());
        assertEquals(true, lua.annotations().destructiveHint());
        assertEquals(false, lua.annotations().readOnlyHint());
        assertEquals(true, lua.annotations().openWorldHint());
        assertEquals(1, lua.icons().size());
        assertEquals(true, tools.get(1).annotations().readOnlyHint());
    }

    @Test
    void aChannelOnlySeesTheToolsTheHostGivesChannels() {
        McpSyncClient client = connect(tokens.issue(OWNER, McpToken.Role.CHANNEL, McpToken.Scope.ALL, "", 0).secret());

        assertEquals(List.of("send"), client.listTools().tools().stream().map(McpSchema.Tool::name).toList());
    }

    @Test
    void toolsTheOwnerHidesAreNotListedToABrain() throws Exception {
        launch(new McpConfig(true, McpConfig.LOOPBACK, 0, 30, List.of("lua"), McpConfig.Limits.DEFAULT));
        McpSyncClient client = connect(brain(McpToken.Scope.ALL).secret());

        assertEquals(List.of("get_events"), client.listTools().tools().stream().map(McpSchema.Tool::name).toList());
    }

    @Test
    void listingAgainPicksUpWhatTheHostChanged() {
        McpSyncClient client = connect(brain(McpToken.Scope.ALL).secret());
        assertEquals(2, client.listTools().tools().size());

        host.tools = List.of(host.tools.get(0));

        assertEquals(List.of("lua"), client.listTools().tools().stream().map(McpSchema.Tool::name).toList());
    }

    // ---- 调用与进度 ----

    @Test
    void aCallReachesTheHostAndTheResultIsEveryTextBlockInOrder() {
        McpSyncClient client = connect(brain(McpToken.Scope.ALL).secret());
        client.listTools();

        McpSchema.CallToolResult result = client.callTool(call("lua", null));

        assertFalse(result.isError());
        assertEquals(List.of("ran lua", "<runtime_state/>"),
                result.content().stream().map(c -> ((McpSchema.TextContent) c).text()).toList());
        assertEquals(List.of("lua {\"code\":\"x\"}"), host.calls);
    }

    @Test
    void progressComesBackWhenAskedForAndIncreasesOnly() throws Exception {
        List<McpSchema.ProgressNotification> seen = new CopyOnWriteArrayList<>();
        McpSyncClient client = spec(brain(McpToken.Scope.ALL).secret()).progressConsumer(seen::add).build();
        client.initialize();
        clients.add(client);
        client.listTools();

        client.callTool(call("lua", "tok-1"));
        client.callTool(call("lua", null));
        for (int i = 0; i < 100 && seen.size() < 2; i++) {
            Thread.sleep(20);
        }

        assertEquals(List.of("step 1", "step 2"), seen.stream().map(McpSchema.ProgressNotification::message).toList(),
                "带令牌的那次有两条,不带的那次一条也没有");
        assertEquals("tok-1", seen.get(0).progressToken());
        assertTrue(seen.get(1).progress() > seen.get(0).progress());
    }

    @Test
    void aThrowingHostIsAnErrorTheModelCanReadNotAProtocolFailure() {
        host.tools = List.of(new McpHost.ToolInfo("fail", null, "fails", "{\"type\":\"object\"}",
                new McpHost.Hints(false, true, false, true)));
        McpSyncClient client = connect(brain(McpToken.Scope.ALL).secret());
        client.listTools();

        McpSchema.CallToolResult result = client.callTool(call("fail", null));

        assertTrue(result.isError());
        assertTrue(((McpSchema.TextContent) result.content().get(0)).text().contains("boom"));
    }

    // ---- 征询 ----

    private static final McpQuestion QUESTION = new McpQuestion("alice asks (#7)",
            List.of(new McpQuestion.Choice("allow", "Allow once"), new McpQuestion.Choice("remember", "Allow and remember")),
            "Your answer", "Why not");

    private McpSyncClient eliciting(String secret, AtomicReference<McpSchema.ElicitFormRequest> asked) {
        McpSyncClient client = spec(secret)
                .capabilities(McpSchema.ClientCapabilities.builder()
                        .elicitation(McpSchema.ClientCapabilities.Elicitation.builder()
                                .form(new McpSchema.ClientCapabilities.Elicitation.Form()).build()).build())
                .elicitation(request -> {
                    asked.set(request);
                    return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT,
                            Map.of("decision", "remember", "note", ""), null);
                }).build();
        client.initialize();
        clients.add(client);
        client.listTools();
        return client;
    }

    /** 在另一条线程上发一次 hold 调用,它停在宿主那里,期间这个客户端有一次调用在飞。 */
    private Thread holdCall(McpSyncClient client) throws Exception {
        Thread t = Thread.ofVirtual().start(() -> client.callTool(call("hold", null)));
        assertTrue(host.holding.await(10, TimeUnit.SECONDS));
        return t;
    }

    @Test
    void aQuestionIsPutToTheHumanAtTheClientDuringACallAndHisChoiceComesBack() throws Exception {
        host.tools = List.of(host.tools.get(0), new McpHost.ToolInfo("hold", null, "holds", "{\"type\":\"object\"}",
                new McpHost.Hints(false, false, false, false)));
        AtomicReference<McpSchema.ElicitFormRequest> asked = new AtomicReference<>();
        McpSyncClient client = eliciting(brain(McpToken.Scope.ALL).secret(), asked);
        Thread call = holdCall(client);
        AtomicReference<McpQuestion.Answer> answer = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        endpoint.ask(7, OWNER, ALICE, QUESTION, a -> {
            answer.set(a);
            done.countDown();
        });

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals("remember", answer.get().choice());
        assertTrue(asked.get().message().contains("alice asks"));
        String schema = asked.get().requestedSchema().toString();
        assertTrue(schema.contains("oneOf") && schema.contains("Allow and remember"), schema);
        host.release.countDown();
        call.join(10_000);
    }

    @Test
    void aQuestionWithNoCallInFlightWaitsForTheNextCall() throws Exception {
        host.tools = List.of(host.tools.get(0), new McpHost.ToolInfo("hold", null, "holds", "{\"type\":\"object\"}",
                new McpHost.Hints(false, false, false, false)));
        AtomicReference<McpSchema.ElicitFormRequest> asked = new AtomicReference<>();
        McpSyncClient client = eliciting(brain(McpToken.Scope.ALL).secret(), asked);
        AtomicReference<McpQuestion.Answer> answer = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        endpoint.ask(7, OWNER, ALICE, QUESTION, a -> {
            answer.set(a);
            done.countDown();
        });
        assertFalse(done.await(300, TimeUnit.MILLISECONDS), "没有能借的调用,先挂着");

        Thread call = holdCall(client);

        assertTrue(done.await(10, TimeUnit.SECONDS), "下一次调用一开始就问出去了");
        assertEquals("remember", answer.get().choice());
        host.release.countDown();
        call.join(10_000);
    }

    @Test
    void aWithdrawnQuestionIsNeverAsked() throws Exception {
        host.tools = List.of(host.tools.get(0), new McpHost.ToolInfo("hold", null, "holds", "{\"type\":\"object\"}",
                new McpHost.Hints(false, false, false, false)));
        AtomicReference<McpSchema.ElicitFormRequest> asked = new AtomicReference<>();
        McpSyncClient client = eliciting(brain(McpToken.Scope.ALL).secret(), asked);
        CountDownLatch done = new CountDownLatch(1);
        endpoint.ask(7, OWNER, ALICE, QUESTION, a -> done.countDown());
        endpoint.withdraw(7);

        Thread call = holdCall(client);

        assertFalse(done.await(500, TimeUnit.MILLISECONDS));
        assertNull(asked.get());
        host.release.countDown();
        call.join(10_000);
    }

    @Test
    void aClientThatCannotElicitIsNeverAsked() throws Exception {
        host.tools = List.of(host.tools.get(0), new McpHost.ToolInfo("hold", null, "holds", "{\"type\":\"object\"}",
                new McpHost.Hints(false, false, false, false)));
        McpSyncClient client = connect(brain(McpToken.Scope.ALL).secret());
        client.listTools();
        CountDownLatch done = new CountDownLatch(1);
        endpoint.ask(7, OWNER, ALICE, QUESTION, a -> done.countDown());

        Thread call = holdCall(client);

        assertFalse(done.await(500, TimeUnit.MILLISECONDS), "不支持就问不了,只能等主人进游戏答");
        host.release.countDown();
        call.join(10_000);
    }

    @Test
    void onlyTheOwnersOwnBrainCoveringTheSubjectIsAsked() throws Exception {
        host.tools = List.of(host.tools.get(0), new McpHost.ToolInfo("hold", null, "holds", "{\"type\":\"object\"}",
                new McpHost.Hints(false, false, false, false)));
        AtomicReference<McpSchema.ElicitFormRequest> asked = new AtomicReference<>();
        McpSyncClient scopedElsewhere = eliciting(brain(McpToken.Scope.of(java.util.Set.of(BOB))).secret(), asked);
        CountDownLatch done = new CountDownLatch(1);
        endpoint.ask(7, OWNER, ALICE, QUESTION, a -> done.countDown());
        endpoint.ask(8, UUID.randomUUID(), BOB, QUESTION, a -> done.countDown());

        Thread call = holdCall(scopedElsewhere);

        assertFalse(done.await(500, TimeUnit.MILLISECONDS), "令牌管不着这个对象 / 别的主人的外部大脑不该被问");
        assertNull(asked.get());
        host.release.countDown();
        call.join(10_000);
    }

    // ---- 限流与审计 ----

    @Test
    void callsPastThePerTokenRateAreAnsweredWithAWaitNotRun() throws Exception {
        launch(new McpConfig(true, McpConfig.LOOPBACK, 0, 30, List.of(), new McpConfig.Limits(8, 2, 64, 1200)));
        McpSyncClient client = connect(brain(McpToken.Scope.ALL).secret());   // 接入也占一次
        client.listTools();

        McpSchema.CallToolResult first = client.callTool(call("lua", null));
        McpSchema.CallToolResult second = client.callTool(call("lua", null));

        assertFalse(first.isError());
        assertTrue(second.isError());
        assertTrue(((McpSchema.TextContent) second.content().get(0)).text().contains("rate limited"));
        now.addAndGet(60_000);
        assertFalse(client.callTool(call("lua", null)).isError(), "一分钟后额度回来");
    }

    @Test
    void theAuditLogNamesWhoCalledWhatAndNothingElse() throws Exception {
        McpTokens.Issued issued = brain(McpToken.Scope.ALL);
        McpSyncClient client = connect(issued.secret());
        client.listTools();
        client.callTool(McpSchema.CallToolRequest.builder().name("lua")
                .arguments(Map.of("code", "SECRET-CODE-不该进日志")).build());

        String log = Files.readString(dir.resolve(McpAudit.FILE), StandardCharsets.UTF_8);

        assertTrue(log.contains("\"type\":\"connect\"") && log.contains("\"type\":\"call\""), log);
        assertTrue(log.contains("\"tool\":\"lua\"") && log.contains(issued.token().id()), log);
        assertTrue(log.contains("\"companion\":\"" + ALICE + "\""), "宿主报的作用对象进审计");
        assertFalse(log.contains("SECRET-CODE"), "审计不记内容");
        assertFalse(log.contains(issued.secret()), "审计不记令牌");
    }

    @Test
    void aFloodOfStrangersIsThrottledButTheRightTokenStillPasses() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        int total = McpStrangers.FAILURES_PER_WINDOW + 5;

        int refused = 0;
        int throttled = 0;
        for (int i = 0; i < total; i++) {
            int status = post("numen_guess" + i, INIT).statusCode();
            if (status == 401) refused++;
            if (status == 429) throttled++;
        }

        assertEquals(McpStrangers.FAILURES_PER_WINDOW, refused);
        assertEquals(5, throttled, "窗里失败满了,之后的不再查令牌就拒绝");
        assertEquals(200, post(secret, INIT).statusCode(), "被限期间对的令牌照常通过");
    }

    // ---- 谁连着,收干净 ----

    @Test
    void aBrainCountsAsConnectedWhileItIsHereAndNotAfterItLeaves() {
        McpTokens.Issued issued = brain(McpToken.Scope.of(java.util.Set.of(ALICE)));
        assertFalse(presence.drives(OWNER, ALICE));

        McpSyncClient client = connect(issued.secret());

        assertTrue(presence.brainConnected(OWNER));
        assertTrue(presence.drives(OWNER, ALICE));
        assertFalse(presence.drives(OWNER, BOB), "令牌管不着的对象驾驶席不动");
        assertFalse(presence.drives(UUID.randomUUID(), ALICE), "别的主人的令牌不算");

        client.closeGracefully();   // DELETE 会话
        for (int i = 0; i < 100 && presence.brainConnected(OWNER); i++) {
            sleep(20);
        }
        assertFalse(presence.brainConnected(OWNER));
    }

    @Test
    void aSilentBrainStopsCountingAsConnectedAndARevokedTokenEndsAtOnce() {
        McpTokens.Issued issued = brain(McpToken.Scope.ALL);
        connect(issued.secret());
        assertTrue(presence.brainConnected(OWNER));

        now.addAndGet(McpPresence.IDLE_MS + 1);
        assertFalse(presence.brainConnected(OWNER), "安静太久不再算连着");

        now.addAndGet(-(McpPresence.IDLE_MS + 1));
        assertTrue(presence.brainConnected(OWNER));
        assertTrue(tokens.revoke(OWNER, issued.token().id()));
        presence.sweep(tokens::valid);
        endpoint.sweep();
        assertFalse(presence.brainConnected(OWNER), "令牌作废,随即断开");
    }

    @Test
    void stoppingTheEndpointLeavesNoServerThreads() throws Exception {
        connect(brain(McpToken.Scope.ALL).secret()).listTools();

        endpoint.stop();

        for (int i = 0; i < 100 && serverThread() != null; i++) {
            sleep(50);
        }
        assertNull(serverThread(), "停服后 Tomcat 的线程收掉了");
        assertNotNull(host);
    }

    private static Thread serverThread() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && (t.getName().startsWith("http-nio-") || t.getName().contains("Catalina")))
                .findFirst().orElse(null);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
