package com.dwinovo.numen.task;

import com.dwinovo.numen.task.TaskChain;
import com.dwinovo.numen.task.ChainScheduler;

import com.dwinovo.numen.entity.NumenPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pure tests for {@link ChainScheduler#select} — no Minecraft: the fake chains
 * return programmed constant priorities and ignore the {@link NumenPlayer} (passed
 * as {@code null}).
 */
class ChainSchedulerTest {

    /** A {@link TaskChain} with a fixed priority; everything else is a no-op. */
    private static final class FakeChain implements TaskChain {
        private final String name;
        private final float priority;

        FakeChain(String name, float priority) {
            this.name = name;
            this.priority = priority;
        }

        @Override public float getPriority(NumenPlayer companion) { return priority; }
        @Override public void tick(NumenPlayer companion) {}
        @Override public void onInterrupt(NumenPlayer companion) {}
        @Override public String name() { return name; }
    }

    @Test
    void picksHighestPriority() {
        FakeChain low = new FakeChain("low", 1.0f);
        FakeChain high = new FakeChain("high", 5.0f);
        FakeChain mid = new FakeChain("mid", 3.0f);

        assertSame(high, ChainScheduler.select(List.of(low, high, mid), null));
    }

    @Test
    void earlierWinsOnTie() {
        FakeChain first = new FakeChain("first", 2.0f);
        FakeChain second = new FakeChain("second", 2.0f);

        // Strict '>' means the earlier chain keeps the win on an equal priority.
        assertSame(first, ChainScheduler.select(List.of(first, second), null));
    }

    @Test
    void nullWhenAllDormant() {
        FakeChain a = new FakeChain("a", Float.NEGATIVE_INFINITY);
        FakeChain b = new FakeChain("b", Float.NEGATIVE_INFINITY);

        assertNull(ChainScheduler.select(List.of(a, b), null));
    }

    @Test
    void nullWhenEmpty() {
        assertNull(ChainScheduler.select(List.<TaskChain>of(), null));
    }

    @Test
    void dormantChainsAreSkippedForAnActiveOne() {
        FakeChain dormant = new FakeChain("dormant", Float.NEGATIVE_INFINITY);
        FakeChain active = new FakeChain("active", TaskChain.LLM_BASE_PRIORITY);

        // Base-priority (0) still beats NEGATIVE_INFINITY, so the active chain wins.
        assertSame(active, ChainScheduler.select(List.of(dormant, active), null));
    }
}
