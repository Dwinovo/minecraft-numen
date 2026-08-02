package com.dwinovo.numen.task;

import com.dwinovo.numen.entity.NumenPlayer;

import java.util.List;

/**
 * The chain-selection rule, extracted as a pure function so
 * it can be unit-tested without a running Minecraft: from a list of chains, pick
 * the highest-priority one whose priority beats {@link Float#NEGATIVE_INFINITY}
 * (dormant), or {@code null} if every chain is dormant. Earlier chains win ties
 * (strict {@code >}), so the list order encodes the tie-break ranking.
 */
public final class ChainScheduler {

    private ChainScheduler() {}

    /** The winning chain for this tick, or {@code null} if all chains are dormant. */
    public static TaskChain select(List<TaskChain> chains, NumenPlayer companion) {
        TaskChain best = null;
        float bestPriority = Float.NEGATIVE_INFINITY;
        for (TaskChain chain : chains) {
            float priority = chain.getPriority(companion);
            if (priority > bestPriority) {
                bestPriority = priority;
                best = chain;
            }
        }
        return best;
    }
}
