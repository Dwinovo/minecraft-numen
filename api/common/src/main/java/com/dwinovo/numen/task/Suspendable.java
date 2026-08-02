package com.dwinovo.numen.task;

/**
 * A task whose physical execution can be paused and resumed WITHOUT losing its
 * logical state — the contract the scheduler needs to preempt an LLM task for a
 * higher-priority survival chain and later hand control back.
 *
 * <p>{@link #suspend()} must release the BODY (halt movement, drop sneak, stop a
 * held click) but keep every logical field (nav plan, dig progress, phase,
 * blacklist) intact; {@link #resume()} lets the task re-drive from where it was on
 * its next {@code tick()}. {@code AbstractCompanionTask} implements this; the
 * scheduler ({@code LlmTaskChain}) calls it via {@code instanceof} so a task that
 * doesn't implement it simply isn't suspended (it just keeps its inputs until the
 * chain regains control), never a compile dependency on the base class.
 */
public interface Suspendable {
    /** Preempted: release the body, keep logical state. */
    void suspend();

    /** Regained control: nothing required by default — the next tick re-drives. */
    default void resume() {}
}
