package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.mcp.server.McpMode;
import com.dwinovo.numen.mcp.McpJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.modelcontextprotocol.client.McpClient;
import net.minecraft.gametest.framework.GameTestHelper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一个 MCP 客户端:官方 SDK 的客户端,进程内 HTTP,带令牌接入。用例里扮外部大脑(或渠道)。和真客户端一样接入后先列一次工具
 * (端点据此登记这张令牌看得到的工具),征询经 SDK 的回调答。
 */
final class McpTestClient {

    /** 在别的线程上还没做完的接入:测试服每刻等它们做完({@link #busy}),接入总在发起的那一刻收尾落定。 */
    private static final java.util.concurrent.atomic.AtomicInteger CONNECTING = new java.util.concurrent.atomic.AtomicInteger();
    /** 还在跑的外部大脑脚本({@link #drive}):每一条要么在算、要么停在一次等游戏的请求里。 */
    private static final java.util.concurrent.atomic.AtomicInteger DRIVING = new java.util.concurrent.atomic.AtomicInteger();

    private final int port;
    private final String secret;
    private volatile McpSyncClient client;
    private volatile Throwable failure;
    /** 端点向这个客户端弹的征询(表单请求),按到达顺序。 */
    final List<McpSchema.ElicitFormRequest> asked = new CopyOnWriteArrayList<>();

    McpTestClient(int port, String secret) {
        this.port = port;
        this.secret = secret;
    }

    private McpClient.SyncSpec spec() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port).jsonMapper(McpJson.MAPPER).endpoint("/mcp").openConnectionOnStartup(true)
                .addHttpRequestCustomizer((builder, method, uri, body, context) ->
                        builder.header("Authorization", "Bearer " + secret))
                .build();
        return McpClient.sync(transport).jsonSchemaValidator(McpJson.SCHEMA_VALIDATOR).requestTimeout(Duration.ofSeconds(60))
                .clientInfo(new McpSchema.Implementation("gametest-client", "1"));
    }

    /** 接入:initialize,再列一次工具。列工具要服务端主线程办事,所以只能在别的线程上调;主线程上用 {@link #connectLater}。 */
    void connect() {
        connect(spec());
    }

    /** 接入,并声明自己会答 elicitation:{@code decision} 是人在弹窗里选的那一项的值。 */
    void connect(String decision) {
        connect(spec().capabilities(McpSchema.ClientCapabilities.builder()
                        .elicitation(McpSchema.ClientCapabilities.Elicitation.builder()
                                .form(new McpSchema.ClientCapabilities.Elicitation.Form()).build()).build())
                .elicitation(request -> {
                    asked.add(request);
                    return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT,
                            Map.of("decision", decision), null);
                }));
    }

    private void connect(McpClient.SyncSpec spec) {
        McpSyncClient opened = spec.build();
        opened.initialize();
        opened.listTools();
        client = opened;
    }

    /** 在另一条线程上接入(用例的主线程就是服务端主线程,不能被堵住);用 {@link #afterConnected} 等它做完。 */
    void connectLater() {
        later(this::connect);
    }

    /** 同 {@link #connectLater},并声明自己会答 elicitation。 */
    void connectLater(String decision) {
        later(() -> connect(decision));
    }

    private void later(Runnable connecting) {
        CONNECTING.incrementAndGet();
        Thread handshake = new Thread(() -> {
            try {
                connecting.run();
            } catch (Throwable t) {
                failure = t;
            } finally {
                CONNECTING.decrementAndGet();
            }
        }, "gametest-mcp-connect");
        handshake.setDaemon(true);
        handshake.start();
    }

    /**
     * 扮外部大脑(或渠道桥)的一段脚本,在它自己的线程上跑,和产品里一样一次次阻塞着等回执。测试服每刻等到每一段脚本都停在一次
     * 等游戏的请求里(端点说它在等游戏、等的还没到,{@link McpMode#waitingOnGame})或跑完:脚本自己在算、请求在路上或端点在算,
     * 这一刻都不往下走,请求总在同一刻发出、同一刻收到回执。脚本里出的错交给 {@code failed}。
     */
    static Thread drive(String name, ThrowingRunnable script, java.util.function.Consumer<Throwable> failed) {
        DRIVING.incrementAndGet();
        Thread thread = new Thread(() -> {
            try {
                script.run();
            } catch (Throwable t) {
                failed.accept(t);
            } finally {
                DRIVING.decrementAndGet();
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** {@link #drive} 的脚本:可以抛出受检异常。 */
    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * 有没有还没落定的:接入没做完、有脚本没停在等游戏的请求里、端点弹给人的问题还没交回;有就是一句说明,没有是 {@code null}。
     * 登记进测试服每刻的等待。端点整个服务器只有一份,用它的用例各自成批,所以脚本数与端点上等游戏的请求数可以直接比。
     */
    static String busy() {
        int connecting = CONNECTING.get();
        if (connecting > 0) {
            return connecting + " gametest mcp client(s) still connecting";
        }
        McpMode mcp = McpMode.instance();
        int asking = mcp.asking();
        if (asking > 0) {
            return asking + " question(s) out at a gametest mcp client";
        }
        int driving = DRIVING.get();
        int parked = mcp.waitingOnGame();
        return driving > parked ? (driving - parked) + " gametest mcp script(s) not waiting on the game" : null;
    }

    /** 接入做完(或失败)后再过 {@code delay} 刻,在主线程上跑 {@code next}。 */
    void afterConnected(GameTestHelper helper, int delay, Runnable next) {
        if (failure != null) {
            throw new IllegalStateException("the client could not connect", failure);
        }
        if (client == null) {
            helper.runAfterDelay(1, () -> afterConnected(helper, delay, next));
        } else {
            helper.runAfterDelay(delay, next);
        }
    }

    /** 一次 tools/call,返回结果:{@code content} 是文本块数组,{@code isError} 是成败。 */
    JsonObject callResult(String tool, JsonObject args) {
        McpSchema.CallToolRequest.Builder request = McpSchema.CallToolRequest.builder().name(tool);
        request.arguments(new com.google.gson.Gson().fromJson(args, Map.class));
        McpSchema.CallToolResult result = client.callTool(request.build());
        JsonArray content = new JsonArray();
        for (McpSchema.Content block : result.content()) {
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", ((McpSchema.TextContent) block).text());
            content.add(text);
        }
        JsonObject out = new JsonObject();
        out.add("content", content);
        out.addProperty("isError", Boolean.TRUE.equals(result.isError()));
        return out;
    }

    /** 一次 tools/call,返回回执的第一段文本。 */
    String call(String tool, JsonObject args) {
        return callResult(tool, args).getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    /** 结束会话。 */
    void disconnect() {
        client.closeGracefully();
    }

    /** 用例收场:不等服务端,当场关掉;还在等回执的调用随之失败,扮外部大脑的脚本跟着跑完({@link #drive})。 */
    void close() {
        McpSyncClient opened = client;
        if (opened != null) {
            opened.close();
        }
    }
}
