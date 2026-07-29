package com.dwinovo.numen.core.follow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class FollowRuntimeContractsTest {

    @Test
    void releaseReasonsRemainSmallExplicitAndNonPersistent() {
        assertEquals(List.of(
                FollowReleaseReason.SCHEDULER_INTERRUPT,
                FollowReleaseReason.FOLLOW_DISABLED,
                FollowReleaseReason.MANUAL_PAUSE,
                FollowReleaseReason.COMPANION_DEATH,
                FollowReleaseReason.COMPANION_REMOVED,
                FollowReleaseReason.SERVER_STOPPING,
                FollowReleaseReason.RUNTIME_REPLACED,
                FollowReleaseReason.INTERNAL_STATE_CHANGE),
                List.of(FollowReleaseReason.values()));
    }

    @Test
    void snapshotContainsOnlyImmutableRuntimeValues() {
        FollowRuntimeSnapshot snapshot = new FollowRuntimeSnapshot(
                FollowRuntimeState.FAILED_COOLDOWN,
                FollowWaitingReason.NONE,
                false,
                false,
                false,
                false,
                150L,
                25L);

        assertEquals(FollowRuntimeState.FAILED_COOLDOWN, snapshot.runtimeState());
        assertEquals(FollowWaitingReason.NONE, snapshot.waitingReason());
        assertEquals(150L, snapshot.failedUntilTick());
        assertEquals(25L, snapshot.remainingCooldownTicks());
        assertFalse(snapshot.navigationActive());
    }

    @Test
    void followServiceRejectsNullServerBeforeStoreAccess() {
        assertThrows(NullPointerException.class,
                () -> FollowService.releaseRuntime(
                        null, UUID.randomUUID(), FollowReleaseReason.FOLLOW_DISABLED));
        assertThrows(NullPointerException.class,
                () -> FollowService.releaseAllRuntime(
                        null, FollowReleaseReason.SERVER_STOPPING));
    }

    @Test
    void followServiceRejectsInvalidNullArgumentSets() {
        assertThrows(NullPointerException.class,
                () -> FollowService.releaseRuntime(
                        null, null, FollowReleaseReason.FOLLOW_DISABLED));
        assertThrows(NullPointerException.class,
                () -> FollowService.removeRuntime(
                        null, null, null));
        assertThrows(NullPointerException.class,
                () -> FollowService.runtimeSnapshot(
                        null, null, 0L));
    }
}
