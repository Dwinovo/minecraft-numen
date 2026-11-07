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

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import net.minecraft.server.MinecraftServer;
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

    /** 断开之前等自己的请求收场,最多等这么久:远大于任何正常的请求,到了还没收场就是卡住了。 */
    private static final long SETTLE_SECONDS = 60;

    private final int port;
    private final String secret;
    /** 这个客户端发出、答复还没回来的 HTTP 请求:它自己调的,和 SDK 替它自动发的(收到工具表变了的通知后补发的 tools/list)。 */
    private final AtomicInteger inFlight = new AtomicInteger();
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
                .clientBuilder(new CountingClient.Builder(inFlight))
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

    /**
     * 结束会话:先等自己发出的请求都收场,再 DELETE。守规矩的客户端就是这样走的;不等的话,结束之后才到服务端的请求得到 404,
     * SDK 客户端会因此另开一个会话,她的驾驶席又被占上。等的请求可能要服务端主线程替它办事(列工具),所以等的时候主线程照常执行
     * 排给它的任务。
     */
    void disconnect(MinecraftServer server) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETTLE_SECONDS);
        server.managedBlock(() -> inFlight.get() == 0 || System.nanoTime() > deadline);
        if (inFlight.get() != 0) {
            throw new IllegalStateException(inFlight.get() + " request(s) of the gametest mcp client did not finish within "
                    + SETTLE_SECONDS + " s");
        }
        client.closeGracefully();
    }

    /** 用例收场:不等服务端,当场关掉;还在等回执的调用随之失败,扮外部大脑的脚本跟着跑完({@link #drive})。 */
    void close() {
        McpSyncClient opened = client;
        if (opened != null) {
            opened.close();
        }
    }

    /**
     * 数在飞请求的 HTTP 客户端:请求交出去加一,答复头回来(或请求失败、被取消)减一。答复头回来时服务端已经收下这个请求、处理到
     * 开始答复;开着的 GET 流也只数到它的答复头。其余原样交给 JDK 的客户端。
     */
    private static final class CountingClient extends HttpClient {
        private final HttpClient delegate;
        private final AtomicInteger inFlight;

        private CountingClient(HttpClient delegate, AtomicInteger inFlight) {
            this.delegate = delegate;
            this.inFlight = inFlight;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return counted(() -> delegate.sendAsync(request, handler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return counted(() -> delegate.sendAsync(request, handler, pushPromiseHandler));
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            inFlight.incrementAndGet();
            try {
                return delegate.send(request, handler);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        private <T> CompletableFuture<T> counted(Supplier<CompletableFuture<T>> send) {
            inFlight.incrementAndGet();
            CompletableFuture<T> sent;
            try {
                sent = send.get();
            } catch (RuntimeException e) {
                inFlight.decrementAndGet();
                throw e;
            }
            sent.whenComplete((response, error) -> inFlight.decrementAndGet());
            return sent;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return delegate.cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return delegate.followRedirects();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return delegate.proxy();
        }

        @Override
        public SSLContext sslContext() {
            return delegate.sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return delegate.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return delegate.authenticator();
        }

        @Override
        public Version version() {
            return delegate.version();
        }

        @Override
        public Optional<Executor> executor() {
            return delegate.executor();
        }

        @Override
        public void shutdownNow() {
            delegate.shutdownNow();
        }

        @Override
        public void close() {
            delegate.close();
        }

        /** SDK 的传输用它造客户端:设置原样交给 JDK 的 builder(默认同 SDK 的 HTTP/1.1),造出来的客户端套上计数。 */
        private static final class Builder implements HttpClient.Builder {
            private final HttpClient.Builder delegate = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1);
            private final AtomicInteger inFlight;

            Builder(AtomicInteger inFlight) {
                this.inFlight = inFlight;
            }

            @Override
            public Builder cookieHandler(CookieHandler cookieHandler) {
                delegate.cookieHandler(cookieHandler);
                return this;
            }

            @Override
            public Builder connectTimeout(Duration duration) {
                delegate.connectTimeout(duration);
                return this;
            }

            @Override
            public Builder sslContext(SSLContext sslContext) {
                delegate.sslContext(sslContext);
                return this;
            }

            @Override
            public Builder sslParameters(SSLParameters sslParameters) {
                delegate.sslParameters(sslParameters);
                return this;
            }

            @Override
            public Builder executor(Executor executor) {
                delegate.executor(executor);
                return this;
            }

            @Override
            public Builder followRedirects(Redirect policy) {
                delegate.followRedirects(policy);
                return this;
            }

            @Override
            public Builder version(Version version) {
                delegate.version(version);
                return this;
            }

            @Override
            public Builder priority(int priority) {
                delegate.priority(priority);
                return this;
            }

            @Override
            public Builder proxy(ProxySelector proxySelector) {
                delegate.proxy(proxySelector);
                return this;
            }

            @Override
            public Builder authenticator(Authenticator authenticator) {
                delegate.authenticator(authenticator);
                return this;
            }

            @Override
            public Builder localAddress(InetAddress localAddr) {
                delegate.localAddress(localAddr);
                return this;
            }

            @Override
            public HttpClient build() {
                return new CountingClient(delegate.build(), inFlight);
            }
        }
    }
}
