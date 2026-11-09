package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.mcp.server.McpMode;
import com.dwinovo.numen.mcp.client.HttpClientTransport;
import com.dwinovo.numen.mcp.client.McpClient;
import com.dwinovo.numen.mcp.protocol.CallToolResult;
import com.dwinovo.numen.mcp.protocol.Content;
import com.dwinovo.numen.mcp.protocol.ElicitRequest;
import com.dwinovo.numen.mcp.protocol.ElicitResult;
import com.dwinovo.numen.mcp.protocol.Implementation;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 一个 MCP 客户端:mcp 模块自己的客户端,进程内 HTTP,带令牌接入。用例里扮外部大脑(或渠道)。和真客户端一样接入后先列一次工具
 * (端点据此登记这张令牌看得到的工具),征询经它开的 GET 流到达、由 {@link McpClient.Elicitor} 答。
 */
final class McpTestClient {

    /** 在别的线程上还没做完的接入:测试服每刻等它们做完({@link #busy}),接入总在发起的那一刻收尾落定。 */
    private static final java.util.concurrent.atomic.AtomicInteger CONNECTING = new java.util.concurrent.atomic.AtomicInteger();
    /** 还在跑的外部大脑脚本({@link #drive}):每一条要么在算、要么停在一次等游戏的请求里。 */
    private static final java.util.concurrent.atomic.AtomicInteger DRIVING = new java.util.concurrent.atomic.AtomicInteger();

    /** 断开之前等自己的请求收场,最多等这么久:远大于任何正常的请求,到了还没收场就是卡住了。 */
    private static final long SETTLE_SECONDS = 60;

    private final int port;
    private final String secret;
    private volatile McpClient client;
    private volatile Throwable failure;
    /** 端点向这个客户端弹的征询(表单请求),按到达顺序。 */
    final List<ElicitRequest> asked = new CopyOnWriteArrayList<>();

    McpTestClient(int port, String secret) {
        this.port = port;
        this.secret = secret;
    }

    /** 接入:initialize,再列一次工具。列工具要服务端主线程办事,所以只能在别的线程上调;主线程上用 {@link #connectLater}。 */
    void connect() throws Exception {
        connect((McpClient.Elicitor) null);
    }

    /** 接入,并声明自己会答 elicitation:{@code decision} 是人在弹窗里选的那一项的值。 */
    void connect(String decision) throws Exception {
        connect(request -> {
            asked.add(request);
            JsonObject content = new JsonObject();
            content.addProperty("decision", decision);
            return new ElicitResult(ElicitResult.Action.ACCEPT, content);
        });
    }

    private void connect(McpClient.Elicitor elicitor) throws Exception {
        HttpClientTransport transport = new HttpClientTransport(URI.create("http://127.0.0.1:" + port + "/mcp"),
                Duration.ofSeconds(10), new HttpClientTransport.Authorization() {
                    @Override
                    public Map<String, String> headers() {
                        return Map.of("Authorization", "Bearer " + secret);
                    }

                    @Override
                    public boolean remedy(int status, List<String> wwwAuthenticate) {
                        return false;
                    }
                });
        McpClient opened = McpClient.connect(transport, new Implementation("gametest-client", null, "1", null, List.of()),
                elicitor, () -> { }, Duration.ofSeconds(60), Duration.ofSeconds(60));
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

    private void later(ThrowingRunnable connecting) {
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
    JsonObject callResult(String tool, JsonObject args) throws Exception {
        CallToolResult result = client.callTool(tool, args);
        JsonArray content = new JsonArray();
        for (Content block : result.content()) {
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", ((Content.Text) block).text());
            content.add(text);
        }
        JsonObject out = new JsonObject();
        out.add("content", content);
        out.addProperty("isError", result.isError());
        return out;
    }

    /** 一次 tools/call,返回回执的第一段文本。 */
    String call(String tool, JsonObject args) throws Exception {
        return callResult(tool, args).getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    /**
     * 结束会话:先等自己发出的请求都收场,再 DELETE。守规矩的客户端就是这样走的。等的请求可能要服务端主线程替它办事(列工具),
     * 所以等的时候主线程照常执行排给它的任务。
     */
    void disconnect(MinecraftServer server) {
        McpClient opened = client;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETTLE_SECONDS);
        server.managedBlock(() -> opened.pending() == 0 || System.nanoTime() > deadline);
        if (opened.pending() != 0) {
            throw new IllegalStateException(opened.pending() + " request(s) of the gametest mcp client did not finish within "
                    + SETTLE_SECONDS + " s");
        }
        opened.close();
    }

    /** 用例收场:不等服务端,当场关掉;还在等回执的调用随之失败,扮外部大脑的脚本跟着跑完({@link #drive})。 */
    void close() {
        McpClient opened = client;
        if (opened != null) {
            opened.close();
        }
    }
}
