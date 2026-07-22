package com.dwinovo.numen.core.task.base;

import com.dwinovo.numen.core.task.FailureType;

/**
 * A cheap, side-effect-free "can this task even begin?" gate, checked once by
 * {@link AbstractCompanionTask#start()} before any body is driven.
 *
 * <p>Preconditions replace the ad-hoc fail-fast blocks each concrete task used to
 * open with (e.g. {@code BuildCompanionTask} rejecting an occupied target with
 * replacement off, {@code MineCompanionTask} rejecting an un-harvestable
 * request). Expressing them as a small ordered list keeps the "why can't I start"
 * diagnosis uniform: the FIRST precondition that reports a {@link Failure} decides
 * the task's terminal result, carrying both a model-facing message and a
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

    /** A precondition's verdict when it is NOT satisfied. */
    record Failure(String message, FailureType type) {}
}
