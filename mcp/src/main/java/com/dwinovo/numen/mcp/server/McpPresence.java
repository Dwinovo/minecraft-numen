package com.dwinovo.numen.mcp.server;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * 谁在用这个端点:按令牌汇总。会话本身在 {@link SessionRegistry};这里只回答宿主要问的"连着没有"。
 *
 * <p><b>"连着"只在这一处算</b>({@link Entry#connected}):还有请求在飞(含客户端开着的那条 GET 流——它一直占着一个请求),
 * 或者最近 {@value #IDLE_MS} 毫秒内出过声。get_events 长轮询最长 50 秒,正常的外部大脑请求间隔远小于此;误报"断开"比漏报伤——
 * 错误的断开会让她的程序被收掉。
 *
 * <p><b>算数的请求只有两种</b>:开新会话的 initialize({@link #opening}),和属于一个开着的会话的({@link #begin};会话开没开着由传输
 * 问 {@link SessionRegistry})。会话开了({@link #opened})、结束了({@link #ended})由会话表告诉这里。带着已结束或不存在的会话号的
 * 请求照 Streamable HTTP 的规定答 404、客户端重新 initialize,它不碰在场记录:否则客户端结束会话之后才到的请求会把驾驶席重新占上
 * {@value #IDLE_MS} 毫秒。
 *
 * <p>线程:任何线程。
 */
public final class McpPresence {

    public static final long IDLE_MS = 120_000L;
    /** 断开了这么久还没人回来的记录扔掉。 */
    static final long DROP_MS = 30 * 60_000L;

    /** 一张令牌的在场记录。 */
    public static final class Entry {
        private final McpToken token;
        private volatile String client;
        private volatile long lastSeen;
        private int inFlight;
        /** 最后一个开着的会话结束了,此后还没有开新的:安静的时间不再算"连着"。 */
        private boolean closed;

        private Entry(McpToken token, String client, long now) {
            this.token = token;
            this.client = client;
            this.lastSeen = now;
        }

        public McpToken token() {
            return token;
        }

        /** 对方自报的名字,没报过是 {@code unknown client}。 */
        public String client() {
            return client;
        }

        public long lastSeen() {
            return lastSeen;
        }

        private boolean connected(long now) {
            synchronized (this) {
                return inFlight > 0 || (!closed && now - lastSeen <= IDLE_MS);
            }
        }
    }

    private final LongSupplier clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public McpPresence(LongSupplier clock) {
        this.clock = clock;
    }

    /** 这张令牌来了一个开新会话的请求(initialize);配对 {@link #end}。 */
    public void opening(McpToken token) {
        Entry entry = entries.computeIfAbsent(token.id(), id -> new Entry(token, "unknown client", clock.getAsLong()));
        synchronized (entry) {
            entry.inFlight++;
            entry.lastSeen = clock.getAsLong();
        }
    }

    /** 这张令牌开着的一个会话来了一个请求(或开了 GET 流);配对 {@link #end}。 */
    public void begin(McpToken token) {
        Entry entry = entries.computeIfAbsent(token.id(), id -> new Entry(token, "unknown client", clock.getAsLong()));
        synchronized (entry) {
            entry.inFlight++;
            entry.lastSeen = clock.getAsLong();
        }
    }

    public void end(McpToken token) {
        Entry entry = entries.get(token.id());
        if (entry != null) {
            synchronized (entry) {
                entry.inFlight--;
                entry.lastSeen = clock.getAsLong();
            }
        }
    }

    /** 对方在 initialize 里自报了名字。 */
    public void named(McpToken token, String client) {
        Entry entry = entries.get(token.id());
        if (entry != null) {
            entry.client = client;
        }
    }

    /** 这张令牌开了一个会话。 */
    public void opened(McpToken token) {
        Entry entry = entries.get(token.id());
        if (entry != null) {
            synchronized (entry) {
                entry.closed = false;
                entry.lastSeen = clock.getAsLong();
            }
        }
    }

    /**
     * 这张令牌的一个会话结束了(客户端 DELETE、闲置太久、令牌作废)。{@code last}:它没有别的开着的会话了——此后在飞的流一收场就算断开,
     * 不等安静满 {@link #IDLE_MS};再开新会话又算连着。
     */
    public void ended(McpToken token, boolean last) {
        Entry entry = entries.get(token.id());
        if (entry != null) {
            synchronized (entry) {
                if (last) {
                    entry.closed = true;
                }
            }
        }
    }

    /** 令牌作废、过期了的记录扔掉,断开太久的也扔;返回扔掉的,调用方据此重算"主人在不在"。 */
    public List<Entry> sweep(Predicate<McpToken> valid) {
        List<Entry> dropped = new ArrayList<>();
        long now = clock.getAsLong();
        for (Entry entry : List.copyOf(entries.values())) {
            if (!valid.test(entry.token) || (!entry.connected(now) && now - entry.lastSeen > DROP_MS)) {
                if (entries.remove(entry.token.id(), entry)) {
                    dropped.add(entry);
                }
            }
        }
        return dropped;
    }

    /** 连着的、身份是大脑的令牌,按主人。 */
    public List<McpToken> brains(UUID owner) {
        long now = clock.getAsLong();
        return entries.values().stream()
                .filter(e -> e.token.role() == McpToken.Role.BRAIN && e.token.owner().equals(owner) && e.connected(now))
                .map(Entry::token).toList();
    }

    /** 有大脑连着的主人。 */
    public Set<UUID> brainOwners() {
        long now = clock.getAsLong();
        Set<UUID> owners = new LinkedHashSet<>();
        for (Entry e : entries.values()) {
            if (e.token.role() == McpToken.Role.BRAIN && e.connected(now)) {
                owners.add(e.token.owner());
            }
        }
        return owners;
    }

    /** 这位主人有大脑连着。 */
    public boolean brainConnected(UUID owner) {
        return !brains(owner).isEmpty();
    }

    /** 这个对象的驾驶席被谁坐着:有连着的大脑令牌、其作用域管着它。 */
    public boolean drives(UUID owner, UUID subject) {
        return brains(owner).stream().anyMatch(t -> t.scope().covers(subject));
    }

    /**
     * 客户端结束了会话、还有请求没收场的令牌(结束会话之后那条流要过一会儿才在别的线程上收掉):有就是一句说明,没有是 {@code null}。
     * 只读;GameTest 服务器每刻据此等断开落定,"她的外部大脑走了"总落在同一刻。
     */
    public String closing() {
        List<String> closing = new ArrayList<>();
        for (Entry entry : entries.values()) {
            synchronized (entry) {
                if (entry.closed && entry.inFlight > 0) {
                    closing.add(entry.token.id());
                }
            }
        }
        return closing.isEmpty() ? null : "mcp session(s) still closing for token(s) " + closing;
    }

    /** 所有记录(界面显示最近接入的客户端、最近出声的时刻)。 */
    public List<Entry> all() {
        return List.copyOf(entries.values());
    }
}
