package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.task.chain.ArmorChain;
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
 * → food → mlg → armor → {@link LlmTaskChain}. In Stage 1 the survival chains are
 * dormant stubs, so the LLM chain is the only one that ever wins — behavior is
 * identical to the pre-refactor single-task dispatcher. When survival chains go
 * live, a spike preempts the LLM task (its body is released via
 * {@link LlmTaskChain#onInterrupt}, its deadline frozen via
 * {@link LlmTaskChain#freezeTick}) and it resumes when the spike subsides.
 */
final class CompanionBrain {

    final TaskQueue queue = new TaskQueue();
    /** Shared survival diary: survival chains write episode lines at their completion
     *  edges; the LLM chain drains them into the next tool result's message — the model
     *  is informed of what its body did autonomously, never consulted. */
    private final SurvivalJournal journal = new SurvivalJournal();
    final LlmTaskChain llm = new LlmTaskChain(queue, journal);

    private final List<TaskChain> chains = List.of(
            new UnstuckChain(),
            new MobDefenseChain(journal),
            new FoodChain(journal),
            new MLGChain(journal),
            new ArmorChain(journal),
            llm);

    /** Last tick's winner, so we can fire {@code onInterrupt} exactly on the switching edge. */
    private TaskChain running;

    /** Quiet ticks before an IDLE body's journal is flushed as a non-urgent {@code <event>}
     *  (rides the owner's next message — no extra LLM call). ~5s aggregates back-to-back
     *  episodes (two zombies = one event, not two). */
    private static final int JOURNAL_IDLE_FLUSH_TICKS = 100;
    private int journalQuietTicks;
    private int lastJournalSize;

    /**
     * Idle-path outlet for the survival diary: with no task running or queued, there is
     * no tool result to ride, so after a quiet spell the accumulated episodes ship as a
     * non-urgent {@code <event>} — queued client-side and spliced into the owner's NEXT
     * message, exactly like a dimension-change event. The model is informed, not woken.
     */
    private void flushIdleJournal(NumenPlayer companion) {
        if (journal.isEmpty()) {
            journalQuietTicks = 0;
            lastJournalSize = 0;
            return;
        }
        int size = journal.size();
        if (size != lastJournalSize) {   // a new episode just landed — restart the quiet clock
            lastJournalSize = size;
            journalQuietTicks = 0;
            return;
        }
        if (++journalQuietTicks < JOURNAL_IDLE_FLUSH_TICKS) return;
        if (companion.resolveOwnerPlayer() == null) return;   // no client to queue on — keep holding
        // One <event> PER episode, as siblings (the protocol's existing grammar — queued
        // events of any kind already splice in side by side, unwrapped; the UI's tag
        // stripper matches exactly this shape).
        StringBuilder xml = new StringBuilder();
        for (String line : journal.drain()) {
            xml.append("<event kind=\"survival\">while idle, you ").append(line).append("</event>");
        }
        com.dwinovo.numen.entity.Companions.emitEvent(companion, xml.toString(), false);
        journalQuietTicks = 0;
        lastJournalSize = 0;
    }

    void tick(NumenPlayer companion) {
        TaskChain best = ChainScheduler.select(chains, companion);

        if (best == null) {
            // Everything dormant (idle body). Release whoever held control, then
            // finalize + drain — a record cancelled out-of-band must still ship.
            if (running != null) {
                running.onInterrupt(companion);
                running = null;
            }
            flushIdleJournal(companion);
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

        // A chain holds the body (fighting / eating / working) — the idle-flush quiet
        // clock restarts; episodes written mid-activity ride the next channel that opens.
        journalQuietTicks = 0;

        best.tick(companion);

        // Finalize EVERY tick, not just when llm wins: an owner Stop (cancelFor)
        // marks the record terminal out-of-band, and the client's serial
        // ToolDispatcher is wedged until that single result ships — even while a
        // survival chain holds the body. (A no-op when llm.tick already finalized.)
        llm.finalizeTerminal();
        llm.drainResults(companion);
    }
}
