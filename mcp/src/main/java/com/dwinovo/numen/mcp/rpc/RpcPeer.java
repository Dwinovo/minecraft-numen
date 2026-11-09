package com.dwinovo.numen.mcp.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JSON-RPC 2.0 的一端:服务端的一个会话与客户端的一条连接都是一个 {@code RpcPeer},区别只在挂了哪些处理、消息走哪条路。
 *
 * <ul>
 *   <li><b>发出去的请求</b>({@link #request}):编号由这里发,答复按编号认回来;等不及了可以撤回({@link #cancel}),撤回照 MCP 发
 *       {@code notifications/cancelled}。</li>
 *   <li><b>对方发来的请求</b>({@link #receive}):交给按方法名登记的处理,在构造时给的 {@code executor} 上跑,可以阻塞;答复写回请求来时的
 *       那条路。对方撤回的,跑着的那条线程被打断,不再答复。没登记的方法答 {@link RpcError#METHOD_NOT_FOUND}。</li>
 *   <li><b>对方发来的通知</b>:在收到它的线程上当场处理,处理不许阻塞(stdio 只有一条读线程,堵住它就收不到答复了)。</li>
 * </ul>
 *
 * <p>消息走哪条路由调用方给({@link Outbound}):HTTP 上一个请求的答复与它相关的通知走这个请求自己的流,无关的走 GET 流;stdio 只有一条。
 *
 * <p>线程:任何线程。
 */
public final class RpcPeer {

    private static final Logger LOG = LoggerFactory.getLogger("Numen");

    public static final String CANCELLED = "notifications/cancelled";

    /** 往对方发消息的一条路:一条 HTTP 流、一个子进程的标准输入。 */
    public interface Outbound {

        void send(RpcMessage message) throws IOException;

        /** 这个请求不再有答复(被对方撤回了):等着它答复的这条路可以收场。 */
        default void dropped(JsonPrimitive requestId) {
        }
    }

    /** 对方发来的一个请求的处理:返回 {@code result};以 {@link RpcException} 抛出的是 JSON-RPC 错误。 */
    @FunctionalInterface
    public interface RequestHandler {
        JsonElement handle(Incoming request) throws Exception;
    }

    /** 对方发来的一个通知的处理;在收到它的线程上跑,不许阻塞。 */
    @FunctionalInterface
    public interface NotificationHandler {
        void handle(JsonObject params);
    }

    /** 正在处理的一个对方的请求。 */
    public final class Incoming {
        private final RpcMessage.Request request;
        private final Outbound replyTo;
        private final Thread thread;
        private volatile boolean cancelled;
        /** 处理已经收场(由本对象的锁守着):此后撤回不再打断那条线程——它可能已经在执行器上跑别的任务了。 */
        private boolean finished;

        private Incoming(RpcMessage.Request request, Outbound replyTo, Thread thread) {
            this.request = request;
            this.replyTo = replyTo;
            this.thread = thread;
        }

        public JsonPrimitive id() {
            return request.id();
        }

        public String method() {
            return request.method();
        }

        public JsonObject params() {
            return request.params();
        }

        /** 对方撤回了它:处理线程已被打断,这个请求不再答复。 */
        public boolean cancelled() {
            return cancelled;
        }

        /** 发一条与这个请求相关的通知(进度):走这个请求的答复那条路。 */
        public void notify(String method, JsonObject params) throws IOException {
            replyTo.send(new RpcMessage.Notification(method, params));
        }
    }

    /** 一个发出去、还在等答复的请求。 */
    public record Outgoing(JsonPrimitive id, String method, CompletableFuture<JsonElement> result) {}

    private final Executor executor;
    private final Map<String, RequestHandler> requestHandlers = new ConcurrentHashMap<>();
    private final Map<String, NotificationHandler> notificationHandlers = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong();
    private final Map<JsonPrimitive, Outgoing> pending = new ConcurrentHashMap<>();
    private final Map<JsonPrimitive, Incoming> running = new ConcurrentHashMap<>();

    /** @param executor 对方的请求在哪里处理 */
    public RpcPeer(Executor executor) {
        this.executor = executor;
    }

    public void onRequest(String method, RequestHandler handler) {
        requestHandlers.put(method, handler);
    }

    public void onNotification(String method, NotificationHandler handler) {
        notificationHandlers.put(method, handler);
    }

    // ---- 收 ----

    /**
     * 对方发来一条消息。请求交给 {@code executor} 处理,答复写回 {@code replyTo};通知当场处理;答复交给等它的请求(不认识的编号不理:
     * 多半是已经撤回或等过期的)。
     */
    public void receive(RpcMessage message, Outbound replyTo) {
        switch (message) {
            case RpcMessage.Request request -> executor.execute(() -> run(request, replyTo));
            case RpcMessage.Notification notification -> {
                if (notification.method().equals(CANCELLED)) {
                    cancelled(notification.params());
                } else {
                    NotificationHandler handler = notificationHandlers.get(notification.method());
                    if (handler != null) {
                        handler.handle(notification.params());
                    }
                }
            }
            case RpcMessage.Response response -> {
                Outgoing waiting = response.id() == null ? null : pending.remove(response.id());
                if (waiting == null) {
                    return;
                }
                if (response.error() != null) {
                    waiting.result().completeExceptionally(new RpcException(response.error()));
                } else {
                    waiting.result().complete(response.result());
                }
            }
        }
    }

    private void run(RpcMessage.Request request, Outbound replyTo) {
        Incoming incoming = new Incoming(request, replyTo, Thread.currentThread());
        running.put(request.id(), incoming);
        RpcMessage.Response response;
        try {
            RequestHandler handler = requestHandlers.get(request.method());
            if (handler == null) {
                throw new RpcException(RpcError.METHOD_NOT_FOUND, "Method not found: " + request.method());
            }
            response = RpcMessage.Response.ok(request.id(), handler.handle(incoming));
        } catch (RpcException refused) {
            response = RpcMessage.Response.failed(request.id(), refused.error());
        } catch (InterruptedException interrupted) {
            response = incoming.cancelled ? null
                    : RpcMessage.Response.failed(request.id(), new RpcError(RpcError.INTERNAL_ERROR, "interrupted"));
        } catch (Exception failed) {
            response = incoming.cancelled ? null
                    : RpcMessage.Response.failed(request.id(), new RpcError(RpcError.INTERNAL_ERROR, String.valueOf(failed.getMessage())));
        } finally {
            synchronized (incoming) {
                incoming.finished = true;
            }
            running.remove(request.id(), incoming);
        }
        if (incoming.cancelled) {
            // 打断只会在收场之前、和 cancelled 一起落下(见 cancelled 方法),这里清掉它,线程回到执行器时不带着
            Thread.interrupted();
            replyTo.dropped(request.id());
            return;
        }
        try {
            replyTo.send(response);
        } catch (IOException gone) {
            // 请求来时的那条路已经断了(对方走了):答复没处可交,照规范断开不等于撤回,处理已经做完
            LOG.debug("[numen-mcp] reply to {} {} not delivered: {}", request.method(), request.id(), gone.toString());
        }
    }

    private void cancelled(JsonObject params) {
        if (!(params.get("requestId") instanceof JsonPrimitive id)) {
            return;   // 格式不对的撤回照规范不理
        }
        Incoming incoming = running.get(RpcMessage.canonicalId(id));
        if (incoming == null || incoming.request.method().equals("initialize")) {
            return;
        }
        synchronized (incoming) {
            if (!incoming.finished) {
                incoming.cancelled = true;
                incoming.thread.interrupt();
            }
        }
    }

    // ---- 发 ----

    /** 发一个请求,答复回来时 {@link Outgoing#result} 完成;发不出去的当场以异常完成。 */
    public Outgoing request(String method, JsonObject params, Outbound via) {
        JsonPrimitive id = new JsonPrimitive(nextId.incrementAndGet());
        Outgoing outgoing = new Outgoing(id, method, new CompletableFuture<>());
        pending.put(id, outgoing);
        try {
            via.send(new RpcMessage.Request(id, method, params));
        } catch (IOException | RuntimeException failed) {
            pending.remove(id, outgoing);
            outgoing.result().completeExceptionally(failed);
        }
        return outgoing;
    }

    /**
     * 发一个请求并等答复。等满 {@code timeout} 就撤回它(发 {@code notifications/cancelled})并抛 {@link TimeoutException}。
     *
     * @throws RpcException 对方答的是错误
     * @throws IOException  发不出去,或这一端关了
     */
    public JsonElement call(String method, JsonObject params, Outbound via, Duration timeout)
            throws RpcException, IOException, TimeoutException, InterruptedException {
        Outgoing outgoing = request(method, params, via);
        try {
            return outgoing.result().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            cancel(outgoing, "timed out after " + timeout.toSeconds() + " s", via);
            throw new TimeoutException(method + " got no answer within " + timeout.toSeconds() + " s");
        } catch (InterruptedException interrupted) {
            cancel(outgoing, "the caller stopped waiting", via);
            throw interrupted;
        } catch (ExecutionException failed) {
            switch (failed.getCause()) {
                case RpcException e -> throw e;
                case IOException e -> throw e;
                case CancellationException e -> throw new IOException(method + " was withdrawn", e);
                case Throwable other -> throw new IOException(method + " failed: " + other, other);
            }
        }
    }

    /**
     * 撤回一个还在等答复的请求:照 MCP 告诉对方({@code notifications/cancelled}),以后到的答复不理。已经答复过的什么也不做。
     * 告诉对方的那条路断了也照样撤回:对方已经收不到这个请求的后续了。
     */
    public void cancel(Outgoing outgoing, String reason, Outbound via) {
        if (!pending.remove(outgoing.id(), outgoing)) {
            return;
        }
        outgoing.result().cancel(false);
        if (outgoing.method().equals("initialize")) {
            return;   // 规范:initialize 不许撤回
        }
        JsonObject params = new JsonObject();
        params.add("requestId", outgoing.id());
        params.addProperty("reason", reason);
        try {
            via.send(new RpcMessage.Notification(CANCELLED, params));
        } catch (IOException gone) {
            LOG.debug("[numen-mcp] cancellation of {} not delivered: {}", outgoing.id(), gone.toString());
        }
    }

    /** 发一个通知。 */
    public void notify(String method, JsonObject params, Outbound via) throws IOException {
        via.send(new RpcMessage.Notification(method, params));
    }

    /** 还在等答复的请求有几个(只读)。 */
    public int pending() {
        return pending.size();
    }

    /** 这一个请求的答复不会来了(传输说它没送到,或它的答复丢在了断掉的流上):以 {@code why} 失败。 */
    public void fail(JsonPrimitive id, IOException why) {
        Outgoing outgoing = pending.remove(id);
        if (outgoing != null) {
            outgoing.result().completeExceptionally(why);
        }
    }

    /** 还在等答复的请求全部以 {@code why} 失败:它们走的那条路断了,或这一端关了。 */
    public void failPending(IOException why) {
        for (Outgoing outgoing : List.copyOf(pending.values())) {
            if (pending.remove(outgoing.id(), outgoing)) {
                outgoing.result().completeExceptionally(why);
            }
        }
    }
}
