package com.dwinovo.numen.api.mcp.server;

import com.google.gson.JsonObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Streamable HTTP 的会话表:{@code initialize} 开一个,之后每个请求带着 {@code Mcp-Session-Id}。会话记着是哪张令牌开的、
 * 对方的名字、它声明会不会答 elicitation,以及它开的那条服务端→客户端的流。
 *
 * <p><b>"连着"只在这一处算</b>({@link #connected}):还有调用在飞,或者那条流开着,或者最近 {@value #IDLE_MS} 毫秒内
 * 出过声。get_events 长轮询最长 50 秒,正常的外部大脑请求间隔远小于此;误报"断开"比漏报伤——
 * 错误的断开会让她的程序被收掉。
 *
 * <p>线程:任何线程。
 */
final class McpSessions {

    static final long IDLE_MS = 120_000L;
    /** 断开了这么久还没人回来的会话从表里扔掉(客户端再来会得到 404,重新 initialize)。 */
    static final long DROP_MS = 30 * 60_000L;
    /** 每张令牌同时开着的会话上限。 */
    static final int MAX_PER_TOKEN = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 服务端→客户端那条流的出口:一条 SSE 事件写出去;写不出去(对方走了)返回 false。 */
    interface Sink {
        boolean send(String event);

        void close();
    }

    /** 一个会话。 */
    final class Session {
        final String id;
        final McpToken token;
        final String client;
        /** 对方在 initialize 里声明了 elicitation 能力。 */
        final boolean elicits;
        private volatile long lastSeen;
        private final AtomicInteger inFlight = new AtomicInteger();
        private volatile Sink stream;
        private final AtomicLong asked = new AtomicLong();
        private final Map<String, CompletableFuture<JsonObject>> asking = new ConcurrentHashMap<>();

        private Session(String id, McpToken token, String client, boolean elicits) {
            this.id = id;
            this.token = token;
            this.client = client;
            this.elicits = elicits;
            this.lastSeen = clock.getAsLong();
        }

        void touch() {
            lastSeen = clock.getAsLong();
        }

        long lastSeen() {
            return lastSeen;
        }

        /** 开始处理一个请求;配对 {@link #finished}。 */
        void started() {
            inFlight.incrementAndGet();
            touch();
        }

        void finished() {
            inFlight.decrementAndGet();
            touch();
        }

        boolean hasStream() {
            return stream != null;
        }

        /** 接上客户端开的流;它原来的那条被顶掉。 */
        void attach(Sink sink) {
            Sink old = stream;
            stream = sink;
            touch();
            if (old != null) {
                old.close();
            }
        }

        /** 这条流断了;已经被新的顶掉了就不管。 */
        void detach(Sink sink) {
            if (stream == sink) {
                stream = null;
                touch();
            }
        }

        /**
         * 经流向客户端发一个请求,答复在返回的 future 里;没有流、写不出去时 future 直接失败。
         * 对方不理会(超时)时调用方自己撤({@link #abandon})。
         */
        CompletableFuture<JsonObject> request(String method, JsonObject params) {
            CompletableFuture<JsonObject> answer = new CompletableFuture<>();
            Sink sink = stream;
            if (sink == null) {
                answer.completeExceptionally(new IllegalStateException("the client has no open stream"));
                return answer;
            }
            String id = "srv-" + asked.incrementAndGet();
            asking.put(id, answer);
            JsonObject frame = new JsonObject();
            frame.addProperty("jsonrpc", "2.0");
            frame.addProperty("id", id);
            frame.addProperty("method", method);
            frame.add("params", params);
            if (!sink.send(frame.toString())) {
                asking.remove(id);
                answer.completeExceptionally(new IllegalStateException("the stream is closed"));
            }
            return answer;
        }

        /** 客户端用 POST 送回了对我们请求的答复;不是我们问过的 id 返回 false。 */
        boolean answered(String id, JsonObject response) {
            CompletableFuture<JsonObject> waiting = asking.remove(id);
            if (waiting == null) {
                return false;
            }
            waiting.complete(response);
            touch();
            return true;
        }

        /** 不再等某个请求的答复,并告诉客户端撤掉({@code notifications/cancelled})。 */
        void abandon(CompletableFuture<JsonObject> answer) {
            asking.values().remove(answer);
            answer.cancel(false);
        }

        /** 向客户端发一条通知(没有流就算了:通知不要求送达)。 */
        void notifyClient(String method, JsonObject params) {
            Sink sink = stream;
            if (sink != null) {
                JsonObject frame = new JsonObject();
                frame.addProperty("jsonrpc", "2.0");
                frame.addProperty("method", method);
                frame.add("params", params);
                sink.send(frame.toString());
            }
        }

        /** 请求号:撤销通知要写它。 */
        String idOf(CompletableFuture<JsonObject> answer) {
            return asking.entrySet().stream().filter(e -> e.getValue() == answer).map(Map.Entry::getKey)
                    .findFirst().orElse(null);
        }

        boolean connected() {
            return inFlight.get() > 0 || stream != null || clock.getAsLong() - lastSeen <= IDLE_MS;
        }

        private void shutdown() {
            Sink sink = stream;
            stream = null;
            if (sink != null) {
                sink.close();
            }
            RuntimeException gone = new IllegalStateException("session ended");
            asking.values().forEach(f -> f.completeExceptionally(gone));
            asking.clear();
        }
    }

    private final LongSupplier clock;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    McpSessions(LongSupplier clock) {
        this.clock = clock;
    }

    /** 开一个会话;这张令牌已经开满了返回 null。 */
    synchronized Session open(McpToken token, String client, boolean elicits) {
        long mine = sessions.values().stream().filter(s -> s.token.hash().equals(token.hash())).count();
        if (mine >= MAX_PER_TOKEN) {
            return null;
        }
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        Session session = new Session(HexFormat.of().formatHex(raw), token, client, elicits);
        sessions.put(session.id, session);
        return session;
    }

    Session find(String id) {
        return id == null ? null : sessions.get(id);
    }

    /** 客户端 DELETE,或令牌作废:会话结束,它的流关掉。 */
    void end(Session session) {
        if (sessions.remove(session.id, session)) {
            session.shutdown();
        }
    }

    /** 令牌作废、过期了的会话结束;断开太久的扔掉。返回结束了的会话,调用方据此重算"主人在不在"。 */
    List<Session> sweep(java.util.function.Predicate<McpToken> valid) {
        List<Session> ended = new ArrayList<>();
        long now = clock.getAsLong();
        for (Session s : List.copyOf(sessions.values())) {
            if (!valid.test(s.token) || (!s.connected() && now - s.lastSeen > DROP_MS)) {
                end(s);
                ended.add(s);
            }
        }
        return ended;
    }

    /** 连着的、身份是大脑的会话,按主人。 */
    List<Session> brains(UUID owner) {
        return sessions.values().stream()
                .filter(s -> s.token.role() == McpToken.Role.BRAIN && s.token.owner().equals(owner) && s.connected())
                .toList();
    }

    /** 有大脑会话连着的主人。 */
    Set<UUID> brainOwners() {
        Set<UUID> owners = new LinkedHashSet<>();
        for (Session s : sessions.values()) {
            if (s.token.role() == McpToken.Role.BRAIN && s.connected()) {
                owners.add(s.token.owner());
            }
        }
        return owners;
    }

    /** 这位主人有大脑会话连着。 */
    boolean brainConnected(UUID owner) {
        return !brains(owner).isEmpty();
    }

    /** 这只同伴的驾驶席被谁坐着:有连着的大脑会话、其令牌的作用域管着她。 */
    boolean drives(UUID owner, UUID companion) {
        return brains(owner).stream().anyMatch(s -> s.token.scope().covers(companion));
    }

    /** 全部会话(给宿主关停时逐个结束)。 */
    List<Session> all() {
        return List.copyOf(sessions.values());
    }
}
