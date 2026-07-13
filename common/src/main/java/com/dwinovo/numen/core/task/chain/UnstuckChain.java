package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.task.TaskChain;
import com.dwinovo.numen.entity.NumenPlayer;

/**
 * Autonomous positional-recovery survival chain. Spikes priority when the body has
 * made no net movement while an LLM task is running (or the path executor reports
 * BOXED_IN), wanders/digs out, then drops back.
 *
 * <p><b>Stage 1 stub:</b> permanently dormant ({@link Float#NEGATIVE_INFINITY}).
 * Lane SURVIVAL fills in the stuck detection (rolling position-delta window) and
 * the {@code UnstuckTask} later.
 */
public final class UnstuckChain implements TaskChain {

    @Override
    public float getPriority(NumenPlayer companion) {
        return Float.NEGATIVE_INFINITY;   // dormant until Lane SURVIVAL lands
    }

    @Override
    public void tick(NumenPlayer companion) {
        // no-op stub
    }

    @Override
    public void onInterrupt(NumenPlayer companion) {
        // no-op stub
    }

    @Override
    public String name() {
        return "unstuck";
    }
}
