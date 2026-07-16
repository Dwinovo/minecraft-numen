package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.task.CompanionTask;

import com.dwinovo.numen.core.task.base.RecoveryLadder;
import com.dwinovo.numen.core.task.base.RecoveryLadder.Rung;
import com.dwinovo.numen.task.TaskResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for {@link RecoveryLadder} — no Minecraft: rung strategies are fake
 * {@link Supplier}s of a trivial {@link CompanionTask}, and failure streams are
 * synthetic {@link FailureType}s.
 */
class RecoveryLadderTest {

    /** A do-nothing task; identity is all the ladder tests care about. */
    private static final class FakeTask implements CompanionTask {
        @Override public void start() {}
        @Override public TaskState tick() { return TaskState.RUNNING; }
        @Override public TaskResult buildResult(TaskState finalState) { return null; }
    }

    /** A strategy that counts how many tasks it built and returns a fresh one each time. */
    private static final class CountingStrategy implements Supplier<CompanionTask> {
        final AtomicInteger builds = new AtomicInteger();
        @Override public CompanionTask get() {
            builds.incrementAndGet();
            return new FakeTask();
        }
    }

    @Test
    void currentBuildsLazilyAndCaches() {
        CountingStrategy s = new CountingStrategy();
        RecoveryLadder ladder = RecoveryLadder.of(
                new Rung(s, Set.of(FailureType.NO_PATH), 1));

        assertEquals(0, s.builds.get());          // nothing built until asked
        CompanionTask first = ladder.current();
        assertSame(first, ladder.current());      // cached across per-tick calls
        assertEquals(1, s.builds.get());
    }

    @Test
    void retriesSameRungUntilMaxAttemptsThenAdvances() {
        CountingStrategy rung0 = new CountingStrategy();
        CountingStrategy rung1 = new CountingStrategy();
        RecoveryLadder ladder = RecoveryLadder.of(
                new Rung(rung0, Set.of(FailureType.OCCLUDED), 3),   // up to 3 tries
                new Rung(rung1, Set.of(FailureType.OCCLUDED), 1));

        CompanionTask t1 = ladder.current();
        assertEquals(0, ladder.currentRung());
        assertEquals(1, ladder.currentAttempt());

        // Attempt 1 failed (handled) → retry same rung, rebuilt.
        assertTrue(ladder.advance(FailureType.OCCLUDED));
        assertEquals(0, ladder.currentRung());
        assertEquals(2, ladder.currentAttempt());
        CompanionTask t2 = ladder.current();
        assertNotSame(t1, t2);
        assertEquals(2, rung0.builds.get());

        // Attempt 2 failed → retry (attempt 3, the max).
        assertTrue(ladder.advance(FailureType.OCCLUDED));
        assertEquals(0, ladder.currentRung());
        assertEquals(3, ladder.currentAttempt());
        ladder.current();
        assertEquals(3, rung0.builds.get());

        // Attempt 3 failed → rung0 exhausted its attempts → advance to rung1.
        assertTrue(ladder.advance(FailureType.OCCLUDED));
        assertEquals(1, ladder.currentRung());
        assertEquals(1, ladder.currentAttempt());
        ladder.current();
        assertEquals(1, rung1.builds.get());
    }

    @Test
    void unhandledCauseFallsThroughToTheNextMatchingRung() {
        CountingStrategy rung0 = new CountingStrategy();
        CountingStrategy rung1 = new CountingStrategy();
        RecoveryLadder ladder = RecoveryLadder.of(
                new Rung(rung0, Set.of(FailureType.NO_PATH), 3),      // does NOT handle OUT_OF_REACH
                new Rung(rung1, Set.of(FailureType.OUT_OF_REACH), 2));

        ladder.current();
        assertEquals(0, ladder.currentRung());

        // rung0 can't handle OUT_OF_REACH even though it has attempts left → skip to rung1.
        assertTrue(ladder.advance(FailureType.OUT_OF_REACH));
        assertEquals(1, ladder.currentRung());
        assertEquals(1, ladder.currentAttempt());
    }

    @Test
    void advanceSkipsRungsThatDoNotHandleTheCause() {
        RecoveryLadder ladder = RecoveryLadder.of(
                new Rung(new CountingStrategy(), Set.of(FailureType.NO_PATH), 1),
                new Rung(new CountingStrategy(), Set.of(FailureType.HAZARD), 1),      // skipped
                new Rung(new CountingStrategy(), Set.of(FailureType.OCCLUDED), 1));   // this one handles it

        ladder.current();
        assertTrue(ladder.advance(FailureType.OCCLUDED));
        assertEquals(2, ladder.currentRung());   // jumped straight past the HAZARD-only rung
    }

    @Test
    void exhaustsWhenNoRemainingRungHandlesTheCause() {
        RecoveryLadder ladder = RecoveryLadder.of(
                new Rung(new CountingStrategy(), Set.of(FailureType.NO_PATH), 1),
                new Rung(new CountingStrategy(), Set.of(FailureType.NO_PATH), 1));

        ladder.current();
        // A prerequisite gap no rung handles → give up immediately.
        assertFalse(ladder.advance(FailureType.NO_MATERIAL));
        assertTrue(ladder.exhausted());
        assertNull(ladder.current());
    }

    @Test
    void terminalGiveUpAfterLastRung() {
        RecoveryLadder ladder = RecoveryLadder.of(
                new Rung(new CountingStrategy(), Set.of(FailureType.NO_PATH), 1));

        ladder.current();
        // Only rung fails on its single attempt with a handled cause, but has no
        // attempts left and there is no later rung → exhausted.
        assertFalse(ladder.advance(FailureType.NO_PATH));
        assertTrue(ladder.exhausted());
        assertNull(ladder.current());

        // Advancing again on an already-exhausted ladder stays false.
        assertFalse(ladder.advance(FailureType.NO_PATH));
    }
}
