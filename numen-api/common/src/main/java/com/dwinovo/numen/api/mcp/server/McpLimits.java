package com.dwinovo.numen.api.mcp.server;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 远程端点的限流:每张令牌限同时在飞的调用数与每分钟的调用数,整个端点再限一个总量。服主在配置里改的是
 * {@link McpConfig.Limits},这里只执行。令牌之间互不挤占各自的额度,总量兜住所有令牌加起来的负载。
 *
 * <p>线程:任何线程,状态在 this 的锁里。
 */
final class McpLimits {

    private static final long WINDOW_MS = 60_000L;

    /** 一次放行:用完要 {@link #close},归还并发额度。被拒的 {@link #refusal} 非空,{@link #close} 什么也不做。 */
    final class Permit implements AutoCloseable {
        private final String token;
        private final String refusal;
        private boolean open;

        private Permit(String token, String refusal) {
            this.token = token;
            this.refusal = refusal;
            this.open = refusal == null;
        }

        /** 被拒的理由,给调用方的话;放行了是 null。 */
        String refusal() {
            return refusal;
        }

        @Override
        public void close() {
            if (open) {
                open = false;
                release(token);
            }
        }
    }

    private final LongSupplier clock;
    private volatile McpConfig.Limits limits;
    private final Map<String, Integer> inFlight = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> recent = new HashMap<>();
    private final ArrayDeque<Long> recentTotal = new ArrayDeque<>();
    private int inFlightTotal;

    McpLimits(McpConfig.Limits limits, LongSupplier clock) {
        this.limits = limits;
        this.clock = clock;
    }

    void configure(McpConfig.Limits limits) {
        this.limits = limits;
    }

    synchronized Permit acquire(String token) {
        McpConfig.Limits cap = limits;
        long now = clock.getAsLong();
        ArrayDeque<Long> own = recent.computeIfAbsent(token, k -> new ArrayDeque<>());
        trim(own, now);
        trim(recentTotal, now);
        if (inFlight.getOrDefault(token, 0) >= cap.perTokenConcurrent()) {
            return new Permit(token, "too many calls in flight for this token (limit " + cap.perTokenConcurrent() + ")");
        }
        if (own.size() >= cap.perTokenPerMinute()) {
            return new Permit(token, "this token is calling too fast (limit " + cap.perTokenPerMinute() + " per minute)");
        }
        if (inFlightTotal >= cap.totalConcurrent()) {
            return new Permit(token, "the server is busy with other tokens' calls (limit " + cap.totalConcurrent() + ")");
        }
        if (recentTotal.size() >= cap.totalPerMinute()) {
            return new Permit(token, "the server is receiving too many calls (limit " + cap.totalPerMinute() + " per minute)");
        }
        own.addLast(now);
        recentTotal.addLast(now);
        inFlight.merge(token, 1, Integer::sum);
        inFlightTotal++;
        return new Permit(token, null);
    }

    private synchronized void release(String token) {
        inFlight.computeIfPresent(token, (k, n) -> n <= 1 ? null : n - 1);
        inFlightTotal--;
    }

    private static void trim(ArrayDeque<Long> stamps, long now) {
        while (!stamps.isEmpty() && now - stamps.peekFirst() >= WINDOW_MS) {
            stamps.removeFirst();
        }
    }
}
