package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.task.base.CountedProgress;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Baseline / gained / remaining / done arithmetic for {@link CountedProgress}. */
class CountedProgressTest {

    @Test
    void baselineIsSnapshotAtConstruction() {
        AtomicInteger live = new AtomicInteger(7);
        CountedProgress p = new CountedProgress(3, live::get);

        assertEquals(0, p.gained());
        assertEquals(3, p.remaining());
        assertFalse(p.done());
    }

    @Test
    void gainedTracksDeltaAboveBaseline() {
        AtomicInteger live = new AtomicInteger(10);
        CountedProgress p = new CountedProgress(5, live::get);

        live.set(12);
        assertEquals(2, p.gained());
        assertEquals(3, p.remaining());
        assertFalse(p.done());
    }

    @Test
    void doneWhenTargetReached() {
        AtomicInteger live = new AtomicInteger(0);
        CountedProgress p = new CountedProgress(4, live::get);

        live.set(4);
        assertTrue(p.done());
        assertEquals(4, p.gained());
        assertEquals(0, p.remaining());
    }

    @Test
    void doneWhenTargetExceeded() {
        AtomicInteger live = new AtomicInteger(0);
        CountedProgress p = new CountedProgress(4, live::get);

        live.set(9);   // one block can yield several drops → overshoot
        assertTrue(p.done());
        assertEquals(9, p.gained());
        assertEquals(0, p.remaining());
    }

    @Test
    void gainedFlooredAtZeroIfTotalShrinks() {
        AtomicInteger live = new AtomicInteger(6);
        CountedProgress p = new CountedProgress(3, live::get);

        live.set(4);   // total dropped below baseline (e.g. items consumed)
        assertEquals(0, p.gained());
        assertEquals(3, p.remaining());
        assertFalse(p.done());
    }

    @Test
    void zeroTargetIsImmediatelyDone() {
        CountedProgress p = new CountedProgress(0, () -> 42);
        assertTrue(p.done());
        assertEquals(0, p.remaining());
    }

    @Test
    void reportsItsTarget() {
        CountedProgress p = new CountedProgress(11, () -> 0);
        assertEquals(11, p.target());
    }
}
