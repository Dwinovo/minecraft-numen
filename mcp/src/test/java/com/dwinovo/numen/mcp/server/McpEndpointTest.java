package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.mcp.McpThreads;
import com.dwinovo.numen.mcp.client.HttpClientTransport;
import com.dwinovo.numen.mcp.client.McpClient;
import com.dwinovo.numen.mcp.protocol.CallToolResult;
import com.dwinovo.numen.mcp.protocol.Content;
import com.dwinovo.numen.mcp.protocol.ElicitRequest;
import com.dwinovo.numen.mcp.protocol.ElicitResult;
import com.dwinovo.numen.mcp.protocol.Implementation;
import com.dwinovo.numen.mcp.protocol.Tool;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.dwinovo.numen.mcp.rpc.Sse;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.BindException;
import java.net.ServerSocket;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端点起在随机端口上,用我们自己的客户端(以及直接发 HTTP)连上去走一遍:认人、版本协商、会话、工具表(注解、图标、serverInfo)、调用、
 * 进度、撤回、征询与撤回、限流、审计、谁连着、停服收干净。每条在对应的东西坏掉时变红——没带令牌能进、别人的会话能用、超了限还放行、
 * 审计里漏出内容、停服后线程还在,都会有一条挂。
 */
class McpEndpointTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000c002");
    /** 1x1 的 PNG。 */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEAHHgH5QAAAABJRU5ErkJggg==");

    /** 假的宿主:两个工具;lua 报两次进展;"fail" 抛异常;"hold" 停到用例放行;记下每次调用。 */
    private static final class Host implements McpHost {
        volatile List<ToolInfo> tools = List.of(
                new ToolInfo("lua", "Run program", "runs a program", "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"}}}",
                        new Hints(false, true, false, true)),
                new ToolInfo("get_events", "Wait for events", "waits", "{\"type\":\"object\"}",
                        new Hints(true, false, false, false)));
        final List<String> calls = new CopyOnWriteArrayList<>();
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch holding = new CountDownLatch(1);
        /** "hold" 等放行时被打断了。 */
        final AtomicBoolean interrupted = new AtomicBoolean();
        /** 非空时 tools 抛出它:宿主取不到工具表。 */
        volatile String toolsFailure;

        @Override
        public Identity identity() {
            return new Identity("numen-test", "Numen", "A test host", "9.9.9", PNG);
        }

        @Override
        public String instructions() {
            return "drive carefully";
        }

        @Override
        public List<ToolInfo> tools(McpToken who) throws Exception {
            if (toolsFailure != null) {
                throw new java.util.concurrent.TimeoutException(toolsFailure);
            }
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
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    throw e;
                }
                return Result.text("released", false);
            }
            progress.report("step 1");
            progress.report("step 2");
            return new Result(List.of("ran " + tool, "<runtime_state/>"), false, ALICE);
        }
    }

    private static final McpHost.ToolInfo HOLD = new McpHost.ToolInfo("hold", null, "holds", "{\"type\":\"object\"}",
            new McpHost.Hints(false, false, false, false));

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
    private final List<McpClient> clients = new ArrayList<>();

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
        clients.forEach(McpClient::close);
        endpoint.stop();
    }

    private McpTokens.Issued brain(McpToken.Scope scope) {
        return tokens.issue(OWNER, McpToken.Role.BRAIN, scope, "", 0);
    }

    private String url() {
        return "http://127.0.0.1:" + endpoint.port();
    }

    private McpClient connect(String secret) throws Exception {
        return connect(secret, null, () -> { });
    }

    private McpClient connect(String secret, McpClient.Elicitor elicitor, McpClient.ToolsChanged changed) throws Exception {
        HttpClientTransport transport = new HttpClientTransport(URI.create(url() + "/mcp"), Duration.ofSeconds(5),
                new HttpClientTransport.Authorization() {
                    @Override
                    public Map<String, String> headers() {
                        return Map.of("Authorization", "Bearer " + secret);
                    }

                    @Override
                    public boolean remedy(int status, List<String> wwwAuthenticate) {
                        return false;
                    }
                });
        McpClient client = McpClient.connect(transport, new Implementation("test-client", null, "1", null, List.of()),
                elicitor, changed, Duration.ofSeconds(20), Duration.ofSeconds(20));
        clients.add(client);
        return client;
    }

    private static JsonObject args(String code) {
        JsonObject args = new JsonObject();
        args.addProperty("code", code);
        return args;
    }

    private static List<String> texts(CallToolResult result) {
        return result.content().stream().map(c -> ((Content.Text) c).text()).toList();
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

    private static String init(String version, String capabilities) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"" + version
                + "\",\"capabilities\":" + capabilities + ",\"clientInfo\":{\"name\":\"raw\",\"version\":\"1\"}}}";
    }

    private static final String INIT = init("2025-06-18", "{}");
    private static final String INITIALIZED = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

    /** 直接发 HTTP 开一个会话:initialize 加 initialized,返回会话号。 */
    private String rawSession(String secret, String initBody) throws Exception {
        HttpResponse<String> opened = post(secret, initBody);
        assertEquals(200, opened.statusCode(), opened.body());
        String session = opened.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertEquals(202, post(secret, INITIALIZED, "Mcp-Session-Id", session).statusCode());
        return session;
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

        assertEquals(403, post(secret, INIT, "Origin", "http://evil.example").statusCode());
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

    // ---- 起在配置的地址与端口 ----

    @Test
    void theEndpointListensOnTheConfiguredPort() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        launch(new McpConfig(true, McpConfig.LOOPBACK, port, 30, List.of(), McpConfig.Limits.DEFAULT));

        assertEquals(port, endpoint.port());
        assertEquals(200, post(brain(McpToken.Scope.ALL).secret(), INIT).statusCode(), "配置的端口上就是端点");
    }

    @Test
    void aPortInUseIsReportedWithThePortAndWhereToChangeItAndNoOtherPortIsTaken() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 50, java.net.InetAddress.getByName(McpConfig.LOOPBACK))) {
            McpConfig config = new McpConfig(true, McpConfig.LOOPBACK, taken.getLocalPort(), 30, List.of(),
                    McpConfig.Limits.DEFAULT);
            McpEndpoint other = new McpEndpoint(config, tokens, new McpLimits(config.limits(), now::get), audit, strangers,
                    presence, host);

            BindException refused = assertThrows(BindException.class, other::start);

            assertTrue(refused.getMessage().contains(String.valueOf(taken.getLocalPort()))
                    && refused.getMessage().contains("mcp_server.json"), refused.getMessage());
        }
    }

    // ---- 版本协商与会话 ----

    @Test
    void eachSupportedVersionIsAnsweredInKindAndAnUnknownOneGetsTheLatest() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        for (String version : List.of("2025-11-25", "2025-06-18", "2025-03-26")) {
            HttpResponse<String> reply = post(secret, init(version, "{}"));
            assertEquals(200, reply.statusCode());
            assertEquals(version, JsonParser.parseString(reply.body()).getAsJsonObject().getAsJsonObject("result")
                    .get("protocolVersion").getAsString(), "支持的版本照它答");
        }
        HttpResponse<String> unknown = post(secret, init("2024-11-05", "{}"));
        assertEquals("2025-11-25", JsonParser.parseString(unknown.body()).getAsJsonObject().getAsJsonObject("result")
                .get("protocolVersion").getAsString(), "不支持的答我们最新的");
    }

    @Test
    void eachVersionGetsOnlyTheFieldsItKnows() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        JsonObject latest = JsonParser.parseString(post(secret, init("2025-11-25", "{}")).body()).getAsJsonObject()
                .getAsJsonObject("result").getAsJsonObject("serverInfo");
        JsonObject june = JsonParser.parseString(post(secret, init("2025-06-18", "{}")).body()).getAsJsonObject()
                .getAsJsonObject("result").getAsJsonObject("serverInfo");
        JsonObject march = JsonParser.parseString(post(secret, init("2025-03-26", "{}")).body()).getAsJsonObject()
                .getAsJsonObject("result").getAsJsonObject("serverInfo");

        assertTrue(latest.has("title") && latest.has("description") && latest.has("icons"), latest.toString());
        assertTrue(june.has("title") && !june.has("description") && !june.has("icons"), june.toString());
        assertTrue(!march.has("title") && !march.has("icons"), march.toString());
    }

    @Test
    void anUnsupportedProtocolVersionHeaderIsABadRequest() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        String session = rawSession(secret, INIT);
        String list = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";

        assertEquals(400, post(secret, list, "Mcp-Session-Id", session, "MCP-Protocol-Version", "1999-01-01").statusCode());
        assertEquals(200, post(secret, list, "Mcp-Session-Id", session, "MCP-Protocol-Version", "2025-06-18").statusCode());
        assertEquals(200, post(secret, list, "Mcp-Session-Id", session).statusCode(), "没带头的按会话谈定的版本");
        assertEquals(400, post(secret, list).statusCode(), "没带会话号的(握手以外)是坏请求");
    }

    @Test
    void aBatchIsAcceptedOnlyFromA20250326Session() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        String batch = "[{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\"},{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/list\"}]";
        String march = rawSession(secret, init("2025-03-26", "{}"));
        String june = rawSession(secret, init("2025-06-18", "{}"));

        HttpResponse<String> answered = post(secret, batch, "Mcp-Session-Id", march);
        assertEquals(200, answered.statusCode());
        assertTrue(answered.body().contains("\"id\":5") && answered.body().contains("\"id\":6") && answered.body().contains("lua"),
                "一批里的每个请求都答了: " + answered.body());
        assertEquals(400, post(secret, batch, "Mcp-Session-Id", june).statusCode(), "2025-06-18 起不许成批");
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

    @Test
    void aSessionOpensOneListeningStreamOnly() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        String session = rawSession(secret, INIT);
        HttpRequest get = HttpRequest.newBuilder(URI.create(url() + "/mcp")).header("Authorization", "Bearer " + secret)
                .header("Accept", "text/event-stream").header("Mcp-Session-Id", session).GET().build();

        HttpResponse<InputStream> first = http.send(get, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<String> second = http.send(get, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, first.statusCode());
        assertTrue(first.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        assertEquals(409, second.statusCode(), "一条消息只走一条流:第二条 GET 流不开");
        first.body().close();
    }

    @Test
    void aToolListTheHostCannotProduceIsAnErrorWithTheReasonNotAStaleList() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        String session = post(secret, INIT).headers().firstValue("Mcp-Session-Id").orElseThrow();
        host.toolsFailure = "main thread did not answer";

        HttpResponse<String> reply = post(secret, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}",
                "Mcp-Session-Id", session, "MCP-Protocol-Version", "2025-06-18");

        assertEquals(200, reply.statusCode());
        assertTrue(reply.body().contains("\"id\":7") && reply.body().contains("\"error\"")
                && reply.body().contains("cannot be listed right now") && reply.body().contains("main thread did not answer"),
                reply.body());
        assertFalse(reply.body().contains("\"tools\""), "不拿旧表搪塞: " + reply.body());
    }

    // ---- 工具表与自我介绍 ----

    @Test
    void theServerIntroducesItselfAndListsItsToolsWithTheirNatureAndIcons() throws Exception {
        McpClient client = connect(brain(McpToken.Scope.ALL).secret());

        Implementation info = client.server().serverInfo();
        assertEquals("numen-test", info.name());
        assertEquals("Numen", info.title());
        assertEquals("A test host", info.description());
        assertEquals("9.9.9", info.version());
        assertEquals(1, info.icons().size());
        assertTrue(info.icons().get(0).src().startsWith("data:image/png;base64,"));
        assertEquals(List.of("1x1"), info.icons().get(0).sizes());
        assertEquals("drive carefully", client.server().instructions());
        assertTrue(client.server().toolsListChanged(), "声明会发工具表变了的通知");

        List<Tool> tools = client.listTools();
        assertEquals(List.of("lua", "get_events"), tools.stream().map(Tool::name).toList(), "宿主给的顺序原样");
        Tool lua = tools.get(0);
        assertEquals("Run program", lua.title());
        assertEquals(true, lua.annotations().destructiveHint());
        assertEquals(false, lua.annotations().readOnlyHint());
        assertEquals(true, lua.annotations().openWorldHint());
        assertEquals(1, lua.icons().size());
        assertEquals(true, tools.get(1).annotations().readOnlyHint());
    }

    @Test
    void aChannelOnlySeesTheToolsTheHostGivesChannels() throws Exception {
        McpClient client = connect(tokens.issue(OWNER, McpToken.Role.CHANNEL, McpToken.Scope.ALL, "", 0).secret());

        assertEquals(List.of("send"), client.listTools().stream().map(Tool::name).toList());
    }

    @Test
    void toolsTheOwnerHidesAreNotListedToABrainAndCannotBeCalled() throws Exception {
        launch(new McpConfig(true, McpConfig.LOOPBACK, 0, 30, List.of("lua"), McpConfig.Limits.DEFAULT));
        McpClient client = connect(brain(McpToken.Scope.ALL).secret());

        assertEquals(List.of("get_events"), client.listTools().stream().map(Tool::name).toList());
        RpcException refused = assertThrows(RpcException.class, () -> client.callTool("lua", args("x")));
        assertTrue(refused.getMessage().contains("Unknown tool"), refused.getMessage());
        assertTrue(host.calls.isEmpty(), "藏起来的工具调不到宿主");
    }

    @Test
    void listingAgainPicksUpWhatTheHostChanged() throws Exception {
        McpClient client = connect(brain(McpToken.Scope.ALL).secret());
        assertEquals(2, client.listTools().size());

        host.tools = List.of(host.tools.get(0));

        assertEquals(List.of("lua"), client.listTools().stream().map(Tool::name).toList());
    }

    @Test
    void openingASessionAnnouncesNothingAndABatchOfToolChangesIsAnnouncedOnce() throws Exception {
        AtomicInteger announced = new AtomicInteger();
        McpClient client = connect(brain(McpToken.Scope.ALL).secret(), null, announced::incrementAndGet);
        assertEquals(2, client.listTools().size());
        sleep(300);
        assertEquals(0, announced.get(), "会话开的时候工具表已经对好,第一次列表没有变化可通知");

        host.tools = List.of(host.tools.get(0), HOLD);
        assertEquals(List.of("lua", "hold"), client.listTools().stream().map(Tool::name).toList());
        for (int i = 0; i < 100 && announced.get() == 0; i++) {
            sleep(20);
        }
        sleep(300);
        assertEquals(1, announced.get(), "撤一个、加一个是一批变化,只通知一次");
    }

    // ---- 调用、进度与撤回 ----

    @Test
    void aCallReachesTheHostAndTheResultIsEveryTextBlockInOrder() throws Exception {
        McpClient client = connect(brain(McpToken.Scope.ALL).secret());

        CallToolResult result = client.callTool("lua", args("x"));

        assertFalse(result.isError());
        assertEquals(List.of("ran lua", "<runtime_state/>"), texts(result));
        assertEquals(List.of("lua {\"code\":\"x\"}"), host.calls);
    }

    @Test
    void progressComesBackOnTheCallsStreamWhenAskedForAndIncreasesOnly() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        String session = rawSession(secret, INIT);
        String withToken = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"lua\",\"arguments\":"
                + "{\"code\":\"x\"},\"_meta\":{\"progressToken\":\"tok-1\"}}}";
        String without = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"lua\",\"arguments\":{}}}";

        HttpResponse<String> streamed = post(secret, withToken, "Mcp-Session-Id", session);
        HttpResponse<String> plain = post(secret, without, "Mcp-Session-Id", session);

        assertTrue(streamed.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        List<JsonObject> events = events(streamed.body());
        assertEquals(3, events.size(), streamed.body());
        assertEquals(List.of("step 1", "step 2"), events.subList(0, 2).stream()
                .map(e -> e.getAsJsonObject("params").get("message").getAsString()).toList());
        assertTrue(events.get(0).getAsJsonObject("params").get("progress").getAsLong()
                < events.get(1).getAsJsonObject("params").get("progress").getAsLong(), "进度只增不减");
        assertTrue(events.subList(0, 2).stream().allMatch(e -> e.getAsJsonObject("params").get("progressToken").getAsString()
                .equals("tok-1")));
        assertEquals(3, events.get(2).get("id").getAsInt(), "答复在进度之后、同一条流上");
        assertTrue(plain.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
                "不带令牌的那次没有进度,答复就是一个 JSON");
    }

    private static List<JsonObject> events(String body) throws Exception {
        List<JsonObject> out = new ArrayList<>();
        try (Sse.Reader reader = new Sse.Reader(new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))) {
            Sse.Event event;
            while ((event = reader.next()) != null) {
                out.add(JsonParser.parseString(event.data()).getAsJsonObject());
            }
        }
        return out;
    }

    @Test
    void aThrowingHostIsAnErrorTheModelCanReadNotAProtocolFailure() throws Exception {
        host.tools = List.of(new McpHost.ToolInfo("fail", null, "fails", "{\"type\":\"object\"}",
                new McpHost.Hints(false, true, false, true)));
        McpClient client = connect(brain(McpToken.Scope.ALL).secret());

        CallToolResult result = client.callTool("fail", new JsonObject());

        assertTrue(result.isError());
        assertTrue(texts(result).get(0).contains("boom"));
    }

    @Test
    void aCallTheClientCancelsIsInterruptedAndGetsNoAnswer() throws Exception {
        host.tools = List.of(HOLD);
        String secret = brain(McpToken.Scope.ALL).secret();
        String session = rawSession(secret, INIT);
        post(secret, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", "Mcp-Session-Id", session);
        AtomicReference<HttpResponse<String>> held = new AtomicReference<>();
        Thread call = McpThreads.start("test-call", () -> {
            try {
                held.set(post(secret, "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"hold\"}}",
                        "Mcp-Session-Id", session));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(host.holding.await(10, TimeUnit.SECONDS));

        assertEquals(202, post(secret, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":"
                + "{\"requestId\":9,\"reason\":\"user stopped it\"}}", "Mcp-Session-Id", session).statusCode());

        call.join(10_000);
        assertTrue(host.interrupted.get(), "撤回打断了宿主那边的等待");
        assertFalse(held.get().body().contains("\"id\":9"), "撤回的请求照规范不再答复: " + held.get().body());
    }

    // ---- 征询 ----

    private static final McpQuestion QUESTION = new McpQuestion("alice asks (#7)",
            List.of(new McpQuestion.Choice("allow", "Allow once"), new McpQuestion.Choice("remember", "Allow and remember")),
            "Your answer", "Why not");

    /** 会答表单的客户端:记下被问的,选"记住"。 */
    private McpClient eliciting(String secret, List<ElicitRequest> asked) throws Exception {
        return connect(secret, request -> {
            asked.add(request);
            JsonObject content = new JsonObject();
            content.addProperty("decision", "remember");
            content.addProperty("note", "");
            return new ElicitResult(ElicitResult.Action.ACCEPT, content);
        }, () -> { });
    }

    @Test
    void aQuestionIsPutToTheHumanThroughTheClientsStreamAndHisChoiceComesBack() throws Exception {
        List<ElicitRequest> asked = new CopyOnWriteArrayList<>();
        eliciting(brain(McpToken.Scope.ALL).secret(), asked);
        AtomicReference<McpQuestion.Answer> answer = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        endpoint.ask(7, OWNER, ALICE, QUESTION, a -> {
            answer.set(a);
            done.countDown();
        });

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals("remember", answer.get().choice());
        assertTrue(asked.get(0).message().contains("alice asks"));
        String schema = asked.get(0).requestedSchema().toString();
        assertTrue(schema.contains("oneOf") && schema.contains("Allow and remember"), schema);
        assertEquals(0, endpoint.asking(), "答复交出去了就不算在问");
    }

    @Test
    void a20250618ClientIsAskedWithEnumAndEnumNamesAndNoMode() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        String session = rawSession(secret, init("2025-06-18", "{\"elicitation\":{}}"));
        HttpResponse<InputStream> stream = http.send(HttpRequest.newBuilder(URI.create(url() + "/mcp"))
                .header("Authorization", "Bearer " + secret).header("Accept", "text/event-stream")
                .header("Mcp-Session-Id", session).GET().build(), HttpResponse.BodyHandlers.ofInputStream());

        endpoint.ask(7, OWNER, ALICE, QUESTION, a -> { });

        try (Sse.Reader reader = new Sse.Reader(stream.body())) {
            JsonObject request = JsonParser.parseString(reader.next().data()).getAsJsonObject();
            assertEquals("elicitation/create", request.get("method").getAsString());
            JsonObject params = request.getAsJsonObject("params");
            assertFalse(params.has("mode"), "2025-06-18 没有 mode");
            JsonObject decision = params.getAsJsonObject("requestedSchema").getAsJsonObject("properties")
                    .getAsJsonObject("decision");
            assertEquals("[\"allow\",\"remember\"]", decision.get("enum").toString());
            assertEquals("[\"Allow once\",\"Allow and remember\"]", decision.get("enumNames").toString());
            assertFalse(decision.has("oneOf"));
        }
    }

    @Test
    void aQuestionWithNoClientToAskWaitsUntilOneOpensItsStream() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        AtomicReference<McpQuestion.Answer> answer = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        endpoint.ask(7, OWNER, ALICE, QUESTION, a -> {
            answer.set(a);
            done.countDown();
        });
        assertFalse(done.await(300, TimeUnit.MILLISECONDS), "没有能问的会话,先挂着");

        eliciting(secret, new CopyOnWriteArrayList<>());

        assertTrue(done.await(10, TimeUnit.SECONDS), "会答的客户端一开流就问出去了");
        assertEquals("remember", answer.get().choice());
    }

    @Test
    void aWithdrawnQuestionIsNeverAsked() throws Exception {
        List<ElicitRequest> asked = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        endpoint.ask(7, OWNER, ALICE, QUESTION, a -> done.countDown());
        endpoint.withdraw(7);

        eliciting(brain(McpToken.Scope.ALL).secret(), asked);

        assertFalse(done.await(500, TimeUnit.MILLISECONDS));
        assertTrue(asked.isEmpty());
    }

    @Test
    void aQuestionAlreadyAtTheHumanIsWithdrawnWithACancellation() throws Exception {
        CountDownLatch shown = new CountDownLatch(1);
        CountDownLatch dismissed = new CountDownLatch(1);
        connect(brain(McpToken.Scope.ALL).secret(), request -> {
            shown.countDown();
            try {
                new CountDownLatch(1).await(20, TimeUnit.SECONDS);   // 人还没答
            } catch (InterruptedException cancelled) {
                dismissed.countDown();   // 客户端收到撤回,收起弹窗
                throw cancelled;
            }
            return new ElicitResult(ElicitResult.Action.CANCEL, null);
        }, () -> { });
        AtomicInteger answered = new AtomicInteger();
        endpoint.ask(7, OWNER, ALICE, QUESTION, a -> answered.incrementAndGet());
        assertTrue(shown.await(10, TimeUnit.SECONDS));
        assertEquals(1, endpoint.asking());

        endpoint.withdraw(7);

        assertTrue(dismissed.await(10, TimeUnit.SECONDS), "已经弹给人的问题照规范撤回(notifications/cancelled)");
        assertEquals(0, endpoint.asking());
        assertEquals(0, answered.get(), "撤回的问题不再交答复");
    }

    @Test
    void aClientThatCannotElicitIsNeverAsked() throws Exception {
        connect(brain(McpToken.Scope.ALL).secret());
        CountDownLatch done = new CountDownLatch(1);

        endpoint.ask(7, OWNER, ALICE, QUESTION, a -> done.countDown());

        assertFalse(done.await(500, TimeUnit.MILLISECONDS), "不支持就问不了,只能等主人进游戏答");
        assertEquals(0, endpoint.asking());
    }

    @Test
    void onlyTheOwnersOwnBrainCoveringTheSubjectIsAsked() throws Exception {
        List<ElicitRequest> asked = new CopyOnWriteArrayList<>();
        eliciting(brain(McpToken.Scope.of(Set.of(BOB))).secret(), asked);
        CountDownLatch done = new CountDownLatch(1);

        endpoint.ask(7, OWNER, ALICE, QUESTION, a -> done.countDown());
        endpoint.ask(8, UUID.randomUUID(), BOB, QUESTION, a -> done.countDown());

        assertFalse(done.await(500, TimeUnit.MILLISECONDS), "令牌管不着这个对象 / 别的主人的外部大脑不该被问");
        assertTrue(asked.isEmpty());
    }

    // ---- 限流与审计 ----

    @Test
    void callsPastThePerTokenRateAreAnsweredWithAWaitNotRun() throws Exception {
        launch(new McpConfig(true, McpConfig.LOOPBACK, 0, 30, List.of(), new McpConfig.Limits(8, 2, 64, 1200)));
        McpClient client = connect(brain(McpToken.Scope.ALL).secret());   // 接入也占一次

        CallToolResult first = client.callTool("lua", args("x"));
        CallToolResult second = client.callTool("lua", args("x"));

        assertFalse(first.isError());
        assertTrue(second.isError());
        assertTrue(texts(second).get(0).contains("rate limited"));
        now.addAndGet(60_000);
        assertFalse(client.callTool("lua", args("x")).isError(), "一分钟后额度回来");
    }

    @Test
    void theAuditLogNamesWhoCalledWhatAndNothingElse() throws Exception {
        McpTokens.Issued issued = brain(McpToken.Scope.ALL);
        McpClient client = connect(issued.secret());
        client.callTool("lua", args("SECRET-CODE-不该进日志"));

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
    void aBrainCountsAsConnectedWhileItIsHereAndNotAfterItLeaves() throws Exception {
        McpTokens.Issued issued = brain(McpToken.Scope.of(Set.of(ALICE)));
        assertFalse(presence.drives(OWNER, ALICE));

        McpClient client = connect(issued.secret());

        assertTrue(presence.brainConnected(OWNER));
        assertTrue(presence.drives(OWNER, ALICE));
        assertFalse(presence.drives(OWNER, BOB), "令牌管不着的对象驾驶席不动");
        assertFalse(presence.drives(UUID.randomUUID(), ALICE), "别的主人的令牌不算");

        client.close();   // DELETE 会话
        for (int i = 0; i < 100 && presence.brainConnected(OWNER); i++) {
            sleep(20);
        }
        assertFalse(presence.brainConnected(OWNER));
    }

    @Test
    void aRequestOnAnEndedOrUnknownSessionIsAnswered404AndDoesNotBringTheBrainBack() throws Exception {
        String secret = brain(McpToken.Scope.ALL).secret();
        HttpResponse<String> init = post(secret, INIT);
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertTrue(presence.brainConnected(OWNER));

        HttpRequest delete = HttpRequest.newBuilder(URI.create(url() + "/mcp")).header("Authorization", "Bearer " + secret)
                .header("Mcp-Session-Id", session).DELETE().build();
        assertEquals(200, http.send(delete, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertFalse(presence.brainConnected(OWNER));

        String list = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}";
        assertEquals(404, post(secret, list, "Mcp-Session-Id", session).statusCode(), "结束了的会话照规范答 404");
        assertFalse(presence.brainConnected(OWNER), "结束会话之后才到的请求不把驾驶席占回去");
        assertEquals(404, post(secret, list, "Mcp-Session-Id", "no-such-session").statusCode());
        assertFalse(presence.brainConnected(OWNER), "不存在的会话也不算");

        post(secret, INIT);
        assertTrue(presence.brainConnected(OWNER), "重新 initialize 又算连着");
    }

    @Test
    void aSilentBrainStopsCountingAsConnectedAndARevokedTokenEndsAtOnce() throws Exception {
        McpTokens.Issued issued = brain(McpToken.Scope.ALL);
        rawSession(issued.secret(), INIT);
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
        endpoint.stop();
        Set<Thread> before = dispatchers();
        launch(new McpConfig(true, McpConfig.LOOPBACK, 0, 30, List.of(), McpConfig.Limits.DEFAULT));
        McpClient client = connect(brain(McpToken.Scope.ALL).secret());
        client.listTools();
        Set<Thread> started = dispatchers();
        started.removeAll(before);
        assertFalse(started.isEmpty(), "端点的分派线程在跑");
        assertTrue(started.stream().allMatch(Thread::isDaemon), "分派线程是守护线程,停服没到也不拖住 JVM");

        endpoint.stop();

        for (int i = 0; i < 100 && started.stream().anyMatch(Thread::isAlive); i++) {
            sleep(50);
        }
        assertTrue(started.stream().noneMatch(Thread::isAlive), "停服后分派线程收掉了");
    }

    private static Set<Thread> dispatchers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().equals("HTTP-Dispatcher")).collect(Collectors.toSet());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
