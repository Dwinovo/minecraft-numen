package com.dwinovo.numen.pathing.search;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * 搜索的派发口,全模块只有这一个:搜索与候选查询都经这里交给一个共享的工作线程池。
 *
 * <p>池子是固定大小(处理器数减二,至少两个)、队列不设上限的守护线程池,线程优先级最低——搜索是纯计算,线程数封顶才不会
 * 同伴一多就挤占服务器与渲染线程;闲着的线程超时回收。交进来的输入里,世界是派发前在世界所在线程上拷好的快照
 * ({@link WorldSnapshot}),成本模型只读,所以工作线程从不碰活世界。
 */
public final class Searches {

    private static final int POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final ThreadPoolExecutor POOL = pool();

    private Searches() {}

    private static ThreadPoolExecutor pool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(POOL_SIZE, POOL_SIZE, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), task -> {
                    Thread thread = new Thread(task, "numen-pathing-" + THREADS.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** 派发一次搜索。 */
    public static Pending<SearchResult> submit(Search search) {
        return dispatch(cancelled -> AStar.run(search, cancelled));
    }

    /** 派发一次候选查询。 */
    public static Pending<RoutePlanner.Plan> submit(RoutePlanner.Query query) {
        return dispatch(cancelled -> RoutePlanner.run(query, cancelled));
    }

    private static <T> Pending<T> dispatch(Function<BooleanSupplier, T> job) {
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> job.apply(cancelled::get), POOL);
        return new Pending<>(future, cancelled);
    }
}
