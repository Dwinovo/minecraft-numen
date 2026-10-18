package com.dwinovo.numen.api.mcp.server;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 没认出令牌的请求,按来源地址管两件事:
 * <ul>
 *   <li><b>限流</b>:一个地址在一个时间窗里认证失败到 {@value #FAILURES_PER_WINDOW} 次,这个窗里它没通过认证的请求回 429。
 *       令牌照常验、验过的照常放行(令牌是 256 位随机数,按哈希查表,猜不中),所以反向代理后面共用一个地址的合法用户不受外人连累;</li>
 *   <li><b>审计合并</b>:同一个窗里同一个地址的失败只记一条({@link McpAudit#strangers}:次数、首末时刻),
 *       窗收场时才写,所以洪水也只是每个地址每窗一条。</li>
 * </ul>
 * 窗从该地址的第一次失败算起、固定长度。地址表有上限,满了的新地址并进同一个 {@value #OVERFLOW} 桶。
 *
 * <p>线程:任何线程,状态在 this 的锁里。
 */
final class McpStrangers {

    static final long WINDOW_MS = 60_000L;
    static final int FAILURES_PER_WINDOW = 10;
    static final int MAX_ADDRESSES = 4096;
    static final String OVERFLOW = "*";

    private static final class Burst {
        final String why;
        final long first;
        long last;
        int count;

        Burst(String why, long now) {
            this.why = why;
            this.first = now;
            this.last = now;
        }
    }

    private final McpAudit audit;
    private final LongSupplier clock;
    private final Map<String, Burst> bursts = new HashMap<>();

    McpStrangers(McpAudit audit, LongSupplier clock) {
        this.audit = audit;
        this.clock = clock;
    }

    /** 这个地址此刻被限了吗(它这个窗里的失败已满)。 */
    synchronized boolean blocked(String address) {
        Burst burst = bursts.get(key(address));
        return burst != null && clock.getAsLong() - burst.first < WINDOW_MS && burst.count >= FAILURES_PER_WINDOW;
    }

    /** 这个地址又一次没通过认证。 */
    synchronized void failed(String address, String why) {
        long now = clock.getAsLong();
        flushClosed(now);
        Burst burst = bursts.computeIfAbsent(key(address), k -> new Burst(why, now));
        burst.count++;
        burst.last = now;
    }

    /** 窗已收场的写进审计。宿主每刻调一次,新的失败来时也顺带调。 */
    synchronized void flushClosed() {
        flushClosed(clock.getAsLong());
    }

    /** 停服:没收场的也写下去。 */
    synchronized void flushAll() {
        bursts.forEach(this::write);
        bursts.clear();
    }

    private void flushClosed(long now) {
        for (Iterator<Map.Entry<String, Burst>> it = bursts.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Burst> entry = it.next();
            if (now - entry.getValue().first >= WINDOW_MS) {
                write(entry.getKey(), entry.getValue());
                it.remove();
            }
        }
    }

    private void write(String address, Burst burst) {
        audit.strangers(address, burst.why, burst.count, burst.first, burst.last);
    }

    private String key(String address) {
        return bursts.containsKey(address) || bursts.size() < MAX_ADDRESSES ? address : OVERFLOW;
    }
}
