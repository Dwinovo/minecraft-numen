package com.dwinovo.numen.api.mcp.client;

import com.dwinovo.numen.api.library.LibraryKind;

/**
 * 接进来的 MCP 服务器是一种库({@link #KIND}):一台服务器一个 JSON 文件——{@code mcp/<命名空间>/<名字>.json},
 * 同伴的大脑能向它借工具。玩家看到"内置"(服务器目录里,管理员配的)与"我的"两组;玩家只能加 HTTP 类的,stdio 类起的是服务器机器上的
 * 进程,只有管理员能加。文件里写的是这台服务器怎么连,格式见 {@link com.dwinovo.numen.mcp.client.McpServerSpec}。
 */
public final class McpClientConfig {

    /** 接进来的 MCP 服务器这种库:一条一个 {@code .json}。 */
    public static final LibraryKind KIND = LibraryKind.files("mcp", ".json");

    private McpClientConfig() {}
}
