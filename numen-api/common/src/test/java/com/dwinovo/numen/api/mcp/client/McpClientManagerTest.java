package com.dwinovo.numen.api.mcp.client;

import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.library.Library;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接进来的 MCP 服务器的连接,跟着库走:给定"该连着哪几台",实际的和它一致——新的记上、没了的撤掉、改了的换一份、
 * 不连的({@code enabled=false})不起连接。这里只验记账;真连一台服务器要网络。
 */
class McpClientManagerTest {

    @TempDir
    Path dir;

    private final List<Integer> changes = new java.util.ArrayList<>();

    private McpClientManager manager() {
        return new McpClientManager(Library.Group.BUILTIN, Runnable::run, () -> changes.add(1), dir,
                new McpClientManager.Tools() {
                    @Override
                    public void register(NumenTool tool) {
                    }

                    @Override
                    public void remove(String name) {
                    }
                }, service -> null);
    }

    private static McpClientConfig.ServerSpec http(String name, String url, boolean enabled) {
        return new McpClientConfig.ServerSpec(name, "http", url, Map.of(), "", List.of(), Map.of(), "", enabled, 1, 1);
    }

    @Test
    void reconcilingRecordsEachServerInTheLibrariesOrderAndAServerSwitchedOffIsNotConnected() {
        McpClientManager manager = manager();
        Map<String, McpClientConfig.ServerSpec> desired = new LinkedHashMap<>();
        desired.put("server:b", http("b", "http://x/b", false));
        desired.put("server:a", http("a", "http://x/a", false));

        manager.reconcile(desired);

        assertEquals(List.of("server:b", "server:a"), manager.servers().stream().map(McpClientManager.ServerHandle::id).toList());
        assertEquals(McpClientManager.Status.DISABLED, manager.handle("server:a").status());
        assertEquals("a", manager.spec("server:a").name());
        assertTrue(changes.size() >= 1, "状态变了通知看的人");
        manager.shutdown();
    }

    @Test
    void aServerThatIsNoLongerWantedIsDroppedAndAChangedOneGetsAFreshHandle() {
        McpClientManager manager = manager();
        Map<String, McpClientConfig.ServerSpec> desired = new LinkedHashMap<>();
        desired.put("server:a", http("a", "http://x/a", false));
        desired.put("server:b", http("b", "http://x/b", false));
        manager.reconcile(desired);
        McpClientManager.ServerHandle untouched = manager.handle("server:a");
        McpClientManager.ServerHandle changed = manager.handle("server:b");

        desired.remove("server:a");
        desired.put("server:b", http("b", "http://elsewhere/b", false));
        desired.put("server:c", http("c", "http://x/c", false));
        manager.reconcile(desired);

        assertNull(manager.handle("server:a"));
        assertNotSame(changed, manager.handle("server:b"), "改了的按新的重来");
        assertEquals("http://elsewhere/b", manager.spec("server:b").url());
        assertEquals(List.of("server:b", "server:c"), manager.servers().stream().map(McpClientManager.ServerHandle::id).toList());

        // 没变的不碰
        manager.reconcile(desired);
        assertSame(manager.handle("server:c"), manager.handle("server:c"));
        assertNotSame(untouched, manager.handle("server:c"));
        manager.shutdown();
    }

    @Test
    void shuttingDownForgetsEveryServer() {
        McpClientManager manager = manager();
        manager.reconcile(Map.of("server:a", http("a", "http://x/a", false)));

        manager.shutdown();

        assertTrue(manager.servers().isEmpty());
    }

    @Test
    void anEntryFileReadsAndWritesBackTheSameServerWithoutItsNameInTheFile() {
        McpClientConfig.ServerSpec stdio = new McpClientConfig.ServerSpec("files", "stdio", "", Map.of(), "npx",
                List.of("-y", "x"), Map.of("K", "v"), "", true, 20, 120);
        McpClientConfig.ServerSpec remote = new McpClientConfig.ServerSpec("docs", "http", "http://x/mcp",
                Map.of("H", "v"), "", List.of(), Map.of(), "docs-service", false, 5, 6);

        for (McpClientConfig.ServerSpec spec : List.of(stdio, remote)) {
            String text = McpClientConfig.write(spec);
            JsonObject json = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            assertTrue(!json.has("name"), "名字是文件名");
            assertEquals(spec, McpClientConfig.read(spec.name(), text));
        }
    }
}
