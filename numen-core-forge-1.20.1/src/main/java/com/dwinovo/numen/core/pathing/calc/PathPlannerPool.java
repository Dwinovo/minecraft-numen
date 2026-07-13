package com.dwinovo.numen.core.pathing.calc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.SynchronousQueue;
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
 * <p>Sizing copies Baritone's executor verbatim ({@code Baritone.java}): 4 core threads, grow on demand
 * to handle bursts, 60s idle reap, direct hand-off ({@link SynchronousQueue}). Threads are daemon so
 * they never hold up JVM shutdown.
 *
 * <p>TODO (config): expose core/max thread counts as a server setting, defaulting to these Baritone
 * values, for operators tuning many-companion servers.
 */
public final class PathPlannerPool {

    private PathPlannerPool() {}

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(
            4, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "numen-path-" + COUNTER.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });

    /** Run {@code task} on the planner pool; the result lands in the returned future. */
    public static <T> CompletableFuture<T> submit(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, POOL);
    }
}
