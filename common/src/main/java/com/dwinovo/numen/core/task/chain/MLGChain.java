package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.task.TaskChain;
import com.dwinovo.numen.entity.NumenPlayer;

/**
 * Autonomous fall-save (MLG water-bucket) survival chain. Spikes priority when the
 * body is falling with lethal fall distance and holds a water bucket / soft block,
 * places it under itself, then drops back.
 *
 * <p><b>Stage 1 stub:</b> permanently dormant ({@link Float#NEGATIVE_INFINITY}).
 * Lane SURVIVAL fills in the fall detection ({@code !onGround()} + fall distance)
 * and the bucket-place later.
 */
public final class MLGChain implements TaskChain {

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
        return "mlg";
    }
}
