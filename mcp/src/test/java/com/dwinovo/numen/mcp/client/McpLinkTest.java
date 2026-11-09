package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.mcp.client.auth.CredentialStore;
import com.dwinovo.numen.mcp.client.auth.McpOAuth;
import com.dwinovo.numen.mcp.protocol.CallToolResult;
import com.dwinovo.numen.mcp.protocol.Content;
import com.dwinovo.numen.mcp.protocol.Tool;
import com.dwinovo.numen.mcp.server.McpAudit;
import com.dwinovo.numen.mcp.server.McpConfig;
import com.dwinovo.numen.mcp.server.McpEndpoint;
import com.dwinovo.numen.mcp.server.McpHost;
import com.dwinovo.numen.mcp.server.McpLimits;
import com.dwinovo.numen.mcp.server.McpPresence;
import com.dwinovo.numen.mcp.server.McpStrangers;
import com.dwinovo.numen.mcp.server.McpToken;
import com.dwinovo.numen.mcp.server.McpTokens;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 到一台服务器的连接:HTTP 的对着我们自己的端点(两头都是这个模块),stdio 的对着一个子进程里的 echo 服务器。
 * 请求头的优先顺序、工具表、调用的文字、工具表变了的通知、子进程收干净。
 */
class McpLinkTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEAHHgH5QAAAABJRU5ErkJggg==");

    @TempDir
    Path dir;
    private volatile List<McpHost.ToolInfo> hostTools = List.of(tool("echo"));
    private McpEndpoint endpoint;
    private McpTokens tokens;
    private final List<McpLink> links = new CopyOnWriteArrayList<>();

    private static McpHost.ToolInfo tool(String name) {
        return new McpHost.ToolInfo(name, null, "says " + name, "{\"type\":\"object\"}",
                new McpHost.Hints(true, false, true, false));
    }

    @BeforeEach
    void start() throws Exception {
        Io io = Io.direct(e -> { throw e; });
        tokens = new McpTokens(dir, io, System::currentTimeMillis);
        McpAudit audit = new McpAudit(dir, io);
        McpConfig config = new McpConfig(true, McpConfig.LOOPBACK, 0, 30, List.of(), McpConfig.Limits.DEFAULT);
        McpHost host = new McpHost() {
            @Override
            public Identity identity() {
                return new Identity("host", "Host", "d", "1", PNG);
            }

            @Override
            public String instructions() {
                return "";
            }

            @Override
            public List<ToolInfo> tools(McpToken who) {
                return hostTools;
            }

            @Override
            public Result call(McpToken who, String tool, String argsJson, int timeoutSeconds, Progress progress) {
                return tool.equals("broken") ? Result.text("it broke", true)
                        : new Result(List.of("a:" + tool, "b:" + argsJson), false, null);
            }
        };
        endpoint = new McpEndpoint(config, tokens, new McpLimits(config.limits(), System::currentTimeMillis), audit,
                new McpStrangers(audit, System::currentTimeMillis), new McpPresence(System::currentTimeMillis), host);
        endpoint.start();
    }

    @AfterEach
    void stop() {
        links.forEach(McpLink::close);
        endpoint.stop();
    }

    private McpOAuth oauth() {
        Map<String, String> vault = new HashMap<>();
        return new McpOAuth(new CredentialStore() {
            @Override
            public String get(String key) {
                return vault.get(key);
            }

            @Override
            public void put(String key, String value) {
                vault.put(key, value);
            }

            @Override
            public void remove(String key) {
                vault.remove(key);
            }
        }, System::currentTimeMillis);
    }

    private McpServerSpec http(Map<String, String> headers, String service) {
        return new McpServerSpec("remote", "http", "http://127.0.0.1:" + endpoint.port() + "/mcp", headers, "", List.of(),
                Map.of(), service, "", true, 5, 10);
    }

    private McpLink open(McpServerSpec spec, Supplier<String> serviceKey, List<List<Tool>> changes) throws Exception {
        McpLink link = McpLink.open(spec, serviceKey, oauth(), "9.9.9",
                new McpLink.Hooks(url -> { }, () -> { }, changes::add));
        links.add(link);
        return link;
    }

    private String secret() {
        return tokens.issue(UUID.randomUUID(), McpToken.Role.BRAIN, McpToken.Scope.ALL, "", 0).secret();
    }

    // ---- HTTP ----

    @Test
    void theServiceKeyFromTheKeychainIsTheBearerAndAHeaderInTheEntryBeatsIt() throws Exception {
        String good = secret();
        List<List<Tool>> changes = new CopyOnWriteArrayList<>();

        McpLink viaKey = open(http(Map.of(), "svc"), () -> good, changes);
        assertEquals(List.of("echo"), viaKey.tools().stream().map(Tool::name).toList());

        McpLink viaHeader = open(http(Map.of("Authorization", "Bearer " + good), "svc"), () -> "numen_wrong", changes);
        assertEquals(1, viaHeader.tools().size(), "条目里写的头盖过钥匙串的 key");
    }

    @Test
    void aWrongBearerFailsToConnectAndOpenLeavesNothingBehind() {
        assertThrows(Exception.class,
                () -> open(http(Map.of(), "svc"), () -> "numen_wrong", new CopyOnWriteArrayList<>()));
    }

    @Test
    void aCallIsRenderedAsOneTextAndAnErrorResultIsAnError() throws Exception {
        hostTools = List.of(tool("echo"), tool("broken"));
        String key = secret();
        McpLink link = open(http(Map.of(), "svc"), () -> key, new CopyOnWriteArrayList<>());
        link.tools();
        JsonObject x = new JsonObject();
        x.addProperty("x", 1);

        McpCaller.Reply ok = link.call("echo", x);
        McpCaller.Reply broken = link.call("broken", new JsonObject());

        assertEquals("a:echo\nb:{\"x\":1}", ok.text(), "整数参数原样送到,不变成 1.0");
        assertFalse(ok.error());
        assertEquals("it broke", broken.text());
        assertTrue(broken.error());
    }

    @Test
    void theClientIsToldWhenTheServersToolsChange() throws Exception {
        String key = secret();
        List<List<Tool>> changes = new CopyOnWriteArrayList<>();
        McpLink watcher = open(http(Map.of(), "svc"), () -> key, changes);
        watcher.tools();

        hostTools = List.of(tool("echo"), tool("more"));
        open(http(Map.of(), "svc"), () -> key, new CopyOnWriteArrayList<>()).tools();   // 另一个客户端再列一次,端点对上变化

        List<String> latest = List.of();
        for (int i = 0; i < 100 && !latest.equals(List.of("echo", "more")); i++) {
            Thread.sleep(50);
            latest = changes.isEmpty() ? List.of() : changes.get(changes.size() - 1).stream().map(Tool::name).toList();
        }
        assertEquals(List.of("echo", "more"), latest, "对方说工具表变了,重列的全量交给宿主");
    }

    // ---- 渲染 ----

    @Test
    void everyKindOfContentBlockBecomesAReadableLine() {
        CallToolResult result = new CallToolResult(List.of(
                new Content.Text("hello"),
                new Content.Resource("file:///a", "text/plain", "contents", null),
                new Content.Resource("file:///b", "image/png", null, "AAAA"),
                new Content.Image("AAAA", "image/png"),
                new Content.Audio("AAAA", "audio/wav"),
                new Content.ResourceLink("file:///c", "c", null)), null, false);

        assertEquals("hello\ncontents\n[resource file:///b image/png]\n[image image/png]\n[audio audio/wav]\n[resource file:///c]",
                McpLink.render(result).text());
    }

    @Test
    void anEmptyResultSaysSoAndStructuredContentIsHandedBackVerbatim() {
        assertEquals("(no content)", McpLink.render(new CallToolResult(List.of(), null, false)).text());
        JsonObject structured = new JsonObject();
        structured.addProperty("a", 1);
        assertEquals("{\"a\":1}", McpLink.render(new CallToolResult(List.of(), structured, false)).text());
    }

    // ---- stdio ----

    @Test
    void aStdioServerRunsAsAChildProcessAndIsGoneWhenTheLinkClosesAndTheLauncherGetsWrappedOnlyWhenNeeded() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        McpServerSpec spec = new McpServerSpec("echo", "stdio", "", Map.of(), java,
                List.of("-cp", System.getProperty("java.class.path"), StdioEcho.class.getName()), Map.of(), "", "", true,
                20, 20);

        java.util.Set<Long> before = children();
        McpLink link = open(spec, () -> null, new CopyOnWriteArrayList<>());
        assertEquals(List.of("echo"), link.tools().stream().map(Tool::name).toList());
        JsonObject word = new JsonObject();
        word.addProperty("word", "hi");
        assertEquals("echo:hi", link.call("echo", word).text());
        java.util.Set<Long> started = new java.util.HashSet<>(children());
        started.removeAll(before);
        assertFalse(started.isEmpty(), "子进程在跑");

        link.close();

        for (int i = 0; i < 100 && started.stream().anyMatch(McpLinkTest::alive); i++) {
            Thread.sleep(50);
        }
        assertTrue(started.stream().noneMatch(McpLinkTest::alive), "关了连接,子进程跟着收掉");
        assertEquals(List.of(java), McpLink.stdioCommand(spec).subList(0, 1), "带路径的命令不套 cmd /c");
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static java.util.Set<Long> children() {
        return ProcessHandle.current().children().map(ProcessHandle::pid).collect(java.util.stream.Collectors.toSet());
    }
}
