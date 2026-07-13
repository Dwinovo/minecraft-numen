package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.task.chain.FoodChain;
import com.dwinovo.numen.core.task.chain.MLGChain;
import com.dwinovo.numen.core.task.chain.MobDefenseChain;
import com.dwinovo.numen.core.task.chain.UnstuckChain;
import com.dwinovo.numen.entity.NumenPlayer;

import java.util.List;

/**
 * One companion body's scheduler — the per-UUID value {@code CompanionTickDispatcher}
 * keeps. Replaces the old two static maps (a {@code TaskQueue} + a single
 * {@code Running} task) with an ordered chain list; each server tick it ticks ONLY
 * the highest-priority active chain (AltoClef {@code TaskRunner}), then always
 * drains completed LLM results.
 *
 * <p>Chain order (priority tie-break, highest-intent first): unstuck → mob-defense
 * → food → mlg → {@link LlmTaskChain}. In Stage 1 the four survival chains are
 * dormant stubs, so the LLM chain is the only one that ever wins — behavior is
 * identical to the pre-refactor single-task dispatcher. When survival chains go
 * live, a spike preempts the LLM task (its body is released via
 * {@link LlmTaskChain#onInterrupt}, its deadline frozen via
 * {@link LlmTaskChain#freezeTick}) and it resumes when the spike subsides.
 */
final class CompanionBrain {

    final TaskQueue queue = new TaskQueue();
    final LlmTaskChain llm = new LlmTaskChain(queue);

    private final List<TaskChain> chains = List.of(
            new UnstuckChain(),
            new MobDefenseChain(),
            new FoodChain(),
            new MLGChain(),
            llm);

    /** Last tick's winner, so we can fire {@code onInterrupt} exactly on the switching edge. */
    private TaskChain running;

    void tick(NumenPlayer companion) {
        TaskChain best = ChainScheduler.select(chains, companion);

        if (best == null) {
            // Everything dormant (idle body). Release whoever held control, then drain.
            if (running != null) {
                running.onInterrupt(companion);
                running = null;
            }
            llm.drainResults(companion);
            return;
        }

        if (running != null && running != best) {
            running.onInterrupt(companion);
        }
        running = best;

        // A non-LLM (survival) chain holds the body this tick → the paused LLM task
        // must not burn its deadline.
        if (best != llm) {
            llm.freezeTick(companion);
        }

        best.tick(companion);
        llm.drainResults(companion);
    }
}
