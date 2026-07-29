package com.dwinovo.numen.core.follow;

import static com.dwinovo.numen.core.follow.OwnerFollowChainTestHarness.activeAt;
import static com.dwinovo.numen.core.follow.OwnerFollowChainTestHarness.enabled;
import static com.dwinovo.numen.core.follow.OwnerFollowChainTestHarness.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.task.ChainScheduler;
import com.dwinovo.numen.task.TaskChain;

import org.junit.jupiter.api.Test;

class OwnerFollowChainRuntimeTest {

    @Test
    void newChainStartsDisabledWithNoWaitingReasonOrNavigation() {
        OwnerFollowChain chain = new OwnerFollowChain();

        FollowRuntimeSnapshot view = chain.runtimeView();

        assertEquals(FollowRuntimeState.DISABLED, view.runtimeState());
        assertEquals(FollowWaitingReason.NONE, view.waitingReason());
        assertFalse(view.following());
        assertFalse(view.sprintAllowed());
        assertFalse(view.catchingUp());
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN, view.failedUntilTick());
        assertFalse(view.navigationActive());
    }

    @Test
    void winningTickPublishesFollowingRuntimeState() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(8.0, 10L));

        harness.chain.tick(null);

        FollowRuntimeSnapshot view = harness.chain.runtimeView();
        assertEquals(FollowRuntimeState.FOLLOWING, view.runtimeState());
        assertEquals(FollowWaitingReason.NONE, view.waitingReason());
        assertTrue(view.following());
        assertTrue(view.navigationActive());
    }

    @Test
    void followNavigationReceivesSafeContextAndPerChainSprintSupplier() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(8.0, 10L));

        harness.chain.tick(null);

        assertSame(FollowContextProvider.INSTANCE, harness.factory.contextProvider);
        assertSame(harness.factory.sprintAllowed,
                harness.factory.last().sprintAllowed);
        assertFalse(harness.factory.sprintAllowed.getAsBoolean());
    }

    @Test
    void sameNavigationGateTurnsOnAtSprintThreshold() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(11.99, 10L));
        harness.chain.tick(null);
        OwnerFollowChainTestHarness.FakeNavigation navigation =
                harness.factory.last();
        assertFalse(navigation.sprintAllowed.getAsBoolean());

        harness.access.snapshot = activeAt(12.0, 11L);
        harness.chain.tick(null);

        assertSame(navigation, harness.factory.last());
        assertEquals(1, harness.factory.created.size());
        assertTrue(navigation.sprintAllowed.getAsBoolean());
    }

    @Test
    void sameNavigationGateTurnsOffBelowSprintThreshold() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(12.0, 10L));
        harness.chain.tick(null);
        OwnerFollowChainTestHarness.FakeNavigation navigation =
                harness.factory.last();
        assertTrue(navigation.sprintAllowed.getAsBoolean());

        harness.access.snapshot = activeAt(11.99, 11L);
        harness.chain.tick(null);

        assertSame(navigation, harness.factory.last());
        assertEquals(1, harness.factory.created.size());
        assertFalse(navigation.sprintAllowed.getAsBoolean());
    }

    @Test
    void catchUpIsOnlyABooleanModeWithinFollowing() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(24.0, 10L));

        harness.chain.tick(null);

        FollowRuntimeSnapshot view = harness.chain.runtimeView();
        assertEquals(FollowRuntimeState.FOLLOWING, view.runtimeState());
        assertTrue(view.sprintAllowed());
        assertTrue(view.catchingUp());
    }

    @Test
    void lostDistanceImmediatelyStopsHaltsAndDoesNotCreateAnotherNavigation() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(8.0, 10L));
        harness.chain.tick(null);
        OwnerFollowChainTestHarness.FakeNavigation navigation =
                harness.factory.last();

        harness.access.snapshot = activeAt(64.0, 11L);
        harness.chain.tick(null);

        FollowRuntimeSnapshot view = harness.chain.runtimeView();
        assertEquals(FollowRuntimeState.WAITING_FOR_OWNER, view.runtimeState());
        assertEquals(FollowWaitingReason.OWNER_TOO_FAR, view.waitingReason());
        assertEquals(1, navigation.stops);
        assertEquals(1, harness.halt.calls);
        assertEquals(1, harness.factory.created.size());
        assertFalse(view.navigationActive());
    }

    @Test
    void ownerOfflineReleasesControlAndPublishesOfflineReason() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(8.0, 10L));
        harness.chain.tick(null);
        OwnerFollowChainTestHarness.FakeNavigation navigation =
                harness.factory.last();
        harness.access.snapshot = snapshot(enabled(), true, true, false,
                false, false, Double.NaN, 11L);

        harness.chain.tick(null);

        FollowRuntimeSnapshot view = harness.chain.runtimeView();
        assertEquals(FollowRuntimeState.WAITING_FOR_OWNER, view.runtimeState());
        assertEquals(FollowWaitingReason.OWNER_OFFLINE, view.waitingReason());
        assertEquals(1, navigation.stops);
        assertFalse(view.navigationActive());
    }

    @Test
    void arrivedWhileStillFollowingRetainsSameRevalidatingNavigation() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(8.0, 10L));
        harness.factory.nextStatus = PlayerNav.Status.ARRIVED;

        harness.chain.tick(null);

        assertEquals(1, harness.factory.created.size());
        assertEquals(0, harness.factory.last().stops);
        assertTrue(harness.chain.runtimeView().navigationActive());
        assertEquals(FollowRuntimeState.FOLLOWING,
                harness.chain.runtimeView().runtimeState());
    }

    @Test
    void arrivedAfterEnteringStopDistanceReleasesControlWithoutSecondNavigation() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(8.0, 10L));
        harness.factory.nextStatus = PlayerNav.Status.ARRIVED;
        harness.chain.tick(null);
        OwnerFollowChainTestHarness.FakeNavigation navigation =
                harness.factory.last();
        navigation.onTick = () ->
                harness.access.snapshot = activeAt(3.0, 11L);

        harness.chain.tick(null);

        assertEquals(1, harness.factory.created.size());
        assertEquals(1, navigation.stops);
        assertEquals(FollowRuntimeState.IDLE_NEAR_OWNER,
                harness.chain.runtimeView().runtimeState());
    }

    @Test
    void twoChainsKeepRuntimeModesAndNavigationIndependent() {
        OwnerFollowChainTestHarness first =
                new OwnerFollowChainTestHarness(activeAt(24.0, 10L));
        OwnerFollowChainTestHarness second =
                new OwnerFollowChainTestHarness(activeAt(8.0, 10L));

        first.chain.tick(null);
        second.chain.tick(null);

        assertNotSame(first.chain, second.chain);
        assertNotSame(first.factory.last(), second.factory.last());
        assertTrue(first.chain.runtimeView().catchingUp());
        assertFalse(second.chain.runtimeView().catchingUp());
        assertTrue(first.chain.runtimeView().sprintAllowed());
        assertFalse(second.chain.runtimeView().sprintAllowed());
    }

    @Test
    void lostOwnerIsRecheckedEverySchedulerEvaluation() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(64.0, 10L));
        assertEquals(Float.NEGATIVE_INFINITY, harness.chain.getPriority(null));

        harness.access.snapshot = activeAt(8.0, 11L);

        assertEquals(OwnerFollowChain.PRIORITY, harness.chain.getPriority(null));
        assertEquals(2, harness.access.snapshots);
        assertEquals(0, harness.factory.created.size());
    }

    @Test
    void higherPrioritySchedulerWinnerInterruptsAndReleasesFollowNavigation() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(8.0, 10L));
        harness.chain.tick(null);
        OwnerFollowChainTestHarness.FakeNavigation navigation =
                harness.factory.last();
        TaskChain explicit = new TaskChain() {
            @Override
            public float getPriority(com.dwinovo.numen.entity.NumenPlayer companion) {
                return TaskChain.LLM_BASE_PRIORITY;
            }

            @Override
            public void tick(com.dwinovo.numen.entity.NumenPlayer companion) {}

            @Override
            public void onInterrupt(com.dwinovo.numen.entity.NumenPlayer companion) {}

            @Override
            public String name() {
                return "explicit";
            }
        };

        TaskChain running = harness.chain;
        TaskChain winner = ChainScheduler.select(
                List.of(harness.chain, explicit), null);
        if (running != winner) {
            running.onInterrupt(null);
        }

        assertSame(explicit, winner);
        assertEquals(1, navigation.stops);
        assertFalse(harness.chain.hasNavigation());
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN,
                harness.chain.runtimeView().failedUntilTick());
    }
}
