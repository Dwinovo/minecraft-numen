package com.dwinovo.numen.pathing.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.SearchView;

import net.minecraft.core.BlockPos;

/**
 * 一批题 × 规格的标准答案求解,出题({@link Generate})与单独重算答案({@link Answers})共用这一处。两档节点上限:先按第一档({@link #cap})
 * 解,撞上限的再用第二档({@link #largeCap})重解,第二档也撞上限的按生产权重试一个上界({@link Reference#upperBound})。
 * 并行度按每档上限各自算({@link Answers#solvers}):第二档一个求解器占的内存是第一档的四倍,同时跑的就少。
 */
final class Solver {

    /** 一条要解的:这一块区域的快照、成本模型、起点与目标。 */
    record Job(SearchView whole, CostModel model, BlockPos start, Goal goal) {}

    /**
     * 一条解完的结论。
     *
     * @param tier   用的是第几档上限(1 或 2)
     * @param millis 这条自己用的墙钟(所有档加起来)
     */
    record Outcome(Reference.Solved solved, int tier, long millis) {}

    private final int cap;
    private final int largeCap;

    Solver(int cap, int largeCap) {
        this.cap = cap;
        this.largeCap = largeCap;
    }

    /** 默认的两档:{@link Reference#NODE_CAP} 与 {@link Reference#LARGE_NODE_CAP}。 */
    static Solver standard() {
        return new Solver(Reference.NODE_CAP, Reference.LARGE_NODE_CAP);
    }

    int cap() {
        return cap;
    }

    int largeCap() {
        return largeCap;
    }

    /**
     * 解 {@code jobs},按输入的顺序交回;每条一解完就调 {@code done}(在工作线程上,同一时刻只有一个调用)。
     */
    List<Outcome> solve(List<Job> jobs, BiConsumer<Integer, Outcome> done) {
        Outcome[] out = new Outcome[jobs.size()];
        ExecutorService first = Executors.newFixedThreadPool(Answers.solvers(cap));
        List<Integer> harder = new ArrayList<>();
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < jobs.size(); i++) {
                int index = i;
                futures.add(first.submit(() -> {
                    long t0 = System.nanoTime();
                    Job job = jobs.get(index);
                    Reference.Solved s = Reference.solve(job.whole(), job.model(), job.start(), job.goal(), cap);
                    Outcome o = new Outcome(s, 1, (System.nanoTime() - t0) / 1_000_000);
                    out[index] = o;
                    if (s.status() == Answers.Status.TOO_HARD) {
                        return index;
                    }
                    synchronized (done) {
                        done.accept(index, o);
                    }
                    return -1;
                }));
            }
            for (Future<Integer> f : futures) {
                int index = get(f);
                if (index >= 0) {
                    harder.add(index);
                }
            }
        } finally {
            first.shutdownNow();
        }
        if (!harder.isEmpty()) {
            ExecutorService second = Executors.newFixedThreadPool(Answers.solvers(largeCap));
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int index : harder) {
                    futures.add(second.submit(() -> {
                        long t0 = System.nanoTime();
                        Job job = jobs.get(index);
                        Reference.Solved s = Reference.solve(job.whole(), job.model(), job.start(), job.goal(), largeCap);
                        if (s.status() == Answers.Status.TOO_HARD) {
                            s = Reference.upperBound(job.whole(), job.model(), job.start(), job.goal(), largeCap, s);
                        }
                        Outcome o = new Outcome(s, 2, out[index].millis() + (System.nanoTime() - t0) / 1_000_000);
                        out[index] = o;
                        synchronized (done) {
                            done.accept(index, o);
                        }
                    }));
                }
                for (Future<?> f : futures) {
                    get(f);
                }
            } finally {
                second.shutdownNow();
            }
        }
        return List.of(out);
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }
}
