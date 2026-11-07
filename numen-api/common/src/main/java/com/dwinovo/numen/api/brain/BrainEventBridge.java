package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.agent.loop.LoopEvent;
import com.dwinovo.numen.api.agent.loop.RunEnd;
import com.dwinovo.numen.api.entity.CompanionEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 把一只同伴的循环里的 run 开收译成给插件的 {@link CompanionEvent#RUN_STARTED}、{@link CompanionEvent#RUN_ENDED},
 * 按刻攒下、在 {@link #flush} 里统一派发。内核发事件时正走在一步的中间,所以不当场交给插件:攒到
 * {@link CompanionLoops#tick} 里、内核两步之间(服务端主线程)再派,插件回调里调门是安全的。
 *
 * <p>一个循环一座桥(和 {@link FeedEncoder} 一样挂在 {@link CompanionLoops} 的登记项上),循环换新时桥随之换。
 * 只认 {@link LoopEvent} 里 run 的开与收;插件看到的载荷是 {@link CompanionEvent} 上只追加字段的 record,不是内核的封闭类型。
 *
 * <p>线程:服务端主线程。
 */
final class BrainEventBridge {

    private final UUID companion;
    private final List<LoopEvent> pending = new ArrayList<>();

    BrainEventBridge(CompanionLoop loop) {
        this.companion = loop.companion();
        loop.subscribe(this::on);
    }

    private void on(LoopEvent event) {
        if (event instanceof LoopEvent.RunStarted || event instanceof LoopEvent.RunEnded) {
            pending.add(event);
        }
    }

    /** 把攒下的 run 开收按发生的先后派给插件;订阅者出错的隔离在 {@link CompanionEvents#fire}。 */
    void flush() {
        if (pending.isEmpty()) {
            return;
        }
        List<LoopEvent> batch = List.copyOf(pending);
        pending.clear();
        for (LoopEvent event : batch) {
            if (event instanceof LoopEvent.RunStarted started) {
                CompanionEvents.fire(CompanionEvent.RUN_STARTED,
                        new CompanionEvent.RunStarted(companion, started.runId()));
            } else if (event instanceof LoopEvent.RunEnded ended) {
                CompanionEvents.fire(CompanionEvent.RUN_ENDED, ended(ended));
            }
        }
    }

    private CompanionEvent.RunEnded ended(LoopEvent.RunEnded ended) {
        return switch (ended.end()) {
            case RunEnd.Done done -> new CompanionEvent.RunEnded(companion, ended.runId(),
                    CompanionEvent.Outcome.DONE, "");
            case RunEnd.Failed failed -> new CompanionEvent.RunEnded(companion, ended.runId(),
                    CompanionEvent.Outcome.FAILED, failed.cause().code());
            case RunEnd.Halted halted -> new CompanionEvent.RunEnded(companion, ended.runId(),
                    CompanionEvent.Outcome.HALTED, halted.reason().cause(null).code());
        };
    }
}
