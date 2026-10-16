package com.dwinovo.numen.api.store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 磁盘写的车道:一个键(一只同伴、一位玩家)一条串行车道,车道之间并行。同一个键下的所有写——对话日志、收件箱、札记、
 * 目标——按提交顺序落盘,不同的键互不等。只有这一条写路径:没有"这一条要紧所以同步写"的特例,停机时把各条车道排空
 * ({@link #drain}),崩溃最多丢车道里还没写下去的那几毫秒。
 *
 * <p>线程:任何线程。
 */
public final class Lanes {

    private final Executor delegate;
    private final Consumer<StoreException> onError;
    private final Map<String, SerialExecutor> lanes = new ConcurrentHashMap<>();

    /**
     * @param delegate 真正跑磁盘任务的执行器(各车道共用)
     * @param onError  写失败的去处,可能在执行器的线程上被调用
     */
    public Lanes(Executor delegate, Consumer<StoreException> onError) {
        this.delegate = delegate;
        this.onError = onError;
    }

    /** 这个键的车道,接成 {@link Io};第一次用时建。 */
    public Io io(String key) {
        return new Io(lanes.computeIfAbsent(key, k -> new SerialExecutor(delegate)), onError);
    }

    /**
     * 等各条车道里已交的写都落下去,至多 {@code timeout}(整体,不是每条)。
     *
     * @return 全排空了为 true;超时为 false(日志里该记一笔:有写没落下去)
     */
    public boolean drain(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        boolean all = true;
        for (SerialExecutor lane : lanes.values()) {
            long left = Math.max(0, deadline - System.nanoTime());
            all &= lane.awaitIdle(left, TimeUnit.NANOSECONDS);
        }
        return all;
    }

    /** 这个键不会再有写了(同伴被遣散):车道不用留。 */
    public void forget(String key) {
        lanes.remove(key);
    }
}
