package com.dwinovo.numen.pathing.search;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 派发出去、还在工作线程上跑的一次搜索或查询。发起方每刻 {@link #poll} 一次;叫停后搜索在展开下一个节点前退出,
 * 结论是"被叫停"。
 */
public final class Pending<T> {

    private final CompletableFuture<T> future;
    private final AtomicBoolean cancelled;

    Pending(CompletableFuture<T> future, AtomicBoolean cancelled) {
        this.future = future;
        this.cancelled = cancelled;
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
}
