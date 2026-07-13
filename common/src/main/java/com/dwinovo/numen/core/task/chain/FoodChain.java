package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.task.TaskChain;
import com.dwinovo.numen.entity.NumenPlayer;

/**
 * Autonomous auto-eat survival chain. Spikes priority above the LLM task when the
 * companion is hungry (or hurt + slightly hungry) and holds food, drives an eat,
 * then drops back.
 *
 * <p><b>Stage 1 stub:</b> permanently dormant ({@link Float#NEGATIVE_INFINITY}).
 * Lane SURVIVAL fills in the hunger polling ({@code getFoodData().getFoodLevel()})
 * and the {@code AutoEatTask} in a later stage; wiring it here now keeps behavior
 * byte-identical to today while freezing the scheduler's chain list.
 */
public final class FoodChain implements TaskChain {

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
        return "food";
    }
}
