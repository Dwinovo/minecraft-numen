package com.dwinovo.numen.core.scaffold;

/** Bounded completion policy for one explicit temporary-scaffold cleanup sweep. */
public final class TemporaryScaffoldReclaimProgress {
    public enum State {
        RUNNING,
        COMPLETE,
        BLOCKED,
        STALLED
    }

    private static final long MAX_NO_PROGRESS_TICKS = 600L;

    private final int initialCount;
    private int lowestRemaining;
    private long lastRemovalAt;

    public TemporaryScaffoldReclaimProgress(int initialCount, long startedAt) {
        this.initialCount = Math.max(0, initialCount);
        this.lowestRemaining = this.initialCount;
        this.lastRemovalAt = startedAt;
    }

    public State observe(int remaining, boolean actionable, long now) {
        int boundedRemaining = Math.max(0, remaining);
        if (boundedRemaining < lowestRemaining) {
            lowestRemaining = boundedRemaining;
            lastRemovalAt = now;
        }
        if (boundedRemaining == 0) {
            return State.COMPLETE;
        }
        if (!actionable) {
            return State.BLOCKED;
        }
        if (now - lastRemovalAt >= MAX_NO_PROGRESS_TICKS) {
            return State.STALLED;
        }
        return State.RUNNING;
    }

    public int reclaimed(int remaining) {
        return Math.max(0, initialCount - Math.max(0, remaining));
    }

    public int initialCount() {
        return initialCount;
    }
}
