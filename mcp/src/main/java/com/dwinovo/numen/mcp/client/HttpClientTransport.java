package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.mcp.McpThreads;
import com.dwinovo.numen.mcp.protocol.McpMethods;
import com.dwinovo.numen.mcp.protocol.ProtocolVersion;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.dwinovo.numen.mcp.rpc.RpcMessage;
import com.dwinovo.numen.mcp.rpc.Sse;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端的 Streamable HTTP 传输(规范 {@code basic/transports}):每条消息一个 POST,答复是一个 JSON 或一条 SSE 流;握手答复头里的
 * {@code Mcp-Session-Id} 此后每个请求都带上,版本谈定后带 {@code MCP-Protocol-Version};握手做完开一条 GET 流听服务端与请求无关的消息。
 *
 * <ul>
 *   <li><b>授权</b>:每个请求带 {@link Authorization#headers} 给的头;401/403 交给 {@link Authorization#remedy}(登录、刷新、补权限),
 *       补救成了就重发。</li>
 *   <li><b>流断了接着读</b>:SSE 给过事件编号的,断了(服务端为了不长占连接主动断的也算)隔 {@code retry} 毫秒带
 *       {@code Last-Event-ID} 用 GET 接上;请求的答复没到、又接不上的,这个请求以失败交回({@link Inbound#lost})。</li>
 *   <li><b>会话被结束</b>:带着会话号的请求得到 404,抛 {@link SessionExpired},由 {@link McpClient} 重新握手。</li>
 *   <li><b>关</b>:DELETE 结束会话,开着的流断开。关了就是关了。</li>
 * </ul>
 *
 * <p>线程:请求在各自的线程上送出与读答复;GET 流一条线程读。
 */
public final class HttpClientTransport implements ClientTransport {

    private static final Logger LOG = LoggerFactory.getLogger("Numen");

    /** 一个请求最多补救几次授权(登录之后可能还要补一次权限)。 */
    private static final int MAX_AUTH_REMEDIES = 2;
    /** 流断了、服务端没说隔多久时,隔这么久再接。 */
    private static final long DEFAULT_RETRY_MS = 1000;
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    /** 请求带什么授权头、被拒了怎么补救。 */
    public interface Authorization {

        Authorization NONE = new Authorization() {
            @Override
            public Map<String, String> headers() {
                return Map.of();
            }

            @Override
            public boolean remedy(int status, List<String> wwwAuthenticate) {
                return false;
            }
        };

        /** 每个请求现取。 */
        Map<String, String> headers();

        /** 请求被 401/403 拒了:能补救就补救(阻塞,可能要等人登录),返回要不要重发。 */
        boolean remedy(int status, List<String> wwwAuthenticate) throws Exception;
    }

    private final URI endpoint;
    private final Authorization authorization;
    private final HttpClient http;
    /** 开着的流:关的时候一起断开。 */
    private final Set<InputStream> streams = ConcurrentHashMap.newKeySet();
    /** 第几个会话:旧会话的 GET 流发现换了会话就不再接。 */
    private final AtomicInteger generation = new AtomicInteger();
    private volatile Inbound inbound;
    private volatile String sessionId;
    private volatile ProtocolVersion version;
    private volatile boolean closed;

    public HttpClientTransport(URI endpoint, Duration connectTimeout, Authorization authorization) {
        this.endpoint = endpoint;
        this.authorization = authorization;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).version(HttpClient.Version.HTTP_1_1).build();
    }

    @Override
    public void open(Inbound inbound) {
        generation.incrementAndGet();
        this.inbound = inbound;
        this.sessionId = null;
        this.version = null;
    }

    @Override
    public void send(RpcMessage message) throws IOException {
        if (closed) {
            throw new IOException("the transport is closed");
        }
        if (message instanceof RpcMessage.Request request) {
            McpThreads.start("numen-mcp-client-post", () -> {
                try {
                    post(message, request.id());
                } catch (IOException failed) {
                    inbound.lost(request.id(), failed);
                }
            });
        } else {
            post(message, null);
        }
    }

    /** 送一条消息并读它的答复。{@code awaiting}:这是个请求,答复要等到。 */
    private void post(RpcMessage message, JsonPrimitive awaiting) throws IOException {
        HttpResponse<InputStream> response = exchange(() -> request()
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(message.encode(), StandardCharsets.UTF_8)));
        int status = response.statusCode();
        if (status == 202) {
            response.body().close();
            return;
        }
        String session = sessionId;
        if (status == 404 && session != null) {
            response.body().close();
            throw new SessionExpired(session);
        }
        if (status != 200) {
            throw failure(response);
        }
        if (awaiting == null) {
            response.body().close();   // 通知与答复没有答复可读:规范要 202,收下了的 200 也一样
            return;
        }
        if (message instanceof RpcMessage.Request r && r.method().equals(McpMethods.INITIALIZE)) {
            sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        }
        String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (type.startsWith("text/event-stream")) {
            read(response.body(), awaiting, null, -1);
        } else if (type.startsWith("application/json")) {
            String body;
            try (InputStream in = response.body()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!body.isBlank()) {
                try {
                    RpcMessage.decodeAll(body).forEach(inbound::message);
                } catch (RpcException malformed) {
                    throw new IOException("the server's answer is not JSON-RPC: " + malformed.getMessage(), malformed);
                }
            }
        } else {
            response.body().close();
            throw new IOException("the server answered with an unexpected content type: " + type);
        }
    }

    /**
     * 读一条 SSE 流,每个事件的消息交回去。在等答复的流断了而答复没到:流给过事件编号就隔一会儿用 GET 带编号接上,接着读;
     * 没给过或接不上,这个请求以失败交回。
     */
    private void read(InputStream body, JsonPrimitive awaiting, String lastEventId, long retry) throws IOException {
        int session = generation.get();
        InputStream current = body;
        while (true) {
            boolean answered = false;
            streams.add(current);
            try (Sse.Reader reader = new Sse.Reader(current)) {
                try {
                    Sse.Event event;
                    while ((event = reader.next()) != null) {
                        RpcMessage message = decode(event.data());
                        if (message == null) {
                            continue;
                        }
                        if (awaiting != null && message instanceof RpcMessage.Response r && awaiting.equals(r.id())) {
                            answered = true;
                        }
                        inbound.message(message);
                    }
                } catch (IOException broken) {
                    LOG.debug("[numen-mcp-client] stream from {} broke: {}", endpoint, broken.toString());
                }
                lastEventId = reader.lastEventId() != null ? reader.lastEventId() : lastEventId;
                retry = reader.retry() >= 0 ? reader.retry() : retry;
            } finally {
                streams.remove(current);
            }
            if (answered || closed || generation.get() != session) {
                return;
            }
            if (lastEventId == null) {
                if (awaiting != null) {
                    throw new IOException("the server closed the stream before answering");
                }
                return;
            }
            pause(retry);
            current = resume(lastEventId);
            if (current == null) {
                if (awaiting != null) {
                    throw new IOException("the stream broke before the answer and could not be resumed");
                }
                return;
            }
        }
    }

    /** 带 {@code Last-Event-ID} 用 GET 接上断了的流;接不上是 null。 */
    private InputStream resume(String lastEventId) throws IOException {
        HttpResponse<InputStream> response = exchange(() -> request()
                .header("Accept", "text/event-stream")
                .header("Last-Event-ID", lastEventId)
                .GET());
        if (response.statusCode() != 200) {
            response.body().close();
            return null;
        }
        return response.body();
    }

    @Override
    public void initialized(ProtocolVersion version) throws IOException {
        this.version = version;
        HttpResponse<InputStream> response = exchange(() -> request().header("Accept", "text/event-stream").GET());
        int status = response.statusCode();
        if (status != 200) {
            // 405 是服务端不开这条流(规范允许);别的也不影响请求本身,只是收不到服务端主动发的消息
            response.body().close();
            if (status != 405) {
                LOG.warn("[numen-mcp-client] {} refused the listening stream (HTTP {}): its notifications will not arrive",
                        endpoint, status);
            }
            return;
        }
        int session = generation.get();
        McpThreads.start("numen-mcp-client-listen", () -> listen(response.body(), session));
    }

    /** 读 GET 流;服务端关了它就隔一会儿再开(带上最后的事件编号),直到这个会话不在了或开不了。 */
    private void listen(InputStream first, int session) {
        InputStream body = first;
        String lastEventId = null;
        long retry = -1;
        while (body != null) {
            streams.add(body);
            try (Sse.Reader reader = new Sse.Reader(body)) {
                Sse.Event event;
                while ((event = reader.next()) != null) {
                    RpcMessage message = decode(event.data());
                    if (message != null) {
                        inbound.message(message);
                    }
                }
                lastEventId = reader.lastEventId() != null ? reader.lastEventId() : lastEventId;
                retry = reader.retry() >= 0 ? reader.retry() : retry;
            } catch (IOException broken) {
                LOG.debug("[numen-mcp-client] listening stream from {} broke: {}", endpoint, broken.toString());
            } finally {
                streams.remove(body);
            }
            if (closed || generation.get() != session) {
                return;
            }
            body = null;
            try {
                pause(retry);
                String resumeFrom = lastEventId;
                HttpResponse<InputStream> response = exchange(() -> {
                    HttpRequest.Builder b = request().header("Accept", "text/event-stream").GET();
                    return resumeFrom == null ? b : b.header("Last-Event-ID", resumeFrom);
                });
                if (response.statusCode() == 200 && !closed && generation.get() == session) {
                    body = response.body();
                } else {
                    response.body().close();
                }
            } catch (IOException failed) {
                LOG.debug("[numen-mcp-client] could not reopen the listening stream from {}: {}", endpoint, failed.toString());
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (InputStream stream : List.copyOf(streams)) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // 断开它就是目的
            }
        }
        String session = sessionId;
        if (session != null) {
            try {
                HttpResponse<Void> response = http.send(request().timeout(CLOSE_TIMEOUT).DELETE().build(),
                        HttpResponse.BodyHandlers.discarding());
                LOG.debug("[numen-mcp-client] ended session at {}: HTTP {}", endpoint, response.statusCode());
            } catch (IOException e) {
                LOG.debug("[numen-mcp-client] could not end the session at {}: {}", endpoint, e.toString());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        http.shutdownNow();
    }

    // ---- 零件 ----

    /** 一个请求的公共部分:端点、会话号、版本头、授权头。 */
    private HttpRequest.Builder request() {
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint);
        String session = sessionId;
        if (session != null) {
            b.header("Mcp-Session-Id", session);
        }
        ProtocolVersion v = version;
        if (v != null) {
            b.header("MCP-Protocol-Version", v.wire());
        }
        authorization.headers().forEach(b::setHeader);
        return b;
    }

    @FunctionalInterface
    private interface RequestFactory {
        HttpRequest.Builder build();
    }

    /** 发一个 HTTP 请求;401/403 交给授权补救,补救成了照新的授权头重发。 */
    private HttpResponse<InputStream> exchange(RequestFactory factory) throws IOException {
        for (int remedies = 0; ; remedies++) {
            HttpResponse<InputStream> response;
            try {
                response = http.send(factory.build().build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while talking to " + endpoint);
            }
            int status = response.statusCode();
            if (status != 401 && status != 403) {
                return response;
            }
            List<String> challenges = response.headers().allValues("WWW-Authenticate");
            response.body().close();
            boolean retry;
            try {
                retry = remedies < MAX_AUTH_REMEDIES && authorization.remedy(status, challenges);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("authorization failed: " + e.getMessage(), e);
            }
            if (!retry) {
                throw new IOException("HTTP " + status + ": the server refused the authorization");
            }
        }
    }

    private static IOException failure(HttpResponse<InputStream> response) throws IOException {
        String body;
        try (InputStream in = response.body()) {
            body = new String(in.readNBytes(512), StandardCharsets.UTF_8).strip();
        }
        return new IOException("HTTP " + response.statusCode() + (body.isEmpty() ? "" : ": " + body));
    }

    private RpcMessage decode(String data) {
        try {
            return RpcMessage.decode(data);
        } catch (RpcException malformed) {
            LOG.warn("[numen-mcp-client] {} sent something that is not a JSON-RPC message: {}", endpoint, malformed.getMessage());
            return null;
        }
    }

    private static void pause(long retryMs) throws InterruptedIOException {
        try {
            Thread.sleep(retryMs >= 0 ? retryMs : DEFAULT_RETRY_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting to reconnect");
        }
    }
}
