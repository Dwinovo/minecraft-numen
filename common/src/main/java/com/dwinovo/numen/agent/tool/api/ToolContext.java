package com.dwinovo.numen.agent.tool.api;

/**
 * Per-call framework plumbing for a server-side tool that produces a body task.
 * It carries the two values such a tool can't make up itself — the originating
 * {@code tool_call} id (which must ride through to the result) and the current
 * game time (the basis for a deadline).
 *
 * <p>A query / client tool never needs this; only code that builds a task
 * record uses a {@code ToolContext}.
 */
public record ToolContext(String toolCallId, long gameTime) {

    /** Absolute deadline game-tick for a task that should run at most {@code ticks} ticks. */
    public long deadline(long ticks) {
        return gameTime + ticks;
    }
}
