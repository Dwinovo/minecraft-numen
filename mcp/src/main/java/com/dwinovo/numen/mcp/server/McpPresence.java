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
 * 谁在用这个端点:按令牌记。会话本身归官方 SDK 管;这里只回答宿主要问的"连着没有"。
 *
 * <p><b>"连着"只在这一处算</b>({@link Entry#connected}):还有请求在飞(含客户端开着的那条 GET 流——它一直占着一个请求),
 * 或者最近 {@value #IDLE_MS} 毫秒内出过声。get_events 长轮询最长 50 秒,正常的外部大脑请求间隔远小于此;误报"断开"比漏报伤——
 * 错误的断开会让她的程序被收掉。
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
        /** 客户端主动结束过会话,此后还没有新的请求:安静的时间不再算"连着"。 */
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

    /** 这张令牌来了一个请求;配对 {@link #end}。 */
    public void begin(McpToken token) {
        Entry entry = entries.computeIfAbsent(token.id(), id -> new Entry(token, "unknown client", clock.getAsLong()));
        synchronized (entry) {
            entry.inFlight++;
            entry.closed = false;
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

    /** 客户端主动结束了会话(DELETE):它在飞的流一收场就算断开,不等安静满 {@link #IDLE_MS};之后再来请求又算连着。 */
    public void ended(McpToken token) {
        Entry entry = entries.get(token.id());
        if (entry != null) {
            synchronized (entry) {
                entry.closed = true;
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

    /** 所有记录(界面显示最近接入的客户端、最近出声的时刻)。 */
    public List<Entry> all() {
        return List.copyOf(entries.values());
    }
}
