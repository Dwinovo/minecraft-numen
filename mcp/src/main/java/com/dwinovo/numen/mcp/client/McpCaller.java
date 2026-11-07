package com.dwinovo.numen.mcp.client;

import java.util.Map;

/** 能调对方一个工具的东西:一条连接({@link McpLink}),或测试里的替身。 */
@FunctionalInterface
public interface McpCaller {

    /** 一次工具调用交回的话:给模型看的一段文字,和这次算不算失败。 */
    record Reply(String text, boolean error) {}

    /** 调一次(阻塞到对方答复或到点)。出岔子抛异常。 */
    Reply call(String tool, Map<String, Object> arguments);
}
