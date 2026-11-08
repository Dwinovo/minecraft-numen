package com.dwinovo.numen.pathing.search;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 派发出去、还在工作线程上跑的一次搜索或查询。发起方每刻 {@link #poll} 一次;叫停后跑着的搜索在展开下一个节点前退出,
 * 结论是"被叫停";还在队里排着的从队里拿走。叫停之后不再 {@link #poll}。跑完之后,它在队里等了多久、在工作线程上跑了多久,是 {@link #queuedNanos} 与 {@link #ranNanos}。
 */
public final class Pending<T> {

    private final CompletableFuture<T> future;
    private final AtomicBoolean cancelled;
    private final Clock clock;
    /** 还在队里排着时把它从队里拿走。 */
    private final Runnable dequeue;

    Pending(CompletableFuture<T> future, AtomicBoolean cancelled, Clock clock, Runnable dequeue) {
        this.future = future;
        this.cancelled = cancelled;
        this.clock = clock;
        this.dequeue = dequeue;
    }

    /** 跑完了就交出结论,没跑完为 null。搜索里抛出的错原样抛给发起方。 */
    public T poll() {
        return future.isDone() ? future.join() : null;
    }

    /** 叫停:跑着的在展开下一个节点前退出;还在队里排着的从队里拿走,不再跑,也就没有结论。 */
    public void cancel() {
        cancelled.set(true);
        dequeue.run();
    }

    /** 从派发到工作线程开始跑,在队里等了多少纳秒;跑完之后才有意义。 */
    public long queuedNanos() {
        return clock.started - clock.submitted;
    }

    /** 在工作线程上跑了多少纳秒;跑完之后才有意义。 */
    public long ranNanos() {
        return clock.finished - clock.started;
    }

    /** 在工作线程上跑的这段时间里,这个线程用掉的 CPU 纳秒;和 {@link #ranNanos} 的比远小于 1,就是它在等锁或被别的线程挤掉了。JVM 不支持线程 CPU 计时为 -1。 */
    public long cpuNanos() {
        return clock.cpuStart < 0 || clock.cpuEnd < 0 ? -1 : clock.cpuEnd - clock.cpuStart;
    }

    /** 跑的这段时间里全进程 JIT 编译花的毫秒(冷启动时大);JVM 不报告为 -1。 */
    public long jitMillis() {
        return clock.jitStart < 0 ? -1 : clock.jitEnd - clock.jitStart;
    }

    /** 跑的这段时间里全进程垃圾回收停顿的毫秒。 */
    public long gcMillis() {
        return clock.gcEnd - clock.gcStart;
    }

    /** 一件活的三个时刻:派发、开跑、跑完,以及开跑与跑完时的线程 CPU、JIT、回收读数(都在工作线程上读)。 */
    static final class Clock {
        private final long submitted = System.nanoTime();
        private volatile long started;
        private volatile long finished;
        private volatile long cpuStart;
        private volatile long cpuEnd;
        private volatile long jitStart;
        private volatile long jitEnd;
        private volatile long gcStart;
        private volatile long gcEnd;

        <T> T time(Supplier<T> job) {
            cpuStart = JvmLoad.threadCpuNanos();
            jitStart = JvmLoad.jitMillis();
            gcStart = JvmLoad.gcMillis();
            started = System.nanoTime();
            try {
                return job.get();
            } finally {
                finished = System.nanoTime();
                cpuEnd = JvmLoad.threadCpuNanos();
                jitEnd = JvmLoad.jitMillis();
                gcEnd = JvmLoad.gcMillis();
            }
        }
    }
}
