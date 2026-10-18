package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.mcp.client.McpClient;
import com.dwinovo.numen.api.mcp.client.McpClientConfig;
import com.dwinovo.numen.api.mcp.client.McpTransport;
import com.dwinovo.numen.api.mcp.client.RemoteMcpTool;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一位主人的同伴看得到的工具:全局的加上他自己接进来的 MCP 工具;内置那几台服务器的工具,他关掉了、或在"我的"里放了同名的盖过了,
 * 她就看不到;别的玩家自己接的工具她看不到。
 */
class OwnerToolsTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    @TempDir
    Path dir;

    private final List<String> registered = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() {
        registered.forEach(name -> {
            ToolRegistry.remove(name);
            ToolRegistry.remove(OWNER, name);
            ToolRegistry.remove(OTHER, name);
        });
    }

    private static RemoteMcpTool tool(String server, String entry, Library.Group group, String remoteName) {
        McpTransport silent = new McpTransport() {
            @Override
            public CompletableFuture<JsonObject> request(JsonObject frame, long timeoutMs) {
                return new CompletableFuture<>();
            }

            @Override
            public void notify(JsonObject frame) {
            }

            @Override
            public void close() {
            }
        };
        JsonObject def = new JsonObject();
        def.addProperty("name", remoteName);
        return new RemoteMcpTool(server, entry, group, new McpClient(server, silent), def, 1000, Runnable::run,
                new HashSet<>());
    }

    private Library mcp() {
        return LibraryHub.headless(dir).library(McpClientConfig.KIND, OWNER);
    }

    private NumenTool shared(RemoteMcpTool tool) {
        ToolRegistry.register(tool);
        registered.add(tool.name());
        return tool;
    }

    @Test
    void aBuiltinServersToolsAreSeenByEveryoneUntilHeSwitchesTheServerOff() {
        Library library = mcp();
        library.builtin().write(new EntryId("server", "kfc"), "{\"type\":\"http\",\"url\":\"http://x\"}");
        NumenTool kfc = shared(tool("kfc", "server:kfc", Library.Group.BUILTIN, "menu"));
        OwnerTools tools = new OwnerTools(OWNER, library);

        assertTrue(tools.all().contains(kfc));
        assertEquals(kfc, tools.resolve(kfc.name()));

        library.setEnabled(new EntryId("server", "kfc"), false);

        assertTrue(!tools.all().contains(kfc), "他关掉了");
        assertNull(tools.resolve(kfc.name()), "模型点名也找不到");
        assertTrue(new OwnerTools(OTHER, LibraryHub.headless(dir).library(McpClientConfig.KIND, OTHER)).all().contains(kfc),
                "别的玩家的开关是他们自己的");
    }

    @Test
    void aToolFromHisOwnServerIsHisAloneAndCoversABuiltinOneOfTheSameName() {
        Library library = mcp();
        library.builtin().write(new EntryId("server", "kfc"), "{\"type\":\"http\",\"url\":\"http://x\"}");
        NumenTool builtin = shared(tool("kfc", "server:kfc", Library.Group.BUILTIN, "menu"));
        library.mine().write(new EntryId("server", "kfc"), "{\"type\":\"http\",\"url\":\"http://mine\"}");
        RemoteMcpTool mine = tool("kfc2", "server:kfc", Library.Group.MINE, "menu");
        ToolRegistry.register(OWNER, mine);
        registered.add(mine.name());
        OwnerTools tools = new OwnerTools(OWNER, library);

        assertTrue(tools.all().contains(mine));
        assertTrue(!tools.all().contains(builtin), "我的同名的盖着内置的,内置那台的工具她看不到");
        assertNull(tools.resolve(builtin.name()));
        assertNotNull(tools.resolve(mine.name()));
        assertTrue(!new OwnerTools(OTHER, LibraryHub.headless(dir).library(McpClientConfig.KIND, OTHER)).all().contains(mine),
                "别的玩家看不到他自己接的");
    }

    @Test
    void anOwnedToolMayNotTakeTheNameOfAServerToolOrOfAnotherOfHisOwn() {
        RemoteMcpTool one = tool("kfc", "server:kfc", Library.Group.BUILTIN, "menu");
        shared(one);
        RemoteMcpTool clash = tool("kfc", "my:kfc", Library.Group.MINE, "menu");

        assertThrows(IllegalStateException.class, () -> ToolRegistry.register(OWNER, clash));

        RemoteMcpTool own = tool("docs", "my:docs", Library.Group.MINE, "search");
        ToolRegistry.register(OWNER, own);
        registered.add(own.name());
        assertThrows(IllegalStateException.class, () -> ToolRegistry.register(OWNER, own));
        assertEquals(own, ToolRegistry.resolve(OWNER, own.name()));
        assertNull(ToolRegistry.resolve(OTHER, own.name()));
        assertEquals(own, ToolRegistry.remove(OWNER, own.name()));
        assertNull(ToolRegistry.resolve(OWNER, own.name()));
    }
}
