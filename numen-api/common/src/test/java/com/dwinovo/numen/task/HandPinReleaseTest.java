package com.dwinovo.numen.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the task-idle release edge of the MAINHAND pin: fires exactly
 * once, only after the grace window of workless ticks, and re-arms when work
 * returns — so the brief think-gaps between one turn's serial tool calls never
 * strip the pin mid-job.
 */
class HandPinReleaseTest {

    private static final int GRACE = 5;

    private final HandPinRelease edge = new HandPinRelease(GRACE);

    private boolean idleTicks(int n) {
        boolean fired = false;
        for (int i = 0; i < n; i++) {
            fired |= edge.tick(false);
        }
        return fired;
    }

    @Test
    void firesOnceAfterGraceIdle() {
        edge.tick(true);
        assertFalse(idleTicks(GRACE - 1));   // still inside the think-gap window
        assertTrue(edge.tick(false));        // grace elapsed → release
        assertFalse(idleTicks(100));         // never again this session
    }

    @Test
    void workInsideTheGraceWindowRearms() {
        edge.tick(true);
        assertFalse(idleTicks(GRACE - 1));
        edge.tick(true);                     // next tool call of the same turn arrived
        assertFalse(idleTicks(GRACE - 1));   // the countdown restarted
        assertTrue(edge.tick(false));
    }

    @Test
    void neverFiresWhileBusy() {
        for (int i = 0; i < GRACE * 3; i++) {
            assertFalse(edge.tick(true));
        }
    }

    @Test
    void newWorkSessionFiresAgain() {
        edge.tick(true);
        assertTrue(idleTicks(GRACE));        // session 1 released
        edge.tick(true);                     // a new task session starts
        assertFalse(idleTicks(GRACE - 1));
        assertTrue(edge.tick(false));        // session 2 releases too
    }

    @Test
    void firesFromColdStartAfterGrace() {
        // A brain that never had work still clears any stale persisted hand pin
        // once the grace passes (the "task session" it belonged to is long gone).
        assertTrue(idleTicks(GRACE));
    }
}
