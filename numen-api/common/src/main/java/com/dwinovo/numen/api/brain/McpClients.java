package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.library.Shelf;
import com.dwinovo.numen.api.mcp.client.McpClientConfig;
import com.dwinovo.numen.api.mcp.client.McpClientManager;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 这台服务器上接进来的 MCP 服务器的连接:服务器的一处(内置组,管理员配的,工具人人可见)和每位玩家自己的一处(我的组,工具只有
 * 他的同伴看得到)。连什么由库里的文件定({@link McpClientConfig#KIND}),库变了就 {@link #reconcile} 让连接跟上;
 * 玩家对内置那几台的开关只管他的同伴看不看得到它们的工具({@link OwnerTools}),不动连接。关服时放手。
 *
 * <p>线程:服务端主线程。
 */
public final class McpClients {

    private static McpClients current;

    static {
        ServerLifecycle.onStopped(() -> {
            synchronized (McpClients.class) {
                if (current != null) {
                    current.shutdown();
                }
                current = null;
            }
        });
    }

    private final MinecraftServer server;
    private final LibraryHub hub;
    private final ModelServices models;
    private final McpClientManager shared;
    private final Map<UUID, McpClientManager> owned = new ConcurrentHashMap<>();

    private McpClients(MinecraftServer server) {
        this.server = server;
        this.hub = LibraryHub.of(server);
        this.models = ModelServices.of(server);
        this.shared = new McpClientManager(Library.Group.BUILTIN, server,
                () -> Replies.share(server, null, Libraries.MCP_CLIENT), Homes.of(server).server().dir(),
                new McpClientManager.Tools() {
                    @Override
                    public void register(NumenTool tool) {
                        ToolRegistry.register(tool);
                    }

                    @Override
                    public void remove(String name) {
                        ToolRegistry.remove(name);
                    }
                }, service -> models.serverKeychain().get(service));
    }

    /** 服务器起来时调一次:按内置组连上服务器的那几台。 */
    public static synchronized McpClients start(MinecraftServer server) {
        current = new McpClients(server);
        current.reconcileShared();
        return current;
    }

    public static synchronized McpClients of(MinecraftServer server) {
        if (current == null || current.server != server) {
            throw new IllegalStateException("MCP 连接还没开张——服务器起好时 McpClients.start 该被调用");
        }
        return current;
    }

    /** 服务器的那一处。 */
    public McpClientManager shared() {
        return shared;
    }

    /** 这位玩家自己的那一处;第一次要时按他的"我的"组连上。 */
    public McpClientManager owner(UUID owner) {
        return owned.computeIfAbsent(owner, id -> {
            McpClientManager manager = new McpClientManager(Library.Group.MINE, server,
                    () -> Replies.pushTo(server, id, Libraries.MCP_CLIENT), Homes.of(server).owner(id).dir(),
                    new McpClientManager.Tools() {
                        @Override
                        public void register(NumenTool tool) {
                            ToolRegistry.register(id, tool);
                        }

                        @Override
                        public void remove(String name) {
                            ToolRegistry.remove(id, name);
                        }
                    }, service -> {
                        var found = models.keys(id).find(service);
                        return found == null ? null : found.key();
                    });
            manager.reconcile(specs(hub.library(McpClientConfig.KIND, id).mine()));
            return manager;
        });
    }

    /** 库变了(这位玩家改了他的、或管理员改了内置的):两处的连接都跟上。 */
    public void reconcile(UUID owner) {
        reconcileShared();
        owner(owner).reconcile(specs(hub.library(McpClientConfig.KIND, owner).mine()));
    }

    /** 内置组变了(或 {@code /reload}):服务器那一处跟上,已经开着的玩家那几处也跟上。 */
    public void reconcileAll() {
        reconcileShared();
        owned.forEach((id, manager) -> manager.reconcile(specs(hub.library(McpClientConfig.KIND, id).mine())));
    }

    private void reconcileShared() {
        shared.reconcile(specs(hub.builtin(McpClientConfig.KIND)));
    }

    /** 一个货架里的服务器:库里的名字 → 怎么连。读不通的一条记一笔并跳过。 */
    private static Map<String, McpClientConfig.ServerSpec> specs(Shelf shelf) {
        Map<String, McpClientConfig.ServerSpec> out = new LinkedHashMap<>();
        for (Shelf.Item item : shelf.items()) {
            try {
                out.put(item.id().toString(), McpClientConfig.read(item.id().name(), item.text()));
            } catch (RuntimeException unreadable) {
                Constants.LOG.warn("[numen-mcp-client] {} 读不通,跳过: {}", item.path(), unreadable.toString());
            }
        }
        return out;
    }

    private void shutdown() {
        // 等授权回调的线程一并放掉:浏览器那头可能永远不回来,关服不该被它拖住
        com.dwinovo.numen.api.mcp.client.McpOAuth.cancelAll();
        shared.shutdown();
        owned.values().forEach(McpClientManager::shutdown);
        owned.clear();
    }
}
