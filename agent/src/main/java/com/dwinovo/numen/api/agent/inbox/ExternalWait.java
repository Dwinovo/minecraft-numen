package com.dwinovo.numen.api.agent.inbox;

import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * 外接大脑的长等待:停靠在收件箱上,直到有会叫醒她的条目({@link EventTypes.Delivery#wakes})就整批交出。
 *
 * <p>叫不叫醒读的是内脑开 run 的同一条判据(投递档的声明),这里不另写一份:捎带的条目(AMBIENT、非急件的 NOTICE)
 * 不单独叫醒,等别的事叫醒、或调用方到点清扫时一起交。取件只走 {@link EventQueue#takeRenderedText} 这一个口,
 * 谁先取到谁拿走,不会交两遍。
 *
 * <p>线程:队列没有锁,{@link #park} 与 {@code cancel} 都要在持有队列的线程上调。
 */
public final class ExternalWait {

    private ExternalWait() {}

    /**
     * @param batch  叫醒后取到的整批事件;到点没有人叫就一直不完成,由调用方的期限清扫兜底
     * @param cancel 撤掉停靠;完成后调也安全
     */
    public record Parked(CompletableFuture<String> batch, Runnable cancel) {}

    /** 先查后登记在同一次调用里完成,"队空"与"登记"之间没有缝隙让一条溜过去。 */
    public static Parked park(EventQueue queue, LongSupplier clock) {
        CompletableFuture<String> f = new CompletableFuture<>();
        if (queue.hasWaking()) {
            String now = queue.takeRenderedText(clock.getAsLong());
            if (now != null) {
                f.complete(now);
                return new Parked(f, () -> { });
            }
        }
        Runnable[] hook = new Runnable[1];
        hook[0] = () -> {
            String batch = queue.takeRenderedText(clock.getAsLong());
            if (batch != null) {   // 别的取件人抢先了就继续挂着,等下一声
                queue.removeWakeListener(hook[0]);
                f.complete(batch);
            }
        };
        queue.addWakeListener(hook[0]);
        return new Parked(f, () -> queue.removeWakeListener(hook[0]));
    }
}
