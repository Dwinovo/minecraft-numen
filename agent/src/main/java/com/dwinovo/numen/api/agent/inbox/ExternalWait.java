package com.dwinovo.numen.api.agent.inbox;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 外接大脑的长等待:停靠在收件箱上,直到有会叫醒她的条目({@link EventTypes.Delivery#wakes})就整批交出。
 *
 * <p>叫不叫醒读的是内脑开 run 的同一条判据(投递档的声明),这里不另写一份:捎带的条目(AMBIENT、非急件的 NOTICE)
 * 不单独叫醒,等别的事叫醒、或调用方到点清扫时一起交。取件只走 {@link EventQueue#takeRenderedText} 这一个口,
 * 谁先取到谁拿走,不会交两遍。
 *
 * <p><b>取走的批次一定到调用方手里。</b>等待者要么在叫醒时取走批次,要么已经撤销,二者互斥,由停靠自己的锁裁决:
 * 调用方的期限到了去撤销,撤销拿到的是"叫醒恰好抢先取走的批次"(没有就是 {@code null},货还在队里)。
 * 取件不会和交付脱节,期限与叫醒撞在同一刻也不会丢事件。
 *
 * <p>线程:{@link #park} 要在持有队列的线程上调(队列没有锁);撤销可在任意线程调。{@link #parkOn} 把登记放到队列的线程上,
 * 任意线程都能调。
 */
public final class ExternalWait {

    private ExternalWait() {}

    /**
     * @param batch  叫醒后取到的整批事件;到点没有人叫就一直不完成
     * @param cancel 撤销停靠(任意线程、可重复调),返回叫醒已经为这个等待者取走的批次,没有取走是 {@code null}。
     *               调方没从 {@code batch} 读到批次时,以这里返回的为准
     */
    public record Parked(CompletableFuture<String> batch, Supplier<String> cancel) {}

    /** 先查后登记在同一次调用里完成,"队空"与"登记"之间没有缝隙让一条溜过去。 */
    public static Parked park(EventQueue queue, LongSupplier clock) {
        CompletableFuture<String> f = new CompletableFuture<>();
        Object lock = new Object();
        boolean[] closed = new boolean[1];
        String[] claimed = new String[1];
        Runnable[] hook = new Runnable[1];
        hook[0] = () -> {
            synchronized (lock) {
                if (closed[0]) {   // 撤销过的:顺手摘掉自己
                    queue.removeWakeListener(hook[0]);
                    return;
                }
                String batch = queue.takeRenderedText(clock.getAsLong());
                if (batch == null) {   // 别的取件人抢先了就继续挂着,等下一声
                    return;
                }
                closed[0] = true;
                claimed[0] = batch;
                queue.removeWakeListener(hook[0]);
                f.complete(batch);
            }
        };
        queue.addWakeListener(hook[0]);
        if (queue.hasWaking()) {
            hook[0].run();
        }
        return new Parked(f, () -> {
            synchronized (lock) {
                closed[0] = true;
                return claimed[0];
            }
        });
    }

    /**
     * 登记放到队列所在的线程上做,调用线程不必是那一个。登记还没轮到执行时撤销,登记就不再做;
     * 登记之后撤销,撤销的是登记出来的那个停靠。{@code register} 在队列的线程上调,没有可停靠的对象就返回
     * {@code null}(批次直接是 {@code null})。
     */
    public static Parked parkOn(Executor queueThread, Supplier<Parked> register) {
        CompletableFuture<String> f = new CompletableFuture<>();
        Object lock = new Object();
        boolean[] withdrawn = new boolean[1];
        Parked[] registered = new Parked[1];
        queueThread.execute(() -> {
            synchronized (lock) {
                if (withdrawn[0]) {
                    return;
                }
                Parked parked = register.get();
                if (parked == null) {
                    f.complete(null);
                    return;
                }
                registered[0] = parked;
                parked.batch().thenAccept(f::complete);
            }
        });
        return new Parked(f, () -> {
            synchronized (lock) {
                withdrawn[0] = true;
                return registered[0] == null ? null : registered[0].cancel().get();
            }
        });
    }
}
