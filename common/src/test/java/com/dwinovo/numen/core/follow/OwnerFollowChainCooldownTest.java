package com.dwinovo.numen.core.follow;

import static com.dwinovo.numen.core.follow.OwnerFollowChainTestHarness.activeAt;
import static com.dwinovo.numen.core.follow.OwnerFollowChainTestHarness.enabled;
import static com.dwinovo.numen.core.follow.OwnerFollowChainTestHarness.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;

import org.junit.jupiter.api.Test;

class OwnerFollowChainCooldownTest {

    @Test
    void failedNavigationSetsDeadlineFromSnapshotGameTime() {
        OwnerFollowChainTestHarness harness = failedAt(50L);

        FollowRuntimeSnapshot view = harness.chain.runtimeView();
        assertEquals(FollowRuntimeState.FAILED_COOLDOWN, view.runtimeState());
        assertEquals(FollowWaitingReason.NONE, view.waitingReason());
        assertEquals(150L, view.failedUntilTick());
        assertEquals(100L, harness.chain.remainingCooldownTicks(50L));
        assertFalse(view.sprintAllowed());
        assertFalse(view.catchingUp());
    }

    @Test
    void failedNavigationReleasesControlAndDoesNotCreateSecondNavInSameTick() {
        OwnerFollowChainTestHarness harness = failedAt(50L);

        assertEquals(1, harness.factory.created.size());
        assertEquals(1, harness.factory.last().ticks);
        assertEquals(1, harness.factory.last().stops);
        assertEquals(1, harness.halt.calls);
        assertFalse(harness.chain.hasNavigation());
    }

    @Test
    void tickBeforeDeadlineDoesNotCreateOrTickNavigation() {
        OwnerFollowChainTestHarness harness = failedAt(50L);
        harness.access.snapshot = activeAt(8.0, 149L);
        int previousTicks = harness.factory.last().ticks;

        harness.chain.tick(null);

        assertEquals(FollowRuntimeState.FAILED_COOLDOWN,
                harness.chain.runtimeView().runtimeState());
        assertEquals(1, harness.factory.created.size());
        assertEquals(previousTicks, harness.factory.last().ticks);
        assertFalse(harness.chain.hasNavigation());
    }

    @Test
    void priorityBeforeDeadlineIsNegativeInfinity() {
        OwnerFollowChainTestHarness harness = failedAt(50L);
        harness.access.snapshot = activeAt(8.0, 149L);

        float priority = harness.chain.getPriority(null);

        assertEquals(Float.NEGATIVE_INFINITY, priority);
        assertEquals(1L, harness.chain.remainingCooldownTicks(149L));
    }

    @Test
    void exactDeadlineAllowsRetryWithOneNewNavigation() {
        OwnerFollowChainTestHarness harness = failedAt(50L);
        harness.access.snapshot = activeAt(8.0, 150L);
        harness.factory.nextStatus = PlayerNav.Status.RUNNING;

        harness.chain.tick(null);

        assertEquals(2, harness.factory.created.size());
        assertEquals(1, harness.factory.last().ticks);
        assertTrue(harness.chain.hasNavigation());
        assertEquals(FollowRuntimeState.FOLLOWING,
                harness.chain.runtimeView().runtimeState());
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN,
                harness.chain.runtimeView().failedUntilTick());
    }

    @Test
    void tickAfterDeadlineAlsoAllowsRetry() {
        OwnerFollowChainTestHarness harness = failedAt(50L);
        harness.access.snapshot = activeAt(8.0, 151L);
        harness.factory.nextStatus = PlayerNav.Status.RUNNING;

        harness.chain.tick(null);

        assertEquals(2, harness.factory.created.size());
        assertTrue(harness.chain.hasNavigation());
    }

    @Test
    void repeatedFailureResetsDeadlineFromRetryTick() {
        OwnerFollowChainTestHarness harness = failedAt(50L);
        harness.access.snapshot = activeAt(8.0, 150L);
        harness.factory.nextStatus = PlayerNav.Status.FAILED;

        harness.chain.tick(null);

        assertEquals(2, harness.factory.created.size());
        assertEquals(250L, harness.chain.runtimeView().failedUntilTick());
        assertEquals(FollowRuntimeState.FAILED_COOLDOWN,
                harness.chain.runtimeView().runtimeState());
    }

    @Test
    void arrivedNavigationDoesNotEnterCooldown() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(8.0, 50L));
        harness.factory.nextStatus = PlayerNav.Status.ARRIVED;

        harness.chain.tick(null);

        assertEquals(FollowRuntimeState.FOLLOWING,
                harness.chain.runtimeView().runtimeState());
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN,
                harness.chain.runtimeView().failedUntilTick());
    }

    @Test
    void interruptDoesNotCreateCooldownAndPreservesFollowLatch() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(12.0, 50L));
        harness.chain.tick(null);

        harness.chain.onInterrupt(null);

        FollowRuntimeSnapshot view = harness.chain.runtimeView();
        assertEquals(FollowRuntimeState.FOLLOWING, view.runtimeState());
        assertTrue(view.following());
        assertFalse(view.sprintAllowed());
        assertFalse(view.catchingUp());
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN, view.failedUntilTick());
    }

    @Test
    void interruptDuringCooldownPreservesExistingDeadline() {
        OwnerFollowChainTestHarness harness = failedAt(50L);

        harness.chain.onInterrupt(null);

        assertEquals(150L, harness.chain.runtimeView().failedUntilTick());
        assertEquals(FollowRuntimeState.FAILED_COOLDOWN,
                harness.chain.runtimeView().runtimeState());
    }

    @Test
    void ownerOfflineClearsOldCooldown() {
        OwnerFollowChainTestHarness harness = failedAt(50L);
        harness.access.snapshot = snapshot(enabled(), true, true, false,
                false, false, Double.NaN, 60L);

        harness.chain.getPriority(null);

        assertEquals(FollowWaitingReason.OWNER_OFFLINE,
                harness.chain.runtimeView().waitingReason());
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN,
                harness.chain.runtimeView().failedUntilTick());
    }

    @Test
    void stopDistanceClearsOldCooldown() {
        OwnerFollowChainTestHarness harness = failedAt(50L);
        harness.access.snapshot = activeAt(3.0, 60L);

        harness.chain.getPriority(null);

        assertEquals(FollowRuntimeState.IDLE_NEAR_OWNER,
                harness.chain.runtimeView().runtimeState());
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN,
                harness.chain.runtimeView().failedUntilTick());
    }

    @Test
    void lostDistanceUsesWaitingStateAndClearsOldCooldown() {
        OwnerFollowChainTestHarness harness = failedAt(50L);
        harness.access.snapshot = activeAt(64.0, 60L);

        harness.chain.getPriority(null);

        FollowRuntimeSnapshot view = harness.chain.runtimeView();
        assertEquals(FollowRuntimeState.WAITING_FOR_OWNER, view.runtimeState());
        assertEquals(FollowWaitingReason.OWNER_TOO_FAR, view.waitingReason());
        assertNotEquals(FollowRuntimeState.FAILED_COOLDOWN, view.runtimeState());
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN, view.failedUntilTick());
    }

    @Test
    void twoChainsKeepFailureDeadlinesIndependent() {
        OwnerFollowChainTestHarness first = failedAt(50L);
        OwnerFollowChainTestHarness second =
                new OwnerFollowChainTestHarness(activeAt(8.0, 50L));
        second.factory.nextStatus = PlayerNav.Status.RUNNING;
        second.chain.tick(null);

        assertEquals(150L, first.chain.runtimeView().failedUntilTick());
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN,
                second.chain.runtimeView().failedUntilTick());
        assertEquals(FollowRuntimeState.FAILED_COOLDOWN,
                first.chain.runtimeView().runtimeState());
        assertEquals(FollowRuntimeState.FOLLOWING,
                second.chain.runtimeView().runtimeState());
    }

    @Test
    void cooldownDoesNotMutateFollowIntent() {
        FollowState state = enabled();
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(snapshot(state, true, true, true,
                        true, true, 8.0, 50L));
        harness.factory.nextStatus = PlayerNav.Status.FAILED;

        harness.chain.tick(null);

        assertSame(state, harness.access.snapshot.state());
        assertEquals(enabled(), harness.access.snapshot.state());
        assertEquals(150L, harness.chain.runtimeView().failedUntilTick());
    }

    private static OwnerFollowChainTestHarness failedAt(long gameTime) {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(8.0, gameTime));
        harness.factory.nextStatus = PlayerNav.Status.FAILED;
        harness.chain.tick(null);
        return harness;
    }
}
