package com.dwinovo.numen.api.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 每位玩家上行的带宽预算:令牌桶,按字节记。桶满是 {@code burst} 字节(一条最大的正当消息装得下),之后每秒回
 * {@code bytesPerSecond}。超了的不收——丢掉、不排队,让一个客户端狂发的东西进不了服务端的主线程。
 *
 * <p>线程:任何线程(网络线程上收分片,主线程上断线清桶);每只桶自己串行。时钟由使用方给,单测用假的。
 */
public final class UplinkBudget {

    private static final class Bucket {
        double tokens;
        long lastNanos;

        Bucket(double tokens, long lastNanos) {
            this.tokens = tokens;
            this.lastNanos = lastNanos;
        }
    }

    private final long burst;
    private final long bytesPerSecond;
    private final LongSupplier nanos;
    private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    public UplinkBudget(long burst, long bytesPerSecond, LongSupplier nanos) {
        this.burst = burst;
        this.bytesPerSecond = bytesPerSecond;
        this.nanos = nanos;
    }

    /** 这位玩家再花 {@code bytes} 字节,够不够。够就扣掉并返回 true;不够什么也不扣,返回 false。 */
    public boolean admit(UUID player, int bytes) {
        long now = nanos.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(player, id -> new Bucket(burst, now));
        synchronized (bucket) {
            bucket.tokens = Math.min(burst, bucket.tokens + (now - bucket.lastNanos) / 1e9 * bytesPerSecond);
            bucket.lastNanos = now;
            if (bucket.tokens < bytes) {
                return false;
            }
            bucket.tokens -= bytes;
            return true;
        }
    }

    /** 这位玩家断线了:他的桶不用留。 */
    public void forget(UUID player) {
        buckets.remove(player);
    }
}
