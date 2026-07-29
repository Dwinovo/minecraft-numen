package com.dwinovo.numen.core.follow;

import static com.dwinovo.numen.core.follow.OwnerFollowChainTestHarness.activeAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;

class OwnerFollowChainExternalReleaseTest {

    @Test
    void ownerFollowChainImplementsRuntimeControl() {
        assertInstanceOf(FollowRuntimeControl.class, new OwnerFollowChain());
    }

    @Test
    void snapshotIsAStableValueAndDoesNotMutateRuntime() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(12.0, 40L));
        harness.chain.tick(null);

        FollowRuntimeSnapshot first = harness.chain.snapshot(40L);
        FollowRuntimeSnapshot second = harness.chain.snapshot(40L);

        assertEquals(first, second);
        assertEquals(first, harness.chain.runtimeView());
        assertTrue(first.navigationActive());
    }

    @Test
    void snapshotClampsNegativeRemainingCooldownToZero() {
        FollowRuntimeSnapshot snapshot = new FollowRuntimeSnapshot(
                FollowRuntimeState.DISABLED,
                FollowWaitingReason.NONE,
                false, false, false, false, -1L, -25L);

        assertEquals(0L, snapshot.remainingCooldownTicks());
    }

    @Test
    void followDisabledStopsAndPublishesDisabledState() {
        OwnerFollowChainTestHarness harness = activeHarness();
        OwnerFollowChainTestHarness.FakeNavigation navigation = harness.factory.last();

        harness.chain.release(FollowReleaseReason.FOLLOW_DISABLED);

        assertEquals(1, navigation.stops);
        assertEquals(0, harness.halt.calls);
        assertReleased(harness.chain.snapshot(10L),
                FollowRuntimeState.DISABLED, FollowWaitingReason.NONE);
    }

    @Test
    void manualPauseStopsAndPublishesPausedState() {
        OwnerFollowChainTestHarness harness = activeHarness();

        harness.chain.release(FollowReleaseReason.MANUAL_PAUSE);

        assertEquals(1, harness.factory.last().stops);
        assertEquals(0, harness.halt.calls);
        assertReleased(harness.chain.snapshot(10L),
                FollowRuntimeState.MANUALLY_PAUSED, FollowWaitingReason.NONE);
    }

    @Test
    void companionDeathClearsNavigationLatchAndCooldown() {
        OwnerFollowChainTestHarness harness = failedHarness();

        harness.chain.release(FollowReleaseReason.COMPANION_DEATH);

        FollowRuntimeSnapshot snapshot = harness.chain.snapshot(10L);
        assertReleased(snapshot, FollowRuntimeState.WAITING_FOR_OWNER,
                FollowWaitingReason.COMPANION_NOT_ACTIVE);
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN, snapshot.failedUntilTick());
    }

    @Test
    void companionRemovalClearsNavigationLatchAndCooldown() {
        OwnerFollowChainTestHarness harness = failedHarness();

        harness.chain.release(FollowReleaseReason.COMPANION_REMOVED);

        FollowRuntimeSnapshot snapshot = harness.chain.snapshot(10L);
        assertReleased(snapshot, FollowRuntimeState.WAITING_FOR_OWNER,
                FollowWaitingReason.COMPANION_NOT_ACTIVE);
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN, snapshot.failedUntilTick());
    }

    @Test
    void serverStoppingClearsNavigationLatchAndCooldown() {
        OwnerFollowChainTestHarness harness = failedHarness();

        harness.chain.release(FollowReleaseReason.SERVER_STOPPING);

        FollowRuntimeSnapshot snapshot = harness.chain.snapshot(10L);
        assertReleased(snapshot, FollowRuntimeState.DISABLED, FollowWaitingReason.NONE);
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN, snapshot.failedUntilTick());
    }

    @Test
    void runtimeReplacementClearsOldRuntime() {
        OwnerFollowChainTestHarness harness = activeHarness();

        harness.chain.release(FollowReleaseReason.RUNTIME_REPLACED);

        assertEquals(1, harness.factory.last().stops);
        assertReleased(harness.chain.snapshot(10L),
                FollowRuntimeState.DISABLED, FollowWaitingReason.NONE);
    }

    @Test
    void repeatedExternalReleaseIsIdempotentForNavigation() {
        OwnerFollowChainTestHarness harness = activeHarness();
        OwnerFollowChainTestHarness.FakeNavigation navigation = harness.factory.last();

        harness.chain.release(FollowReleaseReason.FOLLOW_DISABLED);
        harness.chain.release(FollowReleaseReason.FOLLOW_DISABLED);

        assertEquals(1, navigation.stops);
        assertEquals(0, harness.halt.calls);
        assertFalse(harness.chain.hasNavigation());
    }

    @Test
    void unboundControlReleaseWithNoNavigationIsSafe() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(8.0, 10L));

        harness.chain.release(FollowReleaseReason.SERVER_STOPPING);
        harness.chain.release(FollowReleaseReason.SERVER_STOPPING);

        assertFalse(harness.chain.snapshot(10L).navigationActive());
        assertEquals(0, harness.halt.calls);
    }

    @Test
    void releasedNavigationCannotBeTickedAgainByChain() {
        OwnerFollowChainTestHarness harness = activeHarness();
        OwnerFollowChainTestHarness.FakeNavigation oldNavigation = harness.factory.last();
        harness.chain.release(FollowReleaseReason.SCHEDULER_INTERRUPT);

        harness.chain.tick(null);

        assertEquals(1, oldNavigation.ticks);
        assertEquals(1, oldNavigation.stops);
        assertEquals(2, harness.factory.created.size());
        assertNotSame(oldNavigation, harness.factory.last());
    }

    @Test
    void schedulerInterruptPreservesFailureDeadlineWithoutCreatingOne() {
        OwnerFollowChainTestHarness active = activeHarness();
        active.chain.release(FollowReleaseReason.SCHEDULER_INTERRUPT);
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN,
                active.chain.snapshot(10L).failedUntilTick());

        OwnerFollowChainTestHarness failed = failedHarness();
        long deadline = failed.chain.snapshot(10L).failedUntilTick();
        failed.chain.release(FollowReleaseReason.SCHEDULER_INTERRUPT);
        assertEquals(deadline, failed.chain.snapshot(10L).failedUntilTick());
    }

    @Test
    void schedulerInterruptPreservesLatchAndAllowsFreshNavigationResume() {
        OwnerFollowChainTestHarness harness = activeHarness();
        OwnerFollowChainTestHarness.FakeNavigation oldNavigation = harness.factory.last();
        assertTrue(harness.chain.snapshot(10L).following());

        harness.chain.release(FollowReleaseReason.SCHEDULER_INTERRUPT);
        assertTrue(harness.chain.snapshot(10L).following());
        harness.chain.tick(null);

        assertEquals(2, harness.factory.created.size());
        assertNotSame(oldNavigation, harness.factory.last());
        assertTrue(harness.chain.snapshot(10L).following());
    }

    @Test
    void allExternalReasonsClearSprintAndCatchUp() {
        for (FollowReleaseReason reason : FollowReleaseReason.values()) {
            if (reason == FollowReleaseReason.SCHEDULER_INTERRUPT
                    || reason == FollowReleaseReason.INTERNAL_STATE_CHANGE) {
                continue;
            }
            OwnerFollowChainTestHarness harness =
                    new OwnerFollowChainTestHarness(activeAt(24.0, 10L));
            harness.chain.tick(null);
            assertTrue(harness.chain.snapshot(10L).sprintAllowed());
            assertTrue(harness.chain.snapshot(10L).catchingUp());

            harness.chain.release(reason);

            assertFalse(harness.chain.snapshot(10L).sprintAllowed());
            assertFalse(harness.chain.snapshot(10L).catchingUp());
        }
    }

    @Test
    void externalReleaseDoesNotMutateFollowIntentSnapshot() {
        OwnerFollowChain.Snapshot intentSnapshot = activeAt(8.0, 10L);
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(intentSnapshot);

        harness.chain.tick(null);
        harness.chain.release(FollowReleaseReason.COMPANION_DEATH);

        assertEquals(intentSnapshot, harness.access.snapshot);
        assertTrue(harness.access.snapshot.state().enabled());
        assertFalse(harness.access.snapshot.state().manualPaused());
    }

    private static OwnerFollowChainTestHarness activeHarness() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(8.0, 10L));
        harness.chain.tick(null);
        return harness;
    }

    private static OwnerFollowChainTestHarness failedHarness() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(8.0, 10L));
        harness.factory.nextStatus = PlayerNav.Status.FAILED;
        harness.chain.tick(null);
        return harness;
    }

    private static void assertReleased(
            FollowRuntimeSnapshot snapshot,
            FollowRuntimeState expectedState,
            FollowWaitingReason expectedReason) {
        assertEquals(expectedState, snapshot.runtimeState());
        assertEquals(expectedReason, snapshot.waitingReason());
        assertFalse(snapshot.navigationActive());
        assertFalse(snapshot.following());
        assertFalse(snapshot.sprintAllowed());
        assertFalse(snapshot.catchingUp());
    }
}
