package com.dwinovo.numen.core.task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The body's narrative outlet — the ONE collection point for everything the body
 * does on its own (instinct episodes, preemption stories, wardrobe changes), and
 * the ONLY core-domain producer of ambient events. Constitution §4 ("BodyLog
 * 双轨路由"): two rails share one bounded queue.
 *
 * <ul>
 *   <li><b>Busy rail</b> — an LLM task is running or pending: {@link #report}
 *       queues the line and it rides that task's tool-result message tail
 *       ("[meanwhile ...]", see {@code LlmTaskChain#withSurvivalNotes}) — D1,
 *       zero extra calls;</li>
 *   <li><b>Idle rail</b> — no LLM task: {@link #report} flushes the whole queue
 *       IMMEDIATELY as one non-urgent {@code <event kind="body_log">} — C2,
 *       queued client-side and spliced into the next owner-driven turn. Never
 *       urgent: the model is informed, never woken.</li>
 * </ul>
 *
 * <p><b>Single drain</b>: both rails consume the same queue — whichever ships
 * first takes the entries, nothing is delivered twice. <b>Fallback flush</b>: a
 * task that terminates with NO result (death drop) has no D1 tail for its queued
 * entries to ride, so they transfer to the ambient rail via {@link #flushAmbient}.
 *
 * <p>Bounded ring: at most {@link #MAX_ENTRIES} lines accumulate (oldest dropped),
 * so no single message is ever flooded. Tick-thread only, like all task-layer
 * state. Pure JDK on purpose — the routing core is headless-testable
 * ({@code BodyLogTest}); the Minecraft transport hides behind {@link AmbientSink}.
 */
public final class BodyLog {

    /**
     * Idle-rail transport. Returns {@code true} when the event was handed to a
     * client; {@code false} = nobody can receive right now (owner offline) — the
     * entries stay queued and a later flush retries. The production wiring is
     * {@code Companions.emitEvent(companion, xml, false)}; this interface
     * deliberately has no urgency parameter — a body diary informs, never wakes
     * (urgent=false is constitutional law, §4).
     */
    public interface AmbientSink {
        boolean tryEmit(String xml);
    }

    static final int MAX_ENTRIES = 6;

    private final ArrayDeque<String> entries = new ArrayDeque<>();
    /** "Is a tool result coming that can carry the queue?" — injected by
     *  {@code CompanionBrain} (reads {@code LlmTaskChain.hasWork()}) to avoid a
     *  structural BodyLog → LlmTaskChain dependency cycle. */
    private final BooleanSupplier llmTaskActive;
    private final AmbientSink ambientSink;

    public BodyLog(BooleanSupplier llmTaskActive, AmbientSink ambientSink) {
        this.llmTaskActive = llmTaskActive;
        this.ambientSink = ambientSink;
    }

    /**
     * Record one completed body episode (one line, no trailing punctuation) and
     * route it: LLM task active → queue, rides that task's result; idle → the
     * whole queue flushes NOW as one ambient event.
     */
    public void report(String line) {
        if (line == null || line.isBlank()) return;
        if (entries.size() >= MAX_ENTRIES) {
            entries.removeFirst();
        }
        entries.addLast(line);
        if (!llmTaskActive.getAsBoolean()) {
            flushAmbient();
        }
    }

    /**
     * Idle-rail outlet + fallback flush: package every queued entry into ONE
     * non-urgent {@code <event kind="body_log">} and hand it to the sink. Sink
     * refuses (owner offline) → entries are kept for a later retry. Also called
     * directly on the no-result termination path (death drop), so entries that
     * were waiting on a task result still reach the brain.
     */
    public void flushAmbient() {
        if (entries.isEmpty()) return;
        String xml = "<event kind=\"body_log\">your body handled on its own: "
                + String.join("; ", entries) + "</event>";
        if (ambientSink.tryEmit(xml)) {
            entries.clear();
        }
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /** Busy-rail drain: all entries in order, clearing the queue (single drain —
     *  whatever a result takes, the ambient rail never re-sends). */
    public List<String> drain() {
        List<String> out = new ArrayList<>(entries);
        entries.clear();
        return out;
    }
}
