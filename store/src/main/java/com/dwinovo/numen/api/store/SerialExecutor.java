package com.dwinovo.numen.api.store;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 把任务一个接一个交给 {@code delegate} 去跑:同一时刻至多一个在跑,先交的先跑。一段状态只许在一个执行体上读写的东西
 * (一段程序、一份日志的写)靠它不加锁:谁在什么线程上交任务都行,任务之间有先后关系(happens-before)。队里空了不占线程,下次来任务再
 * 起一个,所以不用关。
 *
 * <p>任务抛出的异常不由这里收:交给跑它的线程的未捕获异常处理器,后面的任务照常跑。
 */
public final class SerialExecutor implements Executor {

    private final Executor delegate;
    private final Queue<Runnable> queue = new ArrayDeque<>();
    private boolean running;

    public SerialExecutor(Executor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable task) {
        synchronized (this) {
            queue.add(task);
            if (running) {
                return;
            }
            running = true;
        }
        delegate.execute(this::drain);
    }

    /**
     * 等到交进来的任务都跑完;{@code timeout} 之内等到了返回 true,没等到返回 false。给停机时"把还没写下去的写完"用。
     */
    public boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (this) {
            while (running) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
            return true;
        }
    }

    private void drain() {
        while (true) {
            Runnable task;
            synchronized (this) {
                task = queue.poll();
                if (task == null) {
                    running = false;
                    notifyAll();
                    return;
                }
            }
            try {
                task.run();
            } catch (Throwable broke) {
                Thread thread = Thread.currentThread();
                thread.getUncaughtExceptionHandler().uncaughtException(thread, broke);
            }
        }
    }
}
