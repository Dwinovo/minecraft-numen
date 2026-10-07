package com.dwinovo.numen.pathing.search;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 派发出去、还在工作线程上跑的一次搜索或查询。发起方每刻 {@link #poll} 一次;叫停后搜索在展开下一个节点前退出,
 * 结论是"被叫停"。跑完之后,它在队里等了多久、在工作线程上跑了多久,是 {@link #queuedNanos} 与 {@link #ranNanos}。
 */
public final class Pending<T> {

    private final CompletableFuture<T> future;
    private final AtomicBoolean cancelled;
    private final Clock clock;

    Pending(CompletableFuture<T> future, AtomicBoolean cancelled, Clock clock) {
        this.future = future;
        this.cancelled = cancelled;
        this.clock = clock;
    }

    /** 跑完了就交出结论,没跑完为 null。搜索里抛出的错原样抛给发起方。 */
    public T poll() {
        return future.isDone() ? future.join() : null;
    }

    /** 等它跑完并交出结论。 */
    public T join() {
        return future.join();
    }

    public void cancel() {
        cancelled.set(true);
    }

    /** 从派发到工作线程开始跑,在队里等了多少纳秒;跑完之后才有意义。 */
    public long queuedNanos() {
        return clock.started - clock.submitted;
    }

    /** 在工作线程上跑了多少纳秒;跑完之后才有意义。 */
    public long ranNanos() {
        return clock.finished - clock.started;
    }

    /** 一件活的三个时刻:派发、开跑、跑完。 */
    static final class Clock {
        private final long submitted = System.nanoTime();
        private volatile long started;
        private volatile long finished;

        <T> T time(Supplier<T> job) {
            started = System.nanoTime();
            try {
                return job.get();
            } finally {
                finished = System.nanoTime();
            }
        }
    }
}
