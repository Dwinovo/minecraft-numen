package com.dwinovo.numen.core.follow;

import static com.dwinovo.numen.core.follow.OwnerFollowChainTestHarness.activeAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.exec.PlayerNavSprintGateTestProbe;
import com.dwinovo.numen.core.pathing.moves.NavigationCapabilities;

class OwnerFollowNavigationReleaseSequenceTest {

    @Test
    void unavailableOwnerMatrixReleasesActiveNavigationWithoutRetick() {
        List<ReleaseCase> cases = List.of(
                new ReleaseCase(
                        OwnerFollowChainTestHarness.snapshot(
                                OwnerFollowChainTestHarness.enabled(),
                                true, true, false, false, false,
                                Double.NaN, 11L),
                        FollowRuntimeState.WAITING_FOR_OWNER,
                        FollowWaitingReason.OWNER_OFFLINE),
                new ReleaseCase(
                        OwnerFollowChainTestHarness.snapshot(
                                OwnerFollowChainTestHarness.enabled(),
                                true, false, false, false, false,
                                Double.NaN, 11L),
                        FollowRuntimeState.WAITING_FOR_OWNER,
                        FollowWaitingReason.OWNER_INVALID),
                new ReleaseCase(
                        OwnerFollowChainTestHarness.snapshot(
                                OwnerFollowChainTestHarness.enabled(),
                                true, true, true, true, false,
                                Double.NaN, 11L),
                        FollowRuntimeState.WAITING_FOR_OWNER,
                        FollowWaitingReason.OWNER_OTHER_DIMENSION),
                new ReleaseCase(
                        OwnerFollowChainTestHarness.snapshot(
                                OwnerFollowChainTestHarness.enabled(),
                                true, true, true, true, true,
                                FollowConfig.DEFAULT_LOST_DISTANCE, 11L),
                        FollowRuntimeState.WAITING_FOR_OWNER,
                        FollowWaitingReason.OWNER_TOO_FAR),
                new ReleaseCase(
                        activeAt(FollowConfig.DEFAULT_STOP_DISTANCE, 11L),
                        FollowRuntimeState.IDLE_NEAR_OWNER,
                        FollowWaitingReason.NONE));

        for (ReleaseCase releaseCase : cases) {
            OwnerFollowChainTestHarness harness =
                    new OwnerFollowChainTestHarness(activeAt(8.0, 10L));
            Stage7BExecutionTestSupport.SchedulerDriver scheduler =
                    new Stage7BExecutionTestSupport.SchedulerDriver();
            scheduler.step(List.of(harness.chain));
            OwnerFollowChainTestHarness.FakeNavigation old =
                    harness.factory.last();

            harness.access.snapshot = releaseCase.snapshot();
            assertEquals(null, scheduler.step(List.of(harness.chain)));
            assertEquals(null, scheduler.step(List.of(harness.chain)));

            FollowRuntimeSnapshot runtime = harness.chain.snapshot(11L);
            assertEquals(releaseCase.runtimeState(), runtime.runtimeState());
            assertEquals(releaseCase.waitingReason(), runtime.waitingReason());
            assertFalse(runtime.navigationActive());
            assertEquals(1, old.ticks);
            assertEquals(1, old.stops);
            assertEquals(1, harness.factory.created.size());
        }
    }

    @Test
    void runningFailureCooldownAndRetryUseFreshSafeNavigation() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(8.0, 10L));
        Stage7BExecutionTestSupport.SchedulerDriver scheduler =
                new Stage7BExecutionTestSupport.SchedulerDriver();
        scheduler.step(List.of(harness.chain));
        OwnerFollowChainTestHarness.FakeNavigation first =
                harness.factory.last();
        assertSafe(first);

        harness.access.snapshot = activeAt(16.0, 11L);
        scheduler.step(List.of(harness.chain));
        harness.access.snapshot = activeAt(24.0, 12L);
        scheduler.step(List.of(harness.chain));
        harness.access.snapshot = activeAt(8.0, 13L);
        scheduler.step(List.of(harness.chain));
        assertSame(first, harness.factory.last());
        assertEquals(4, first.ticks);
        assertFalse(harness.chain.snapshot(13L).sprintAllowed());
        assertFalse(harness.chain.snapshot(13L).catchingUp());

        first.status = PlayerNav.Status.FAILED;
        harness.access.snapshot = activeAt(8.0, 20L);
        scheduler.step(List.of(harness.chain));
        assertEquals(1, first.stops);
        assertEquals(120L, harness.chain.snapshot(20L).failedUntilTick());
        assertFalse(harness.chain.snapshot(20L).navigationActive());

        harness.access.snapshot = activeAt(8.0, 119L);
        assertEquals(null, scheduler.step(List.of(harness.chain)));
        assertEquals(1, harness.factory.created.size());

        harness.factory.nextStatus = PlayerNav.Status.RUNNING;
        harness.access.snapshot = activeAt(8.0, 120L);
        assertSame(harness.chain, scheduler.step(List.of(harness.chain)));
        OwnerFollowChainTestHarness.FakeNavigation retry =
                harness.factory.last();
        assertNotSame(first, retry);
        assertSafe(retry);
        assertEquals(2, harness.factory.created.size());
        assertEquals(1, retry.ticks);
        assertEquals(5, first.ticks);
    }

    @Test
    void ownerChainGateDrivesPlayerNavGuardWithoutRebuildingNavigation() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(8.0, 10L));
        harness.chain.tick(null);
        OwnerFollowChainTestHarness.FakeNavigation navigation =
                harness.factory.last();
        BooleanSupplier gate = navigation.sprintAllowed;

        assertFalse(PlayerNavSprintGateTestProbe.observeGuard(gate));
        harness.access.snapshot = activeAt(16.0, 11L);
        harness.chain.tick(null);
        assertTrue(PlayerNavSprintGateTestProbe.observeGuard(gate));
        harness.access.snapshot = activeAt(8.0, 12L);
        harness.chain.tick(null);
        assertFalse(PlayerNavSprintGateTestProbe.observeGuard(gate));

        assertSame(navigation, harness.factory.last());
        assertEquals(1, harness.factory.created.size());
        assertEquals(3, navigation.ticks);
    }

    @Test
    void stopAndHaltFailuresStillClearNavigationAndDynamicFlags() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(24.0, 10L));
        harness.chain.tick(null);
        OwnerFollowChainTestHarness.FakeNavigation navigation =
                harness.factory.last();
        navigation.throwOnStop = true;
        harness.halt.throwOnHalt = true;

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> harness.chain.onInterrupt(null));

        assertEquals("deliberate navigation stop failure", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("deliberate input halt failure",
                failure.getSuppressed()[0].getMessage());
        FollowRuntimeSnapshot runtime = harness.chain.snapshot(10L);
        assertFalse(runtime.navigationActive());
        assertFalse(runtime.sprintAllowed());
        assertFalse(runtime.catchingUp());
        assertTrue(runtime.following());
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN,
                runtime.failedUntilTick());
        assertEquals(1, navigation.ticks);
        assertEquals(1, navigation.stops);
        assertEquals(1, harness.halt.calls);
    }

    @Test
    void internalStateChangePreservesExistingLatchAndDeadline() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(8.0, 10L));
        harness.factory.nextStatus = PlayerNav.Status.FAILED;
        harness.chain.tick(null);
        FollowRuntimeSnapshot failed = harness.chain.snapshot(10L);

        harness.chain.release(FollowReleaseReason.INTERNAL_STATE_CHANGE);

        FollowRuntimeSnapshot released = harness.chain.snapshot(10L);
        assertEquals(FollowRuntimeState.FAILED_COOLDOWN,
                released.runtimeState());
        assertTrue(released.following());
        assertEquals(failed.failedUntilTick(), released.failedUntilTick());
        assertFalse(released.navigationActive());
    }

    private static void assertSafe(
            OwnerFollowChainTestHarness.FakeNavigation navigation) {
        assertSame(FollowContextProvider.INSTANCE, navigation.contextProvider);
        assertNotSame(PlayerNav.ContextProvider.DEFAULT,
                navigation.contextProvider);
        NavigationCapabilities capabilities =
                FollowContextProvider.capabilities();
        assertSame(NavigationCapabilities.SAFE_FOLLOW, capabilities);
        assertFalse(capabilities.permitsBreak(true));
        assertFalse(capabilities.permitsPlace(true));
        assertFalse(capabilities.permitsWaterBucketLanding(true));
    }

    private record ReleaseCase(
            OwnerFollowChain.Snapshot snapshot,
            FollowRuntimeState runtimeState,
            FollowWaitingReason waitingReason) {}
}
