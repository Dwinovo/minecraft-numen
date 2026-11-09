package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.mcp.McpThreads;
import com.dwinovo.numen.mcp.protocol.Content;
import com.dwinovo.numen.mcp.protocol.Implementation;
import com.dwinovo.numen.mcp.protocol.ProtocolVersion;
import com.dwinovo.numen.mcp.protocol.Tool;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端会话对着一台照剧本答的服务器:版本协商、会话被结束后重新握手、关了就不再开会话、SSE 断了带编号接上、超时撤回、工具表变了的通知。
 */
class McpClientTest {

    /** 照剧本答的服务器:每个请求交给 {@link Script},记下收到的(方法、会话号、版本头、Last-Event-ID)。 */
    private static final class Server implements AutoCloseable {
        final HttpServer http;
        final List<String> seen = new CopyOnWriteArrayList<>();
        volatile Script script;

        Server(Script script) throws IOException {
            this.script = script;
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(McpThreads.perTask("test-server-"));
            http.createContext("/mcp", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonObject message = body.isBlank() ? null : JsonParser.parseString(body).getAsJsonObject();
                String what = exchange.getRequestMethod() + " "
                        + (message != null && message.has("method") ? message.get("method").getAsString() : "-")
                        + " session=" + exchange.getRequestHeaders().getFirst("Mcp-Session-Id")
                        + " version=" + exchange.getRequestHeaders().getFirst("MCP-Protocol-Version")
                        + " last=" + exchange.getRequestHeaders().getFirst("Last-Event-ID");
                seen.add(what);
                try {
                    this.script.answer(exchange, message);
                } finally {
                    exchange.close();
                }
            });
            http.start();
        }

        URI url() {
            return URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/mcp");
        }

        long count(String prefix) {
            return seen.stream().filter(s -> s.startsWith(prefix)).count();
        }

        @Override
        public void close() {
            http.stop(0);
        }
    }

    @FunctionalInterface
    private interface Script {
        void answer(HttpExchange exchange, JsonObject message) throws IOException;
    }

    private static void json(HttpExchange exchange, String body, String... headers) throws IOException {
        for (int i = 0; i < headers.length; i += 2) {
            exchange.getResponseHeaders().set(headers[i], headers[i + 1]);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void status(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private static String id(JsonObject message) {
        return message.get("id").toString();
    }

    private static String initialized(JsonObject message, String version) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id(message) + ",\"result\":{\"protocolVersion\":\"" + version
                + "\",\"capabilities\":{\"tools\":{\"listChanged\":true}},\"serverInfo\":{\"name\":\"fake\",\"version\":\"1\"}}}";
    }

    private static String tools(JsonObject message, String... names) {
        StringBuilder list = new StringBuilder();
        for (String name : names) {
            list.append(list.isEmpty() ? "" : ",").append("{\"name\":\"").append(name).append("\",\"inputSchema\":{\"type\":\"object\"}}");
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id(message) + ",\"result\":{\"tools\":[" + list + "]}}";
    }

    private Server server;
    private McpClient client;

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private McpClient connect(Duration requestTimeout, McpClient.ToolsChanged changed) throws Exception {
        client = McpClient.connect(new HttpClientTransport(server.url(), Duration.ofSeconds(5),
                        HttpClientTransport.Authorization.NONE), new Implementation("test", null, "1", null, List.of()), null,
                changed, requestTimeout, Duration.ofSeconds(10));
        return client;
    }

    // ---- 版本协商 ----

    @Test
    void theClientSpeaksTheVersionTheServerAnswersAndSendsItOnEveryLaterRequest() throws Exception {
        server = new Server((exchange, message) -> {
            switch (exchange.getRequestMethod() + " " + (message == null ? "" : message.get("method").getAsString())) {
                case "POST initialize" -> json(exchange, initialized(message, "2025-06-18"), "Mcp-Session-Id", "s1");
                case "POST tools/list" -> json(exchange, tools(message, "a"));
                case "GET " -> status(exchange, 405);
                default -> status(exchange, 202);
            }
        });
        connect(Duration.ofSeconds(10), () -> { });

        client.listTools();

        assertEquals(ProtocolVersion.V2025_06_18, client.server().protocolVersion());
        assertTrue(server.seen.stream().anyMatch(s -> s.equals("POST tools/list session=s1 version=2025-06-18 last=null")),
                server.seen.toString());
        assertTrue(server.seen.stream().filter(s -> s.startsWith("POST initialize")).allMatch(s -> s.contains("version=null")),
                "握手的时候还没有版本头");
    }

    @Test
    void aServerVersionTheClientDoesNotSpeakEndsTheConnection() throws Exception {
        server = new Server((exchange, message) -> {
            if (message != null && message.get("method").getAsString().equals("initialize")) {
                json(exchange, initialized(message, "2024-11-05"));
            } else {
                status(exchange, 202);
            }
        });

        RpcException refused = assertThrows(RpcException.class, () -> connect(Duration.ofSeconds(10), () -> { }));
        assertTrue(refused.getMessage().contains("2024-11-05"));
    }

    // ---- 会话 ----

    @Test
    void aSessionTheServerEndedIsReopenedAndTheRequestSentAgain() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        server = new Server((exchange, message) -> {
            String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
            switch (exchange.getRequestMethod() + " " + (message == null ? "" : message.get("method").getAsString())) {
                case "POST initialize" -> json(exchange, initialized(message, "2025-11-25"), "Mcp-Session-Id",
                        "s" + opened.incrementAndGet());
                case "POST tools/list" -> {
                    if ("s1".equals(session)) {
                        status(exchange, 404);   // 服务端结束了第一个会话
                    } else {
                        json(exchange, tools(message, "a"));
                    }
                }
                case "GET " -> status(exchange, 405);
                default -> status(exchange, 202);
            }
        });
        connect(Duration.ofSeconds(10), () -> { });

        List<Tool> tools = client.listTools();

        assertEquals(List.of("a"), tools.stream().map(Tool::name).toList());
        assertEquals(2, server.count("POST initialize"), "照规范重新握手开新会话");
        assertTrue(server.seen.contains("POST tools/list session=s2 version=2025-11-25 last=null"), server.seen.toString());
    }

    @Test
    void aClosedClientStaysClosedEvenWhenA404ArrivesAfterwards() throws Exception {
        CountDownLatch listing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server = new Server((exchange, message) -> {
            switch (exchange.getRequestMethod() + " " + (message == null ? "" : message.get("method").getAsString())) {
                case "POST initialize" -> json(exchange, initialized(message, "2025-11-25"), "Mcp-Session-Id", "s1");
                case "POST tools/list" -> {
                    listing.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    status(exchange, 404);   // 客户端已经关了,这个会话也不在了
                }
                case "GET " -> status(exchange, 405);
                default -> status(exchange, 200);
            }
        });
        connect(Duration.ofSeconds(10), () -> { });
        AtomicInteger failed = new AtomicInteger();
        Thread list = McpThreads.start("test-list", () -> {
            try {
                client.listTools();
            } catch (Exception expected) {
                failed.incrementAndGet();
            }
        });
        assertTrue(listing.await(10, TimeUnit.SECONDS));

        client.close();
        release.countDown();
        list.join(10_000);

        assertEquals(1, failed.get(), "在飞的请求随关闭失败");
        assertEquals(1, server.count("POST initialize"), "关了就是关了:关之后到的 404 不让它再开会话");
        assertEquals(1, server.count("DELETE"), "关的时候告诉服务端结束会话");
        assertThrows(IOException.class, () -> client.listTools(), "关了以后的请求一律失败");
    }

    // ---- 流 ----

    @Test
    void anAnswerLostOnABrokenStreamIsPickedUpWithLastEventId() throws Exception {
        server = new Server((exchange, message) -> {
            String last = exchange.getRequestHeaders().getFirst("Last-Event-ID");
            switch (exchange.getRequestMethod() + " " + (message == null ? "" : message.get("method").getAsString())) {
                case "POST initialize" -> json(exchange, initialized(message, "2025-11-25"), "Mcp-Session-Id", "s1");
                case "POST tools/call" -> {
                    // 只发一个带编号与重连间隔的预备事件就断开(服务端为了不长占连接)
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write("id: call-1\nretry: 50\ndata: \n\n".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().close();
                }
                case "GET " -> {
                    if ("call-1".equals(last)) {
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write(("event: message\nid: call-2\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,"
                                + "\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"resumed\"}]}}\n\n")
                                .getBytes(StandardCharsets.UTF_8));
                        exchange.getResponseBody().close();
                    } else {
                        status(exchange, 405);
                    }
                }
                default -> status(exchange, 202);
            }
        });
        connect(Duration.ofSeconds(10), () -> { });

        Content.Text text = (Content.Text) client.callTool("x", new JsonObject()).content().get(0);

        assertEquals("resumed", text.text());
        assertTrue(server.seen.stream().anyMatch(s -> s.startsWith("GET - session=s1") && s.endsWith("last=call-1")),
                server.seen.toString());
    }

    @Test
    void aRequestThatTimesOutIsWithdrawnWithACancellation() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        List<String> cancelledIds = new CopyOnWriteArrayList<>();
        CountDownLatch end = new CountDownLatch(1);
        server = new Server((exchange, message) -> {
            switch (exchange.getRequestMethod() + " " + (message == null ? "" : message.get("method").getAsString())) {
                case "POST initialize" -> json(exchange, initialized(message, "2025-11-25"), "Mcp-Session-Id", "s1");
                case "POST tools/call" -> {
                    try {
                        end.await(10, TimeUnit.SECONDS);   // 一直不答
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    status(exchange, 500);
                }
                case "POST notifications/cancelled" -> {
                    cancelledIds.add(message.getAsJsonObject("params").get("requestId").toString());
                    cancelled.countDown();
                    status(exchange, 202);
                }
                case "GET " -> status(exchange, 405);
                default -> status(exchange, 202);
            }
        });
        connect(Duration.ofMillis(300), () -> { });

        assertThrows(TimeoutException.class, () -> client.callTool("slow", new JsonObject()));

        assertTrue(cancelled.await(10, TimeUnit.SECONDS), "等不及的请求照规范撤回");
        assertEquals(List.of("2"), cancelledIds);
        end.countDown();
    }

    @Test
    void theServerSayingItsToolsChangedOnTheListeningStreamReachesTheClient() throws Exception {
        server = new Server((exchange, message) -> {
            switch (exchange.getRequestMethod() + " " + (message == null ? "" : message.get("method").getAsString())) {
                case "POST initialize" -> json(exchange, initialized(message, "2025-11-25"), "Mcp-Session-Id", "s1");
                case "GET " -> {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":"
                            + "\"notifications/tools/list_changed\"}\n\n").getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    try {
                        Thread.sleep(2000);   // 流开着一会儿
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                default -> status(exchange, 202);
            }
        });
        CountDownLatch changed = new CountDownLatch(1);

        connect(Duration.ofSeconds(10), changed::countDown);

        assertTrue(changed.await(10, TimeUnit.SECONDS));
    }
}
