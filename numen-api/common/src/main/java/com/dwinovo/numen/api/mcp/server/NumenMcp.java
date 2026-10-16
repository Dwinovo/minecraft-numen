package com.dwinovo.numen.api.mcp.server;

import java.nio.file.Path;

/**
 * 装载「外接大脑」模式:读配置文件、交给 {@link McpMode} 托管,配置里开着就立刻起服。此后的起停由管理员在设置里拨,
 * 不再经过这里。
 *
 * <p>MCP 服务器让外部 agent(Claude Desktop 经 {@code mcp-remote} stdio 桥)列出
 * 主人的同伴、直接调它们的工具——外部 agent 是大脑,同伴是它的手和眼。宿主跑在同伴的大脑所在的那一端,
 * 手({@link ExternalHands})由那一端给。
 */
public final class NumenMcp {

    private NumenMcp() {}

    /** 大脑所在的那一端启动时调一次。 */
    public static void start(Path configFile, ExternalHands hands, McpMode.Notices notices) {
        McpMode.instance().bootstrap(configFile, McpConfig.load(configFile), hands, notices);
    }
}
