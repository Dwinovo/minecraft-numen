package com.dwinovo.numen.core.follow;

import static com.dwinovo.numen.core.follow.OwnerFollowChainTestHarness.activeAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.ChainScheduler;
import com.dwinovo.numen.task.TaskChain;

class OwnerFollowSchedulerCoordinationTest {

    @Test
    void explicitPriorityZeroPreemptsFollowMinusTwo() {
        assertPreempts(0.0F);
    }

    @Test
    void speakingLookPriorityMinusOnePreemptsFollowMinusTwo() {
        assertPreempts(-1.0F);
    }

    @Test
    void survivalPriorityPreemptsFollowMinusTwo() {
        assertPreempts(10.0F);
    }

    @Test
    void preemptionStopsHaltsWithoutCooldownOrIntentMutation() {
        OwnerFollowChainTestHarness harness = runningHarness();
        OwnerFollowChainTestHarness.FakeNavigation navigation = harness.factory.last();
        FollowState intent = harness.access.snapshot.state();

        harness.chain.onInterrupt(null);

        assertEquals(1, navigation.stops);
        assertEquals(1, harness.halt.calls);
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN,
                harness.chain.snapshot(10L).failedUntilTick());
        assertEquals(intent, harness.access.snapshot.state());
    }

    @Test
    void followResumesWithNewNavigationAfterHigherPrioritySleeps() {
        OwnerFollowChainTestHarness harness = runningHarness();
        OwnerFollowChainTestHarness.FakeNavigation oldNavigation = harness.factory.last();
        TaskChain higher = chainAt(0.0F);
        TaskChain winner = ChainScheduler.select(List.of(harness.chain, higher), null);
        assertSame(higher, winner);
        harness.chain.onInterrupt(null);

        TaskChain resumed = ChainScheduler.select(List.of(harness.chain), null);
        resumed.tick(null);

        assertSame(harness.chain, resumed);
        assertEquals(2, harness.factory.created.size());
        assertNotSame(oldNavigation, harness.factory.last());
        assertTrue(harness.chain.snapshot(10L).navigationActive());
    }

    @Test
    void noEligibleWinnerInterruptsPreviouslyRunningFollow() {
        OwnerFollowChainTestHarness harness = runningHarness();
        harness.access.snapshot = new OwnerFollowChain.Snapshot(
                FollowState.defaults(), true, true, true, true, true, 8.0, 11L);

        TaskChain winner = ChainScheduler.select(List.of(harness.chain), null);
        if (winner == null) {
            harness.chain.onInterrupt(null);
        }

        assertEquals(null, winner);
        assertFalse(harness.chain.snapshot(11L).navigationActive());
        assertEquals(1, harness.factory.last().stops);
    }

    @Test
    void independentChainsNeverShareNavigationOrCooldown() {
        OwnerFollowChainTestHarness first = runningHarness();
        OwnerFollowChainTestHarness second = runningHarness();
        first.chain.onInterrupt(null);

        assertFalse(first.chain.snapshot(10L).navigationActive());
        assertTrue(second.chain.snapshot(10L).navigationActive());
        assertNotSame(first.factory.last(), second.factory.last());
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN,
                second.chain.snapshot(10L).failedUntilTick());
    }

    private static void assertPreempts(float priority) {
        OwnerFollowChainTestHarness harness = runningHarness();
        TaskChain higher = chainAt(priority);

        TaskChain winner =
                ChainScheduler.select(List.of(harness.chain, higher), null);
        if (winner != harness.chain) {
            harness.chain.onInterrupt(null);
        }

        assertSame(higher, winner);
        assertEquals(1, harness.factory.last().stops);
        assertFalse(harness.chain.snapshot(10L).navigationActive());
    }

    private static OwnerFollowChainTestHarness runningHarness() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(8.0, 10L));
        harness.chain.tick(null);
        return harness;
    }

    private static TaskChain chainAt(float priority) {
        return new TaskChain() {
            @Override
            public float getPriority(NumenPlayer companion) {
                return priority;
            }

            @Override
            public void tick(NumenPlayer companion) {}

            @Override
            public void onInterrupt(NumenPlayer companion) {}

            @Override
            public String name() {
                return "test_priority_" + priority;
            }
        };
    }
}
