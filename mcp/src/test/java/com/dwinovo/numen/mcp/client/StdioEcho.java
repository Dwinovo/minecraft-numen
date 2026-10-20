package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.mcp.McpJson;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

/** 测试用的 stdio MCP 服务器:当子进程起,有一个 echo 工具。{@link McpLinkTest} 经 stdio 连它。 */
public final class StdioEcho {

    private StdioEcho() {}

    public static void main(String[] args) throws Exception {
        McpSchema.Tool echo = McpSchema.Tool.builder("echo", java.util.Map.<String, Object>of("type", "object")).build();
        McpServer.sync(new StdioServerTransportProvider(McpJson.MAPPER))
                .serverInfo("echo", "1")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(McpServerFeatures.SyncToolSpecification.builder().tool(echo)
                        .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                                .content(List.of(new McpSchema.TextContent("echo:" + request.arguments().get("word"))))
                                .build()).build())
                .build();
        Thread.currentThread().join();
    }
}
