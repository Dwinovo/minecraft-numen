package com.dwinovo.numen.core.pathing.exec;

/** Detects path-index thrashing without depending on Minecraft world state. */
final class PathProgressWatchdog {
    static final int WARN_TICKS = 60;
    static final int REPLAN_TICKS = 120;

    enum Signal { NONE, WARN, REPLAN }

    private int maxIndexReached;
    private int ticksSinceProgress;
    private boolean warned;

    Signal tick(int index, int ticksOnCurrent) {
        if (index > maxIndexReached) {
            maxIndexReached = index;
            ticksSinceProgress = 0;
            warned = false;
            return Signal.NONE;
        }
        ticksSinceProgress++;
        if (ticksSinceProgress >= REPLAN_TICKS && ticksOnCurrent < WARN_TICKS) {
            return Signal.REPLAN;
        }
        if (ticksSinceProgress >= WARN_TICKS && !warned) {
            warned = true;
            return Signal.WARN;
        }
        return Signal.NONE;
    }

    int ticksSinceProgress() {
        return ticksSinceProgress;
    }
}
