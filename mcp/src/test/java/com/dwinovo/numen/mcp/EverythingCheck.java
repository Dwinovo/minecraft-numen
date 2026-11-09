package com.dwinovo.numen.mcp;

import com.dwinovo.numen.mcp.client.McpLink;
import com.dwinovo.numen.mcp.client.McpServerSpec;
import com.dwinovo.numen.mcp.client.auth.CredentialStore;
import com.dwinovo.numen.mcp.client.auth.McpOAuth;
import com.dwinovo.numen.mcp.protocol.Tool;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 拿官方 everything 服务器({@code @modelcontextprotocol/server-everything})验我们的客户端的入口,不是产品代码:走产品里接一台
 * 服务器的同一条路({@link McpLink}),列工具、调几样(文本、各种内容块、结构化结果),让它发一次工具表变了、等重列的全量回来。
 *
 * <p>用法:{@code ... EverythingCheck http <url>} 或 {@code ... EverythingCheck stdio <命令> <参数...>}。哪一步不对以非零退出。
 */
public final class EverythingCheck {

    private EverythingCheck() {}

    public static void main(String[] args) throws Exception {
        McpServerSpec spec = args[0].equals("http")
                ? new McpServerSpec("everything", "http", args[1], Map.of(), "", List.of(), Map.of(), "", "", true, 20, 60)
                : new McpServerSpec("everything", "stdio", "", Map.of(), args[1], Arrays.asList(args).subList(2, args.length),
                Map.of(), "", "", true, 30, 60);
        CountDownLatch changed = new CountDownLatch(1);
        McpLink link = McpLink.open(spec, () -> null, new McpOAuth(new CredentialStore() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public void put(String key, String value) {
            }

            @Override
            public void remove(String key) {
            }
        }, System::currentTimeMillis), "0.0.0", new McpLink.Hooks(url -> { }, () -> { }, tools -> {
            System.out.println("list_changed -> relisted " + tools.size() + " tools");
            changed.countDown();
        }));
        try {
            List<Tool> tools = link.tools();
            System.out.println("tools/list -> " + tools.size() + " tools: " + tools.stream().map(Tool::name).toList());
            System.out.println("echo -> " + link.call("echo", args("message", "hello numen")));
            JsonObject sum = new JsonObject();
            sum.addProperty("a", 2);
            sum.addProperty("b", 3);
            System.out.println("get-sum -> " + link.call("get-sum", sum));
            System.out.println("get-tiny-image -> " + link.call("get-tiny-image", new JsonObject()));
            System.out.println("get-resource-links -> " + link.call("get-resource-links", args("count", 2)));
            System.out.println("get-structured-content -> " + link.call("get-structured-content", args("location", "New York")));
            System.out.println("trigger-list-changed -> " + link.call("trigger-list-changed", args("list", "tools")));
            if (!changed.await(15, TimeUnit.SECONDS)) {
                System.out.println("FAILED: no tools/list_changed arrived");
                System.exit(2);
            }
            System.out.println("OK");
        } finally {
            link.close();
        }
    }

    private static JsonObject args(String key, Object value) {
        JsonObject o = new JsonObject();
        if (value instanceof Number n) {
            o.addProperty(key, n);
        } else {
            o.addProperty(key, String.valueOf(value));
        }
        return o;
    }
}
