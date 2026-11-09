package com.dwinovo.numen.mcp;

import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.mcp.server.McpAudit;
import com.dwinovo.numen.mcp.server.McpConfig;
import com.dwinovo.numen.mcp.server.McpEndpoint;
import com.dwinovo.numen.mcp.server.McpHost;
import com.dwinovo.numen.mcp.server.McpLimits;
import com.dwinovo.numen.mcp.server.McpPresence;
import com.dwinovo.numen.mcp.server.McpStrangers;
import com.dwinovo.numen.mcp.server.McpToken;
import com.dwinovo.numen.mcp.server.McpTokens;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * 给官方一致性测试({@code npx @modelcontextprotocol/conformance server --url ...})用的入口,不是产品代码:起一个真的
 * {@link McpEndpoint},宿主带着一致性场景要的几个工具;端点永远要令牌,而一致性测试的客户端不带头,所以前面再起一个只做转发的
 * 代理,替它加上 {@code Authorization: Bearer}(Host、Origin 原样转发,端点自己的来源检查照常生效)。
 *
 * <p>用法:{@code java -cp <mcp 测试运行类路径> com.dwinovo.numen.mcp.ConformanceServer <代理端口> [令牌输出文件]}。
 * 打印 {@code token=<令牌>} 与 {@code url=<代理地址>} 两行;端点自己的地址另打一行 {@code endpoint=}(给 Inspector 带头直连)。
 */
public final class ConformanceServer {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEAHHgH5QAAAABJRU5ErkJggg==");
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "content-length", "expect", "upgrade",
            "transfer-encoding", "keep-alive", "authorization");

    private ConformanceServer() {}

    public static void main(String[] args) throws Exception {
        // 转发要原样带上 Host(重绑定的检查看它)
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
        int proxyPort = Integer.parseInt(args[0]);
        Io io = Io.direct(e -> { throw e; });
        var dir = Files.createTempDirectory("numen-mcp-conformance");
        McpTokens tokens = new McpTokens(dir, io, System::currentTimeMillis);
        McpAudit audit = new McpAudit(dir, io);
        McpConfig config = new McpConfig(true, McpConfig.LOOPBACK, 0, 30, List.of(),
                new McpConfig.Limits(64, 100_000, 256, 100_000));
        McpEndpoint endpoint = new McpEndpoint(config, tokens, new McpLimits(config.limits(), System::currentTimeMillis), audit,
                new McpStrangers(audit, System::currentTimeMillis), new McpPresence(System::currentTimeMillis), new Host());
        endpoint.start();
        String secret = tokens.issue(UUID.randomUUID(), McpToken.Role.BRAIN, McpToken.Scope.ALL, "conformance", 0).secret();
        URI target = URI.create("http://127.0.0.1:" + endpoint.port() + "/mcp");

        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", proxyPort), 0);
        proxy.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        proxy.createContext("/mcp", exchange -> {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                HttpRequest.Builder request = HttpRequest.newBuilder(target)
                        .method(exchange.getRequestMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body))
                        .header("Authorization", "Bearer " + secret);
                exchange.getRequestHeaders().forEach((name, values) -> {
                    if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                        values.forEach(value -> request.header(name, value));
                    }
                });
                HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                response.headers().map().forEach((name, values) -> {
                    if (!HOP_BY_HOP.contains(name.toLowerCase()) && !name.startsWith(":")) {
                        exchange.getResponseHeaders().put(name, values);
                    }
                });
                boolean empty = response.headers().firstValue("content-length").map("0"::equals).orElse(false)
                        || response.statusCode() == 202 || response.statusCode() == 204;
                exchange.sendResponseHeaders(response.statusCode(), empty ? -1 : 0);
                try (InputStream in = response.body(); OutputStream out = exchange.getResponseBody()) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        out.write(buffer, 0, n);
                        out.flush();
                    }
                }
            } catch (Exception e) {
                System.err.println("proxy: " + e);
            } finally {
                exchange.close();
            }
        });
        proxy.start();
        System.out.println("token=" + secret);
        System.out.println("endpoint=" + target);
        System.out.println("url=http://localhost:" + proxyPort + "/mcp");
        System.out.flush();
        Thread.currentThread().join();
    }

    /** 一致性场景要的工具;别的场景要的(图片、音频、日志、采样、征询……)我们的宿主接口本来就不给,不做。 */
    private static final class Host implements McpHost {
        private static final McpHost.Hints PLAIN = new McpHost.Hints(true, false, true, false);

        @Override
        public Identity identity() {
            return new Identity("numen-conformance", "Numen", "Numen's MCP endpoint under the conformance suite", "0.0.0", PNG);
        }

        @Override
        public String instructions() {
            return "A test host for the MCP conformance suite.";
        }

        @Override
        public List<ToolInfo> tools(McpToken who) {
            return List.of(
                    new ToolInfo("test_simple_text", null, "Returns simple text", "{\"type\":\"object\"}", PLAIN),
                    new ToolInfo("test_error_handling", null, "Always fails", "{\"type\":\"object\"}", PLAIN),
                    new ToolInfo("test_tool_with_progress", null, "Reports progress", "{\"type\":\"object\"}", PLAIN),
                    new ToolInfo("json_schema_2020_12_tool", null, "Tool with JSON Schema 2020-12 features",
                            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\",\"$defs\":"
                                    + "{\"address\":{\"type\":\"object\",\"properties\":{\"street\":{\"type\":\"string\"},"
                                    + "\"city\":{\"type\":\"string\"}}}},\"properties\":{\"name\":{\"type\":\"string\"},"
                                    + "\"address\":{\"$ref\":\"#/$defs/address\"}},\"additionalProperties\":false}", PLAIN));
        }

        @Override
        public Result call(McpToken who, String tool, String argsJson, int timeoutSeconds, Progress progress) throws Exception {
            return switch (tool) {
                case "test_simple_text" -> Result.text("This is a simple text response for testing.", false);
                case "test_error_handling" -> Result.text("This tool intentionally returns an error for testing", true);
                case "test_tool_with_progress" -> {
                    progress.report("0/100");
                    Thread.sleep(50);
                    progress.report("50/100");
                    Thread.sleep(50);
                    progress.report("100/100");
                    yield Result.text("Progress tool finished", false);
                }
                default -> Result.text("called " + tool + " with " + argsJson, false);
            };
        }
    }
}
