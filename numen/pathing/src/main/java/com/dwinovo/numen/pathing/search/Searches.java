package com.dwinovo.numen.pathing.search;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 搜索的派发口,全模块只有这一个:搜索与由搜索组成的活都经这里交给一个共享的工作线程池。
 *
 * <p>池子是固定大小(处理器数减二,至少两个)、队列不设上限的守护线程池,线程优先级最低——搜索是纯计算,线程数封顶才不会
 * 同伴一多就挤占服务器与渲染线程;闲着的线程超时回收。交进来的输入里,世界是派发前在世界所在线程上拷好的快照
 * ({@link WorldSnapshot}),成本模型只读,所以工作线程从不碰活世界。
 *
 * <p>排队分两级({@link Urgency}):身体站着等的(从脚下搜、只搜不走的规划、诊断为什么没路)排在身体边走边等的接续前面,
 * 同一级里先来先跑。池子满了时,接续让首段先跑:首段决定她多久开始动,接续还有剩下那截路的时间。
 */
public final class Searches {

    private static final int POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
    private static final AtomicInteger THREADS = new AtomicInteger();
    /** 派发的先后:同一级里先来先跑。 */
    private static final AtomicLong ORDER = new AtomicLong();
    /** 派发了、还没收场的活:排着的与在算的。派发前加一,活跑完或从队里拿走时减一。 */
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final ThreadPoolExecutor POOL = pool();

    private Searches() {}

    /** 一件活排队的级别。 */
    public enum Urgency {
        /** 身体站着等它:从脚下搜、只搜不走的规划、诊断为什么没路。 */
        NOW,
        /** 身体边走边等它:从手上路线的终点接着搜。 */
        AHEAD
    }

    /** 工作线程池的大小。 */
    public static int poolSize() {
        return POOL_SIZE;
    }

    /**
     * 此刻有没有派发了还没收场的搜索(在算或排着队):有就是一句说明,没有是 {@code null}。只读,不改任何状态。
     * 按自己记的在飞数判,不看线程池的瞬时状态:活交给线程到线程开始跑之间,池子的在算数与队长都是零。
     */
    public static String busy() {
        int inFlight = IN_FLIGHT.get();
        return inFlight == 0 ? null : inFlight + " pathing search(es) running or queued";
    }

    /**
     * 主线程问到一件还没跑完的活时做什么({@link Pending#poll} 之前调)。正式运行什么都不做:没跑完就是还没有结论,下一刻再问,
     * 主线程从不为搜索停。
     */
    private static volatile Consumer<Future<?>> onUnfinishedPoll = unfinished -> { };

    /**
     * 从此主线程问到还没跑完的活时交给 {@code wait}(它返回时活应当跑完了)。GameTest 服务器用它让主线程等结论回来:每刻开头与收尾
     * 等齐在飞的活({@link #busy})管的是刻与刻之间,一件活在一刻当中派出、又在同一刻里被问到(受理前的规划派出之后同一刻里再问一次、
     * 规划没交出路之后接着派的诊断),问到的是不是结论就看工作线程赶没赶上;在这里等它回来,哪一刻拿到结论就只看主线程问的先后。
     */
    public static void onUnfinishedPoll(Consumer<Future<?>> wait) {
        onUnfinishedPoll = wait;
    }

    static void beforePoll(Future<?> future) {
        if (!future.isDone()) {
            onUnfinishedPoll.accept(future);
        }
    }

    private static ThreadPoolExecutor pool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(POOL_SIZE, POOL_SIZE, 60, TimeUnit.SECONDS,
                new PriorityBlockingQueue<>(), task -> {
                    Thread thread = new Thread(task, "numen-pathing-" + THREADS.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** 派发一次搜索,排在 {@code urgency} 那一级。 */
    public static Pending<SearchResult> submit(Search search, Urgency urgency) {
        return dispatch(cancelled -> AStar.run(search, cancelled), urgency);
    }

    /** 一件由搜索组成的活:在工作线程上跑,{@code cancelled} 答是就尽快收手。 */
    @FunctionalInterface
    public interface Job<T> {
        T run(BooleanSupplier cancelled);
    }

    /** 派发一件由搜索组成的活(门面诊断"为什么没路"时在同一份快照上换几份规格再搜):有人站着等它。 */
    public static <T> Pending<T> submit(Job<T> job) {
        return dispatch(job::run, Urgency.NOW);
    }

    private static <T> Pending<T> dispatch(Function<BooleanSupplier, T> job, Urgency urgency) {
        AtomicBoolean cancelled = new AtomicBoolean();
        Pending.Clock clock = new Pending.Clock();
        CompletableFuture<T> future = new CompletableFuture<>();
        Task task = new Task(urgency, ORDER.getAndIncrement(), () -> {
            try {
                future.complete(clock.time(() -> job.apply(cancelled::get)));
            } catch (Throwable e) {
                future.completeExceptionally(e);
            } finally {
                IN_FLIGHT.decrementAndGet();
            }
        });
        IN_FLIGHT.incrementAndGet();
        POOL.execute(task);
        // 叫停时还在队里排着的,直接从队里拿走:它握着的快照与成本模型不必等到有线程空出来、跑到它、发现被叫停才放掉
        return new Pending<>(future, cancelled, clock, () -> {
            if (POOL.remove(task)) {
                IN_FLIGHT.decrementAndGet();
            }
        });
    }

    /** 队里的一件活:先比级别,再比派发的先后。 */
    private record Task(Urgency urgency, long order, Runnable body) implements Runnable, Comparable<Task> {

        @Override
        public void run() {
            body.run();
        }

        @Override
        public int compareTo(Task other) {
            int byUrgency = urgency.compareTo(other.urgency);
            return byUrgency != 0 ? byUrgency : Long.compare(order, other.order);
        }
    }
}
