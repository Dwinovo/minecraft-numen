package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ToolBook;
import com.dwinovo.numen.api.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.keys.Keychain;
import com.dwinovo.numen.api.keys.KeychainCredentials;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.library.Shelf;
import com.dwinovo.numen.api.mcp.client.McpClientConfig;
import com.dwinovo.numen.api.mcp.client.RemoteMcpTool;
import com.dwinovo.numen.api.mcp.client.RemoteTools;
import com.dwinovo.numen.api.platform.Services;
import com.dwinovo.numen.mcp.client.McpConnections;
import com.dwinovo.numen.mcp.client.McpServerSpec;
import com.dwinovo.numen.mcp.client.auth.McpOAuth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 主人这台电脑上接进来的 MCP 服务器的连接,和她看得到的工具({@link ToolBook}):系统自带的与插件的(全局,{@link ToolRegistry}),
 * 加上主人自己接进来的 MCP 工具,减去他关掉的。连什么由库里的文件定({@link McpClientConfig#KIND}),库变了就 {@link #reconcile}
 * 让连接跟上;他在库里关掉的只管她看不看得到那台的工具,不动连接。连接的钥匙(登录令牌、服务商 key)在主人的钥匙串里,不上服务器。
 *
 * <p>线程:大脑的线程({@code main})。
 */
public final class ClientMcp implements ToolBook {

    private final UUID owner;
    private final Library library;
    private final McpConnections connections;

    /**
     * @param owner    这台电脑上的玩家:工具按他登记
     * @param main     大脑的线程:工具的结果回到这里
     * @param library  接进来的 MCP 服务器的库
     * @param keychain 主人的钥匙串:登录令牌与各服务的 key
     * @param changed  连接的状态变了(连上、断开、等授权)
     */
    public ClientMcp(UUID owner, Executor main, Library library, Keychain keychain, Runnable changed) {
        this.owner = owner;
        this.library = library;
        this.connections = new McpConnections(main, changed,
                new McpOAuth(new KeychainCredentials(keychain), System::currentTimeMillis),
                new RemoteTools(Library.Group.SERVER, main, tool -> ToolRegistry.register(owner, tool),
                        name -> ToolRegistry.remove(owner, name)),
                keychain::get, Services.platform().modVersion(Constants.MOD_ID));
    }

    /** 连接本身:库页读它的状态、授权回调交给它。 */
    public McpConnections connections() {
        return connections;
    }

    /** 库变了:连接跟上。 */
    public void reconcile() {
        connections.reconcile(specs(library.server()));
    }

    /** 放手:连接都断开。 */
    public void shutdown() {
        // 等授权回调的线程一并放掉:浏览器那头可能永远不回来,退出不该被它拖住
        McpOAuth.cancelAll();
        connections.shutdown();
    }

    @Override
    public List<NumenTool> all() {
        return ToolRegistry.all(owner).stream().filter(this::visible).toList();
    }

    @Override
    public NumenTool resolve(String name) {
        NumenTool tool = ToolRegistry.resolve(owner, name);
        return tool != null && visible(tool) ? tool : null;
    }

    private boolean visible(NumenTool tool) {
        if (!(tool instanceof RemoteMcpTool remote)) {
            return true;
        }
        Library.Entry entry = library.find(EntryId.parse(remote.entryId()));
        return entry != null && entry.group() == remote.group();
    }

    /** 一个货架里的服务器:库里的名字 → 怎么连。读不通的一条记一笔并跳过。 */
    private static Map<String, McpServerSpec> specs(Shelf shelf) {
        Map<String, McpServerSpec> out = new LinkedHashMap<>();
        for (Shelf.Item item : shelf.items()) {
            try {
                out.put(item.id().toString(), McpServerSpec.read(item.id().name(), item.text()));
            } catch (RuntimeException unreadable) {
                Constants.LOG.warn("[numen-mcp-client] {} cannot be parsed, skipped: {}", item.path(), unreadable.toString());
            }
        }
        return out;
    }
}
