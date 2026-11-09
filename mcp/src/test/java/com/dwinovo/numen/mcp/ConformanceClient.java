package com.dwinovo.numen.mcp;

import com.dwinovo.numen.mcp.client.McpLink;
import com.dwinovo.numen.mcp.client.McpServerSpec;
import com.dwinovo.numen.mcp.client.auth.CredentialStore;
import com.dwinovo.numen.mcp.client.auth.McpOAuth;
import com.dwinovo.numen.mcp.protocol.Tool;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 给官方一致性测试的 client 场景({@code npx @modelcontextprotocol/conformance client --command ...})用的入口,不是产品代码:
 * 走产品里接一台服务器的同一条路({@link McpLink},HTTP 传输、OAuth),连上、列工具、调工具、关掉。要登录的场景里,
 * 浏览器那一步由这里代劳:拿到授权网址就去 GET 它、跟着重定向回到本机的回调端点。
 *
 * <p>一致性测试把服务器地址作为最后一个参数传进来,场景名在环境变量 {@code MCP_CONFORMANCE_SCENARIO}。
 */
public final class ConformanceClient {

    private ConformanceClient() {}

    public static void main(String[] args) throws Exception {
        String url = args[args.length - 1];
        Map<String, String> vault = new ConcurrentHashMap<>();
        McpOAuth oauth = new McpOAuth(new CredentialStore() {
            @Override
            public String get(String key) {
                return vault.get(key);
            }

            @Override
            public void put(String key, String value) {
                vault.put(key, value);
            }

            @Override
            public void remove(String key) {
                vault.remove(key);
            }
        }, System::currentTimeMillis);
        HttpClient browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        McpServerSpec spec = new McpServerSpec("conformance", "http", url, Map.of(), "", List.of(), Map.of(), "", "", true, 10, 30);
        McpLink link = McpLink.open(spec, () -> null, oauth, "0.0.0", new McpLink.Hooks(
                authUrl -> McpThreads.start("conformance-browser", () -> {
                    try {
                        browser.send(HttpRequest.newBuilder(URI.create(authUrl)).GET().build(),
                                HttpResponse.BodyHandlers.discarding());
                    } catch (Exception e) {
                        System.err.println("browser: " + e);
                    }
                }),
                () -> { },
                tools -> System.err.println("tools changed: " + tools.size())));
        try {
            List<Tool> tools = link.tools();
            System.err.println("tools: " + tools.stream().map(Tool::name).toList());
            for (Tool tool : tools) {
                JsonObject args1 = new JsonObject();
                if (tool.name().equals("add_numbers")) {
                    args1.addProperty("a", 5);
                    args1.addProperty("b", 3);
                }
                System.err.println(tool.name() + " -> " + link.call(tool.name(), args1));
            }
        } finally {
            link.close();
        }
    }
}
