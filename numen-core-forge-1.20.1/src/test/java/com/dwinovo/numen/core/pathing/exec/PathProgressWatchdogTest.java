package com.dwinovo.numen.core.pathing.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathProgressWatchdogTest {
    @Test void warnsOnceAndReplansIndexThrashAtBackstop() {
        PathProgressWatchdog watchdog = new PathProgressWatchdog();
        for (int i = 1; i < PathProgressWatchdog.WARN_TICKS; i++) {
            assertEquals(PathProgressWatchdog.Signal.NONE, watchdog.tick(0, i % 3));
        }
        assertEquals(PathProgressWatchdog.Signal.WARN, watchdog.tick(0, 0));
        assertEquals(PathProgressWatchdog.Signal.NONE, watchdog.tick(0, 1));
        while (watchdog.ticksSinceProgress() < PathProgressWatchdog.REPLAN_TICKS - 1) {
            assertEquals(PathProgressWatchdog.Signal.NONE, watchdog.tick(0, 1));
        }
        assertEquals(PathProgressWatchdog.Signal.REPLAN, watchdog.tick(0, 0));
    }

    @Test void forwardProgressResetsEpisodeAndLongMoveUsesOwnTimeout() {
        PathProgressWatchdog watchdog = new PathProgressWatchdog();
        for (int i = 0; i < 70; i++) watchdog.tick(0, i);
        assertEquals(PathProgressWatchdog.Signal.NONE, watchdog.tick(1, 0));
        assertEquals(0, watchdog.ticksSinceProgress());
        for (int i = 0; i < PathProgressWatchdog.REPLAN_TICKS; i++) {
            PathProgressWatchdog.Signal signal = watchdog.tick(1, PathProgressWatchdog.WARN_TICKS);
            if (i == PathProgressWatchdog.WARN_TICKS - 1) {
                assertEquals(PathProgressWatchdog.Signal.WARN, signal);
            } else {
                assertEquals(PathProgressWatchdog.Signal.NONE, signal);
            }
        }
    }
}
