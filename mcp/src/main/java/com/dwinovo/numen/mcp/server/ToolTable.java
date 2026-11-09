package com.dwinovo.numen.mcp.server;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 每张令牌此刻对外列出的工具表:开会话与客户端列工具时向宿主现取一次({@link #refresh}),去掉服主不给大脑看的;和上一次的比,
 * 变了(不论增删几个)就通知一次({@code changed}),这张令牌开着的会话各收一条工具表变了的通知。{@code tools/call} 只认表里有的。
 *
 * <p>线程:任何线程;同一张令牌的现取串行(宿主取一次要去游戏主线程),不同令牌互不等。
 */
final class ToolTable {

    private final McpHost host;
    private final McpConfig config;
    private final Consumer<String> changed;
    private final Map<String, List<McpHost.ToolInfo>> byToken = new ConcurrentHashMap<>();
    private final Map<String, McpToken> owners = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /** @param changed 这张令牌(编号)的工具表变了 */
    ToolTable(McpHost host, McpConfig config, Consumer<String> changed) {
        this.host = host;
        this.config = config;
        this.changed = changed;
    }

    /** 向宿主现取这张令牌的工具表。取不到(宿主停着、主线程长时间不应、宿主自己抛了)如实抛出,不拿旧表搪塞。 */
    List<McpHost.ToolInfo> refresh(McpToken token) throws Exception {
        synchronized (locks.computeIfAbsent(token.id(), id -> new Object())) {
            List<McpHost.ToolInfo> now = host.tools(token).stream()
                    .filter(tool -> token.role() != McpToken.Role.BRAIN || !config.isHidden(tool.name()))
                    .toList();
            owners.put(token.id(), token);
            List<McpHost.ToolInfo> before = byToken.put(token.id(), now);
            if (before != null && !before.equals(now)) {
                changed.accept(token.id());
            }
            return now;
        }
    }

    /** 这张令牌上一次列出的表里有这个工具。 */
    boolean has(McpToken token, String name) {
        return byToken.getOrDefault(token.id(), List.of()).stream().anyMatch(tool -> tool.name().equals(name));
    }

    /** 作废、过期了的令牌的表忘掉。 */
    void sweep(Predicate<McpToken> valid) {
        for (McpToken token : List.copyOf(owners.values())) {
            if (!valid.test(token)) {
                owners.remove(token.id());
                byToken.remove(token.id());
                locks.remove(token.id());
            }
        }
    }
}
