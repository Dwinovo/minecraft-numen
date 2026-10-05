package com.dwinovo.numen.agent.http;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取消令牌对传输层的每一段都生效:发送前、流式读到一半、重试退避的等待里。取消之后不再回调、
 * 不再发请求,连接被关掉,future 以 {@link CancellationException} 结束。
 *
 * <p>对面是本机起的一个 JDK {@link HttpServer},按场景吐 SSE 或 503。
 */
class TransportCancelTest {

    private HttpServer server;
    private ExecutorService serverThreads;
    private String base;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        serverThreads.shutdownNow();
    }

    private static HttpLlmTransport transport() {
        return new HttpLlmTransport(null, Map.of());
    }

    /** future 以什么失败结束(剥掉组合 future 包的那层 CompletionException);5 秒内没结束算失败。 */
    private static Throwable failureOf(CompletableFuture<Void> future) throws Exception {
        Throwable failure = future.handle((v, err) -> err).get(5, TimeUnit.SECONDS);
        assertNotNull(failure, "取消了的请求不该正常结束");
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    @Test
    void cancelledBeforeSendingNeverReachesTheServer() throws Exception {
        server.createContext("/sse", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        CancelToken cancel = new CancelToken();
        cancel.cancel();

        CompletableFuture<Void> future = transport().postSse(base + "/sse", "k", new JsonObject(),
                chunk -> { }, cancel);

        assertInstanceOf(CancellationException.class, failureOf(future));
        Thread.sleep(200);
        assertEquals(0, requests.get(), "取消过的令牌不该发出任何请求");
    }

    @Test
    void cancelMidStreamStopsChunksAndClosesTheConnection() throws Exception {
        CountDownLatch peerClosed = new CountDownLatch(1);
        server.createContext("/sse", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            try {
                // 一直往下写,直到对面关掉连接写不进去为止
                for (int i = 0; i < 400; i++) {
                    out.write(("data: {\"n\":" + i + "}\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(25);
                }
            } catch (IOException closed) {
                peerClosed.countDown();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        CancelToken cancel = new CancelToken();
        List<JsonObject> seen = new CopyOnWriteArrayList<>();

        CompletableFuture<Void> future = transport().postSse(base + "/sse", "k", new JsonObject(), chunk -> {
            seen.add(chunk);
            if (seen.size() == 2) {
                cancel.cancel();
            }
        }, cancel);

        assertInstanceOf(CancellationException.class, failureOf(future));
        int atCancel = seen.size();
        Thread.sleep(300);
        assertEquals(atCancel, seen.size(), "取消之后不该再有 chunk 进处理器");
        assertEquals(2, atCancel);
        assertTrue(peerClosed.await(5, TimeUnit.SECONDS), "取消要关掉连接,服务端写不进去才对");
    }

    @Test
    void cancelDuringRetryBackoffSendsNoFurtherAttempt() throws Exception {
        CountDownLatch firstAnswered = new CountDownLatch(1);
        server.createContext("/sse", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"error\":\"busy\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            firstAnswered.countDown();
        });
        CancelToken cancel = new CancelToken();

        CompletableFuture<Void> future = transport().postSse(base + "/sse", "k", new JsonObject(),
                chunk -> { }, cancel);
        assertTrue(firstAnswered.await(5, TimeUnit.SECONDS));
        // 503 会让传输层退避 375~500ms 后重试;在等待里取消
        Thread.sleep(100);
        cancel.cancel();

        assertInstanceOf(CancellationException.class, failureOf(future));
        Thread.sleep(1200);
        assertEquals(1, requests.get(), "退避期间取消,重试不该再发出去");
    }
}
