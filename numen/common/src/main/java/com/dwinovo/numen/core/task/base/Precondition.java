package com.dwinovo.numen.core.task.base;

import com.dwinovo.numen.core.FailureType;

/**
 * A cheap, side-effect-free "can this task even begin?" gate, checked once before any body is driven: by
 * {@link AbstractCompanionTask#prepare} before the work is accepted (a failure is the call's error result, with no
 * task id), or by {@link AbstractCompanionTask#start} when nothing prepared the task (a synchronous action, a child
 * sub-goal).
 *
 * <p>Preconditions replace the ad-hoc fail-fast blocks each concrete task used to
 * open with (e.g. {@code BuildCompanionTask} rejecting an occupied target with
 * replacement off, {@code DigCompanionTask} rejecting an un-harvestable
 * request). Expressing them as a small ordered list keeps the "why can't I start"
 * diagnosis uniform: the FIRST precondition that reports a {@link Failure} decides
 * the refusal (or the task's terminal result), carrying both a model-facing message and a
 * {@link FailureType} the reactive layer can branch on.
 *
 * <p>A precondition is a PREREQUISITE check — the kinds of failure it emits
 * ({@link FailureType#NO_MATERIAL}, {@link FailureType#WRONG_TOOL}, …) are exactly
 * the "kick back to the LLM" categories: the deterministic layer must not silently
 * acquire what's missing, it reports and stops.
 */
public interface Precondition {

    /**
     * Evaluate the gate.
     *
     * @return {@code null} when satisfied (the task may start); otherwise the
     *         {@link Failure} to report as the task's terminal result.
     */
    Failure check();

    /**
     * A precondition's verdict when it is NOT satisfied.
     *
     * @param hint 能照抄的下一步(走过去的那一行);没有为 null
     */
    record Failure(String message, FailureType type, String hint) {

        public Failure(String message, FailureType type) {
            this(message, type, null);
        }
    }
}
