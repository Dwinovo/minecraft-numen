package com.dwinovo.numen.agent.script;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * 把任务一个接一个交给 {@code delegate} 去跑:同一时刻至多一个在跑,先交的先跑。一段状态只许在一个执行体上读写的东西
 * ({@link Program})靠它不加锁:谁在什么线程上交任务都行,任务之间有先后关系(happens-before)。队里空了不占线程,下次来任务再
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

    private void drain() {
        while (true) {
            Runnable task;
            synchronized (this) {
                task = queue.poll();
                if (task == null) {
                    running = false;
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
