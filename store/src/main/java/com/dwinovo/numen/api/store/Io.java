package com.dwinovo.numen.api.store;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 磁盘读写在哪个线程上做,由使用方给的执行器定;本模块不认识游戏线程,也不自己开线程。
 *
 * <p>写不抛:失败交给 {@code onError}(想记进哪份日志,调用方自己说了算),因为写是后台的事,
 * 没有谁在原地等着接异常。读有两种:直接 {@code read()} 在调用线程上读、失败抛 {@link StoreException};
 * {@code readAsync()} 交给执行器,失败是 future 的异常。
 *
 * <p>同一份文件的写必须按提交顺序执行,所以后台执行器要是串行的(单线程)。
 *
 * @param executor 跑磁盘任务的执行器
 * @param onError  写失败时的去处;可能在执行器的线程上被调用
 */
public record Io(Executor executor, Consumer<StoreException> onError) {

    public Io {
        if (executor == null || onError == null) {
            throw new IllegalArgumentException("Io 需要执行器和出错的去处");
        }
    }

    /** 在调用线程上直接做(使用方自己已经在合适的线程上,或单测)。 */
    public static Io direct(Consumer<StoreException> onError) {
        return new Io(Runnable::run, onError);
    }

    /** 把一次写交给执行器;{@link StoreException} 去 {@code onError}。给存储自己的写和"一条一个文件"的内容文件用。 */
    public void write(Runnable job) {
        executor.execute(() -> {
            try {
                job.run();
            } catch (StoreException e) {
                onError.accept(e);
            }
        });
    }

    /** 把一次读交给执行器。 */
    <T> CompletableFuture<T> read(Supplier<T> job) {
        CompletableFuture<T> result = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                result.complete(job.get());
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }
}
