package com.dwinovo.numen.mcp.protocol;

/** 我们用到的 MCP 方法名,只在这里写一次。 */
public final class McpMethods {

    public static final String INITIALIZE = "initialize";
    public static final String INITIALIZED = "notifications/initialized";
    public static final String PING = "ping";
    public static final String TOOLS_LIST = "tools/list";
    public static final String TOOLS_CALL = "tools/call";
    public static final String TOOLS_LIST_CHANGED = "notifications/tools/list_changed";
    public static final String PROGRESS = "notifications/progress";
    public static final String ELICIT = "elicitation/create";

    private McpMethods() {}
}
