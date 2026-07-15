package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.task.chain.BreathChain;
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
 * the highest-priority active chain (priority arbitration: every chain bids, one
 * winner drives the body), then always drains completed LLM results.
 *
 * <p>Chain order (priority tie-break, highest-intent first): unstuck → mob-defense
 * → food → mlg → breath → {@link LlmTaskChain}. A survival spike preempts the LLM
 * task (its body is released via {@link LlmTaskChain#onInterrupt}, its deadline
 * frozen via {@link LlmTaskChain#freezeTick}) and it resumes when the spike subsides.
 *
 * <p>This class also wires the {@link BodyLog} dual rail (constitution §4): chains
 * report body episodes into the log; the log asks {@link LlmTaskChain#hasWork}
 * (injected as a supplier — no structural cycle) to pick a rail, and its idle-rail
 * transport is this brain's {@link #tryEmitAmbient} — one non-urgent
 * {@code <event kind="body_log">} to the owner's client, never a wake.
 */
final class CompanionBrain {

    /**
     * Grace window before the MAINHAND intent pin auto-releases once the LLM
     * chain runs out of work (the 任务结束 edge, constitution §5 / point 11).
     * Debounced instead of a bare edge because the client dispatches tool calls
     * strictly serially — between two calls of one turn the chain is idle for
     * however long the model thinks; 30s comfortably outlives that gap while
     * still clearing a stale hand pin soon after the job truly ends.
     */
    private static final int HAND_PIN_GRACE_TICKS = 600;

    final TaskQueue queue = new TaskQueue();
    /** The body's narrative outlet: busy → rides the next tool result (D1 tail),
     *  idle → immediate non-urgent ambient event (C2). See {@link BodyLog}. */
    private final BodyLog bodyLog;
    final LlmTaskChain llm;

    private final List<TaskChain> chains;

    /** The body this brain is currently acting for — bound at every entry point
     *  that can trigger an ambient flush, read by {@link #tryEmitAmbient}. (The
     *  brain is keyed per companion UUID, but chains report without a companion
     *  argument, so the flush transport resolves the body through this field.) */
    private NumenPlayer body;

    /** Last tick's winner, so we can fire {@code onInterrupt} exactly on the switching edge. */
    private TaskChain running;

    /** Task-idle edge for the hand pin (pure counter; see {@link #HAND_PIN_GRACE_TICKS}). */
    private final com.dwinovo.numen.core.task.pin.HandPinRelease handPinRelease =
            new com.dwinovo.numen.core.task.pin.HandPinRelease(HAND_PIN_GRACE_TICKS);

    CompanionBrain() {
        // The method reference defers the llm read to call time, so construction
        // order is safe (a direct field read in a lambda trips definite assignment).
        this.bodyLog = new BodyLog(this::llmHasWork, this::tryEmitAmbient);
        this.llm = new LlmTaskChain(queue, bodyLog);
        this.chains = List.of(
                new UnstuckChain(),
                new MobDefenseChain(bodyLog),
                new FoodChain(bodyLog),
                new MLGChain(bodyLog),
                new BreathChain(bodyLog),
                llm);
    }

    /** {@link BodyLog}'s busy-rail predicate — "is a tool result coming that can
     *  carry the queue?" (see {@link LlmTaskChain#hasWork}). */
    private boolean llmHasWork() {
        return llm.hasWork();
    }

    /**
     * {@link BodyLog}'s idle-rail transport: ship the packaged {@code body_log}
     * event to the owner's client via the engine's public event channel.
     * {@code urgent=false} is constitutional law (§4) — a body diary informs the
     * next turn, it never wakes the brain. No owner online → refuse, so the log
     * keeps its entries and retries on a later flush.
     */
    private boolean tryEmitAmbient(String xml) {
        NumenPlayer companion = body;
        if (companion == null || companion.resolveOwnerPlayer() == null) return false;
        com.dwinovo.numen.entity.Companions.emitEvent(companion, xml, false);
        return true;
    }

    void tick(NumenPlayer companion) {
        body = companion;

        // 任务结束边沿 (constitution §5): the LLM chain has stayed workless past the
        // grace window — the explicit-hold session is over, the hand goes back to
        // the reflexes. Armor pins are untouched (their life is §5's four natural
        // endpoints); only MAINHAND is task-scoped.
        if (handPinRelease.tick(llm.hasWork())) {
            com.dwinovo.numen.core.task.pin.IntentPinsData.pinsFor(companion)
                    .unpin(com.dwinovo.numen.core.task.pin.IntentPins.SLOT_MAINHAND);
        }

        TaskChain best = ChainScheduler.select(chains, companion);

        if (best == null) {
            // Everything dormant (idle body). Release whoever held control, then
            // finalize + drain — a record cancelled out-of-band must still ship.
            if (running != null) {
                running.onInterrupt(companion);
                running = null;
            }
            // Idle retry for entries a refused flush left behind (the owner was
            // offline when they were reported) — a no-op when the log is empty.
            bodyLog.flushAmbient();
            llm.finalizeTerminal();
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

        // Finalize EVERY tick, not just when llm wins: an owner Stop (cancelFor)
        // marks the record terminal out-of-band, and the client's serial
        // ToolDispatcher is wedged until that single result ships — even while a
        // survival chain holds the body. (A no-op when llm.tick already finalized.)
        llm.finalizeTerminal();
        llm.drainResults(companion);
    }

    /**
     * Death path (via {@code CompanionTickDispatcher.clearActiveTask}): bind the
     * body so the ambient sink can reach its owner, then let the LLM chain drop
     * the running task and fallback-flush the {@link BodyLog} (constitution §4 —
     * a no-result termination strands the queued entries, they transfer to C2).
     */
    void dropActiveNoResult(NumenPlayer companion) {
        body = companion;
        // Death ends the task session — the task-scoped hand pin goes with it.
        com.dwinovo.numen.core.task.pin.IntentPinsData.pinsFor(companion)
                .unpin(com.dwinovo.numen.core.task.pin.IntentPins.SLOT_MAINHAND);
        llm.dropActiveNoResult();
    }
}
