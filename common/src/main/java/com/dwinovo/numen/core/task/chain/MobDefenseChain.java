package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.task.TaskChain;
import com.dwinovo.numen.entity.NumenPlayer;

/**
 * Autonomous threat-response survival chain. Spikes priority above the LLM task
 * when the companion is being attacked / a hostile is close, drives fight-back or
 * flee, then drops back.
 *
 * <p><b>Stage 1 stub:</b> permanently dormant ({@link Float#NEGATIVE_INFINITY}).
 * Lane SURVIVAL fills in the threat polling ({@code hurtTime} / {@code
 * getLastHurtByMob} / a bounded hostile scan) and the fight/flee tasks later.
 */
public final class MobDefenseChain implements TaskChain {

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
        return "mob_defense";
    }
}
