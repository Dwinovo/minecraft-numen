package com.dwinovo.numen.api.mcp.client;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.mcp.client.McpConnections;
import com.dwinovo.numen.mcp.client.McpLink;
import com.dwinovo.numen.mcp.protocol.Tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 接进来的服务器的工具登记到哪:包成 {@link RemoteMcpTool},登记进 {@link com.dwinovo.numen.api.agent.tool.ToolRegistry}
 * (进哪个名下由构造时给的两个口决定)。
 *
 * <p>名字由 {@code McpToolName} 统一铸出来:同一批共用一个已用名集合,洗过之后撞车的(例如 {@code my.server} 与
 * {@code my_server})在这里就分开了,不留到注册表去抛异常。
 */
public final class RemoteTools implements McpConnections.Registry {

    private final Executor main;
    private final Consumer<NumenTool> register;
    private final Consumer<String> remove;

    /**
     * @param main     大脑所在的线程:工具的结果回到这里
     * @param register 把一个工具登记进注册表(重名抛异常)
     * @param remove   按名字把一个工具撤出注册表
     */
    public RemoteTools(Executor main, Consumer<NumenTool> register, Consumer<String> remove) {
        this.main = main;
        this.register = register;
        this.remove = remove;
    }

    /**
     * 逐个登记,返回真的进了注册表的那些名字。一个工具进不去不该带走整台服务器的其余工具——工具清单每轮都要发,
     * 少一个是少一个能力,全没了是这台服务器白接。
     */
    @Override
    public List<String> register(McpConnections.ServerHandle server, McpLink link, List<Tool> tools) {
        List<String> registered = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (Tool tool : tools) {
            RemoteMcpTool wrapped = new RemoteMcpTool(server.name(), server.id(), link, tool, main, taken);
            try {
                register.accept(wrapped);
                registered.add(wrapped.name());
            } catch (RuntimeException ex) {
                Constants.LOG.warn("[numen-mcp-client] '{}' skips tool {}: {}", server.name(), wrapped.name(),
                        ex.getMessage());
            }
        }
        return registered;
    }

    @Override
    public void remove(List<String> names) {
        names.forEach(remove);
    }
}
