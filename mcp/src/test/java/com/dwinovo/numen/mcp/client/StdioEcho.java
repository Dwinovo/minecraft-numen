package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.mcp.rpc.RpcMessage;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 测试用的 stdio MCP 服务器:当子进程起,有一个 echo 工具。{@link McpLinkTest} 经 stdio 连它。照着规范手写的最小一台:一行一条消息,
 * 答 initialize、tools/list、tools/call,别的请求答"没有这个方法",通知不理。
 */
public final class StdioEcho {

    private StdioEcho() {}

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (!(RpcMessage.decode(line) instanceof RpcMessage.Request request)) {
                continue;
            }
            String result = switch (request.method()) {
                case "initialize" -> "{\"protocolVersion\":\"" + request.params().get("protocolVersion").getAsString()
                        + "\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"echo\",\"version\":\"1\"}}";
                case "tools/list" -> "{\"tools\":[{\"name\":\"echo\",\"inputSchema\":{\"type\":\"object\"}}]}";
                case "tools/call" -> "{\"content\":[{\"type\":\"text\",\"text\":\"echo:"
                        + request.params().getAsJsonObject("arguments").get("word").getAsString() + "\"}]}";
                default -> null;
            };
            RpcMessage reply = result == null
                    ? RpcMessage.Response.failed(request.id(), new com.dwinovo.numen.mcp.rpc.RpcError(-32601, "Method not found"))
                    : RpcMessage.Response.ok(request.id(), JsonParser.parseString(result).getAsJsonObject());
            System.out.println(reply.encode());
            System.out.flush();
        }
    }
}
