package com.dwinovo.numen.agent.tool.api;

/**
 * Per-call framework plumbing injected (by type) into a {@link NumenAction}
 * method that produces a body task. It carries the two values such a method
 * can't make up itself — the originating {@code tool_call} id (which must ride
 * through to the result) and the current game time (the basis for a deadline).
 *
 * <p>A query / client tool never needs this; only a method that builds a
 * task record declares a {@code ToolContext} parameter.
 */
public record ToolContext(String toolCallId, long gameTime) {

    /** Absolute deadline game-tick for a task that should run at most {@code ticks} ticks. */
    public long deadline(long ticks) {
        return gameTime + ticks;
    }
}
