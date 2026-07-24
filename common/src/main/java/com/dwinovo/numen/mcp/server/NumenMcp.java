package com.dwinovo.numen.mcp.server;

import java.nio.file.Path;

/**
 * Loader-agnostic core — both entry points call {@link #initClient} once during
 * client init.
 *
 * <p>装载「外接大脑」模式:读配置文件、交给 {@link McpMode} 托管,配置里开着就
 * 立刻起服。此后的起停由玩家在设置面板里拨,不再经过这里。
 *
 * <p>MCP 服务器让外部 agent(Claude Desktop 经 {@code mcp-remote} stdio 桥)列出
 * 主人的同伴、直接调它们的工具——外部 agent 是大脑,同伴是它的手和眼。
 * Client-only:同伴与工具注册表都活在主人的游戏客户端里。
 */
public final class NumenMcp {

    private NumenMcp() {}

    public static void initClient(Path configDir) {
        Path file = configDir.resolve("numen").resolve("mcp_server.json");
        McpMode.instance().bootstrap(file, McpConfig.load(file));
    }
}
