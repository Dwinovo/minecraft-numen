package com.dwinovo.numen.core.task.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the rolling-window stuck detector — driven by a synthetic position stream. */
class UnstuckDetectorTest {

    private static final int WINDOW = 10;
    private static final double THRESHOLD = 0.75;

    @Test
    void notStuckBeforeWindowFills() {
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW - 1; i++) {
            d.record(5.0, 5.0, true);   // pinned in place, trying — but window not full yet
        }
        assertFalse(d.isStuck());
    }

    @Test
    void firesWhenTryingButPinnedForAFullWindow() {
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(5.0, 5.0, true);
        }
        assertTrue(d.isStuck());
    }

    @Test
    void idleBodyNeverFires() {
        // Pinned in place but NOT trying to move (idle): must not be flagged stuck.
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(5.0, 5.0, false);
        }
        assertFalse(d.isStuck());
    }

    @Test
    void movingBodyIsNotStuck() {
        // Trying and actually travelling (1 block/tick): clearly not stuck.
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(i, 0.0, true);
        }
        assertFalse(d.isStuck());
    }

    @Test
    void tinyJitterUnderThresholdStillCountsAsStuck() {
        // Vibrating within the disc (< 0.75 block) while pushing = wedged against geometry.
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(5.0 + (i % 2) * 0.1, 5.0, true);
        }
        assertTrue(d.isStuck());
    }

    @Test
    void resetClearsTheWindow() {
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(5.0, 5.0, true);
        }
        assertTrue(d.isStuck());
        d.reset();
        assertFalse(d.isStuck());   // window emptied; needs to refill before firing again
    }

    @Test
    void aFewIdleTicksWithinAnOtherwisePinnedWindowStillFires() {
        // tryingFraction is 0.8 by default → up to 2 of 10 ticks may be non-attempts.
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(5.0, 5.0, i >= 2);   // first two ticks idle, rest trying (8/10)
        }
        assertTrue(d.isStuck());
    }

    @Test
    void mostlyIdleWindowDoesNotFire() {
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(5.0, 5.0, i >= 5);   // only 5/10 trying — below the 0.8 fraction
        }
        assertFalse(d.isStuck());
    }
}
