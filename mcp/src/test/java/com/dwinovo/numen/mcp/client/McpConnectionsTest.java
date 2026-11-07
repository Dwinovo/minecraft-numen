package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.mcp.client.auth.McpOAuth;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接进来的 MCP 服务器的连接,跟着库走:给定"该连着哪几台",实际的和它一致——新的记上、没了的撤掉、改了的换一份、
 * 不连的({@code enabled=false})不起连接。这里只验记账;真连一台服务器的在 {@code McpLinkTest}。
 */
class McpConnectionsTest {

    private final List<Integer> changes = new ArrayList<>();

    private McpConnections manager() {
        Map<String, String> vault = new HashMap<>();
        McpOAuth oauth = new McpOAuth(new com.dwinovo.numen.mcp.client.auth.CredentialStore() {
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
        return new McpConnections(Runnable::run, () -> changes.add(1), oauth, new McpConnections.Registry() {
            @Override
            public List<String> register(McpConnections.ServerHandle server, McpLink link, List<McpSchema.Tool> tools) {
                return List.of();
            }

            @Override
            public void remove(List<String> names) {
            }
        }, service -> null, "9.9.9");
    }

    private static McpServerSpec http(String name, String url, boolean enabled) {
        return new McpServerSpec(name, "http", url, Map.of(), "", List.of(), Map.of(), "", "", enabled, 1, 1);
    }

    @Test
    void reconcilingRecordsEachServerInTheLibrariesOrderAndAServerSwitchedOffIsNotConnected() {
        McpConnections manager = manager();
        Map<String, McpServerSpec> desired = new LinkedHashMap<>();
        desired.put("server:b", http("b", "http://x/b", false));
        desired.put("server:a", http("a", "http://x/a", false));

        manager.reconcile(desired);

        assertEquals(List.of("server:b", "server:a"), manager.servers().stream().map(McpConnections.ServerHandle::id).toList());
        assertEquals(McpConnections.Status.DISABLED, manager.handle("server:a").status());
        assertEquals("a", manager.spec("server:a").name());
        assertTrue(changes.size() >= 1, "状态变了通知看的人");
        manager.shutdown();
    }

    @Test
    void aServerThatIsNoLongerWantedIsDroppedAndAChangedOneGetsAFreshHandle() {
        McpConnections manager = manager();
        Map<String, McpServerSpec> desired = new LinkedHashMap<>();
        desired.put("server:a", http("a", "http://x/a", false));
        desired.put("server:b", http("b", "http://x/b", false));
        manager.reconcile(desired);
        McpConnections.ServerHandle untouched = manager.handle("server:a");
        McpConnections.ServerHandle changed = manager.handle("server:b");

        desired.remove("server:a");
        desired.put("server:b", http("b", "http://elsewhere/b", false));
        desired.put("server:c", http("c", "http://x/c", false));
        manager.reconcile(desired);

        assertNull(manager.handle("server:a"));
        assertNotSame(changed, manager.handle("server:b"), "改了的按新的重来");
        assertEquals("http://elsewhere/b", manager.spec("server:b").url());
        assertEquals(List.of("server:b", "server:c"), manager.servers().stream().map(McpConnections.ServerHandle::id).toList());

        // 没变的不碰
        McpConnections.ServerHandle c = manager.handle("server:c");
        manager.reconcile(desired);
        assertEquals(c, manager.handle("server:c"));
        assertNotSame(untouched, manager.handle("server:c"));
        manager.shutdown();
    }

    @Test
    void shuttingDownForgetsEveryServer() {
        McpConnections manager = manager();
        manager.reconcile(Map.of("server:a", http("a", "http://x/a", false)));

        manager.shutdown();

        assertTrue(manager.servers().isEmpty());
    }

    @Test
    void anEntryFileReadsAndWritesBackTheSameServerWithoutItsNameInTheFile() {
        McpServerSpec stdio = new McpServerSpec("files", "stdio", "", Map.of(), "npx", List.of("-y", "x"),
                Map.of("K", "v"), "", "", true, 20, 120);
        McpServerSpec remote = new McpServerSpec("docs", "http", "http://x/mcp", Map.of("H", "v"), "", List.of(),
                Map.of(), "docs-service", "my-client", false, 5, 6);

        for (McpServerSpec spec : List.of(stdio, remote)) {
            String text = spec.write();
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            assertTrue(!json.has("name"), "名字是文件名");
            assertEquals(spec, McpServerSpec.read(spec.name(), text));
        }
    }
}
