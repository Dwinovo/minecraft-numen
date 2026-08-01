package com.dwinovo.numen.core.pathing.calc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The shared worker pool that runs A* searches off the server tick thread.
 * One pool for ALL companions — 100 companions don't spawn 100 threads. Each
 * {@link com.dwinovo.numen.core.pathing.exec.PlayerNav} submits its search here and polls the returned
 * future each tick.
 *
 * <h2>Sizing: bounded, CPU-friendly</h2>
 * A* is pure CPU work. The pool is a <b>fixed</b> {@link #POOL_SIZE} = {@code max(2, cores - 2)}
 * threads with an <b>unbounded queue</b>: excess searches wait their turn instead of each spawning a
 * thread. This is the standard shape for compute-bound work at scale (Goetz, <i>JCiP</i> §8.2) — it
 * caps CPU threads no matter how many companions submit at once, leaving cores for the (integrated or
 * dedicated) server tick and the client render thread. The previous shape
 * ({@code max=Integer.MAX_VALUE} + {@code SynchronousQueue}) spawned a new thread per submission and,
 * with many companions, could grow unbounded and starve the game thread. Backlog stays small in
 * practice: a companion holds at most one in-flight search (it polls before re-dispatching), so the
 * queue is bounded by companion count.
 *
 * <p>Workers are <b>daemon</b> (never hold up JVM shutdown) and run at {@link Thread#MIN_PRIORITY} so
 * the OS scheduler prefers the latency-sensitive game / render threads whenever both are runnable —
 * a cheap best-effort assist on top of the hard thread cap. Idle core threads time out
 * ({@link ThreadPoolExecutor#allowCoreThreadTimeOut}) so the pool holds no threads when nothing navigates.
 */
public final class PathPlannerPool {

    private PathPlannerPool() {}

    private static final AtomicInteger COUNTER = new AtomicInteger();

    /** Bounded worker count: leave two cores for the server tick + client render threads. */
    private static final int POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);

    private static final ThreadPoolExecutor POOL = createPool();

    private static ThreadPoolExecutor createPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                POOL_SIZE, POOL_SIZE, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "numen-path-" + COUNTER.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** Run {@code task} on the planner pool; the result lands in the returned future. */
    public static <T> CompletableFuture<T> submit(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, POOL);
    }

    // ==================== 性能探针用的池快照(见 NavProfiler)====================

    /** 当前存活线程数(有界:上限 {@link #POOL_SIZE};空闲会超时回收)。 */
    public static int liveThreads() {
        return POOL.getPoolSize();
    }

    /** 当前正在跑搜索的线程数。 */
    public static int activeThreads() {
        return POOL.getActiveCount();
    }

    /** 历史峰值线程数(JDK 不可复位,反映最坏时刻的线程膨胀)。 */
    public static int peakThreads() {
        return POOL.getLargestPoolSize();
    }

    /** 累计完成的搜索任务数(取窗口差值即本窗口吞吐)。 */
    public static long completedTasks() {
        return POOL.getCompletedTaskCount();
    }
}
