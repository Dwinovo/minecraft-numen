package com.dwinovo.numen.pathing.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.SearchView;

import net.minecraft.core.BlockPos;

/**
 * 标准答案的求解,出题({@link Generate})与单独重算答案({@link Answers})共用这一处。两档节点上限:先按第一档({@link #cap})解,撞上限的再用第二档
 * ({@link #largeCap})重解,第二档也撞上限的按生产权重试一个上界({@link Reference#upperBound})。
 *
 * <p><b>按内存预算排程</b>:一个线程池(处理器数减一个线程,{@link Answers#threads}),每个作业开工前从一份字节预算({@link Answers#memoryBudget})里预留
 * 它这一档会占的 {@code 上限 × }{@link Answers#NODE_BYTES},做完归还。预算先到先得(公平的信号量),所以大作业不会被一直插队的小作业饿死;预算装不下它就等,
 * 所以活着的节点总量不会超过预算(超过就是堆里的 GC 空转)。第二档是撞上限之后作为新作业排回队里,与别的作业并行占它那份预算,没有"一批等第二档"的栅栏。
 * 队里按优先级(出题时是候选的序号)先小后大,先抽到的候选先出结论。
 */
final class Solver implements AutoCloseable {

    /** 一条要解的:这一块区域的快照、成本模型、起点与目标。 */
    record Job(SearchView whole, CostModel model, BlockPos start, Goal goal) {}

    /**
     * 一条解完的结论。
     *
     * @param tier   用的是第几档上限(1 或 2)
     * @param millis 这条自己用的墙钟(所有档加起来,不含排队等预算的)
     */
    record Outcome(Reference.Solved solved, int tier, long millis) {}

    /** 预算按这么多字节一块记账。 */
    private static final long BLOCK = 1 << 20;

    private final int cap;
    private final int largeCap;
    private final int totalBlocks;
    private final Semaphore budget;
    private final ThreadPoolExecutor pool;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong reserved = new AtomicLong();
    private final AtomicLong peak = new AtomicLong();

    /**
     * @param budgetBytes 活着的节点最多占多少字节
     * @param threads     同时跑几个作业(核数上限)
     */
    Solver(int cap, int largeCap, long budgetBytes, int threads) {
        this.cap = cap;
        this.largeCap = largeCap;
        this.totalBlocks = (int) Math.max(1, budgetBytes / BLOCK);
        this.budget = new Semaphore(totalBlocks, true);
        this.pool = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS, new PriorityBlockingQueue<>());
    }

    /** 默认的两档:{@link Reference#NODE_CAP} 与 {@link Reference#LARGE_NODE_CAP},预算与核数是这台机器的({@link Answers#memoryBudget}、{@link Answers#threads})。 */
    static Solver standard() {
        return new Solver(Reference.NODE_CAP, Reference.LARGE_NODE_CAP, Answers.memoryBudget(), Answers.threads());
    }

    int cap() {
        return cap;
    }

    int largeCap() {
        return largeCap;
    }

    /** 到现在为止同时预留过的最多字节数(单测看预算没超)。 */
    long peakReservedBytes() {
        return peak.get() * BLOCK;
    }

    /** 预算总额,按块取整后的字节数。 */
    long budgetBytes() {
        return (long) totalBlocks * BLOCK;
    }

    /**
     * 排进一条作业。{@code priority} 小的先开工(同一优先级先来的先开工);{@code cancelled} 为真时没开工的作业不再开工、在跑的搜索尽快收手,
     * 它的结论是被取消的。
     */
    CompletableFuture<Outcome> submit(Job job, long priority, BooleanSupplier cancelled) {
        CompletableFuture<Outcome> out = new CompletableFuture<>();
        pool.execute(new Task(priority, sequence.getAndIncrement(), () -> first(job, priority, cancelled, out)));
        return out;
    }

    /** 解 {@code jobs},按输入的顺序交回;每条一解完就调 {@code done}(同一时刻只有一个调用)。 */
    List<Outcome> solve(List<Job> jobs, BiConsumer<Integer, Outcome> done) {
        List<CompletableFuture<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            int index = i;
            futures.add(submit(jobs.get(i), i, () -> false).thenApply(o -> {
                synchronized (done) {
                    done.accept(index, o);
                }
                return o;
            }));
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }

    // ==================== 作业 ====================

    private void first(Job job, long priority, BooleanSupplier cancelled, CompletableFuture<Outcome> out) {
        try {
            if (cancelled.getAsBoolean()) {
                out.cancel(false);
                return;
            }
            long t0 = System.nanoTime();
            Reference.Solved s = reserving(cap, () -> Reference.solve(job.whole(), job.model(), job.start(), job.goal(), cap, cancelled));
            long millis = (System.nanoTime() - t0) / 1_000_000;
            if (s.status() == Answers.Status.TOO_HARD && !cancelled.getAsBoolean()) {
                pool.execute(new Task(priority, sequence.getAndIncrement(), () -> second(job, cancelled, out, millis)));
            } else {
                out.complete(new Outcome(s, 1, millis));
            }
        } catch (Throwable e) {
            out.completeExceptionally(e);
        }
    }

    private void second(Job job, BooleanSupplier cancelled, CompletableFuture<Outcome> out, long spent) {
        try {
            if (cancelled.getAsBoolean()) {
                out.cancel(false);
                return;
            }
            long t0 = System.nanoTime();
            Reference.Solved s = reserving(largeCap, () -> {
                Reference.Solved exact = Reference.solve(job.whole(), job.model(), job.start(), job.goal(), largeCap, cancelled);
                return exact.status() == Answers.Status.TOO_HARD
                        ? Reference.upperBound(job.whole(), job.model(), job.start(), job.goal(), largeCap, exact, cancelled) : exact;
            });
            out.complete(new Outcome(s, 2, spent + (System.nanoTime() - t0) / 1_000_000));
        } catch (Throwable e) {
            out.completeExceptionally(e);
        }
    }

    /** 预留这一档的内存预算,跑 {@code work},归还。一个作业比整个预算还大就独占整个预算。 */
    private <T> T reserving(int nodes, java.util.function.Supplier<T> work) throws InterruptedException {
        int blocks = (int) Math.min(totalBlocks, ((long) nodes * Answers.NODE_BYTES + BLOCK - 1) / BLOCK);
        budget.acquire(blocks);
        long now = reserved.addAndGet(blocks);
        peak.accumulateAndGet(now, Math::max);
        try {
            return work.get();
        } finally {
            reserved.addAndGet(-blocks);
            budget.release(blocks);
        }
    }

    /** 队里的一件事:优先级先小后大,同优先级先来先走。 */
    private record Task(long priority, long order, Runnable body) implements Runnable, Comparable<Task> {

        @Override
        public void run() {
            body.run();
        }

        @Override
        public int compareTo(Task other) {
            int byPriority = Long.compare(priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(order, other.order);
        }
    }
}
