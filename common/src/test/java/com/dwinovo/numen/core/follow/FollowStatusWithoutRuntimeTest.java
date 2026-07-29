package com.dwinovo.numen.core.follow;

import static com.dwinovo.numen.core.follow.FollowStage7ATestSupport.reload;
import static com.dwinovo.numen.core.follow.FollowStage7ATestSupport.state;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FollowStatusWithoutRuntimeTest {

    private static final FollowConfig CONFIG = new FollowConfig(
            2.0, 4.0, 9.0, 20.0, 50.0, 240L,
            false, false, false, false, false);

    @ParameterizedTest
    @ValueSource(doubles = {50.0, 50.000001})
    void lostDistanceAndBeyondUseTooFarWithoutCreatingRuntime(
            double distance) {
        UUID companion = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        store.put(companion, state(true, false, null, null));
        store.setDirty(false);

        FollowStatus status = FollowService.status(
                store,
                FollowStage7ATestSupport.subject(
                        companion, true, true, true, true,
                        OptionalDouble.of(distance), 100L),
                CONFIG);

        assertFalse(status.runtimeAvailable());
        assertEquals(FollowRuntimeState.WAITING_FOR_OWNER,
                status.runtimeState());
        assertEquals(FollowWaitingReason.OWNER_TOO_FAR,
                status.waitingReason());
        assertEquals(distance, status.distance().orElseThrow());
        assertEquals(0, store.runtimeControlCount());
        assertFalse(store.isDirty());
    }

    @Test
    void reloadedOverrideUsesCurrentConfigWithoutBindingRuntime() {
        UUID companion = UUID.randomUUID();
        FollowStateStore original = new FollowStateStore();
        original.put(companion, state(true, false, 2.5, 6.5));
        FollowStateStore loaded = reload(original);
        loaded.setDirty(false);

        FollowStatus status = FollowService.status(
                loaded,
                FollowStage7ATestSupport.subject(
                        companion, true, true, true, true,
                        OptionalDouble.of(8.0), 100L),
                CONFIG);

        assertFalse(status.runtimeAvailable());
        assertEquals(2.5, status.effectiveStopDistance());
        assertEquals(6.5, status.effectiveStartDistance());
        assertEquals(9.0, status.sprintDistance());
        assertEquals(20.0, status.catchUpDistance());
        assertEquals(50.0, status.lostDistance());
        assertEquals(240L, status.failedCooldownTicks());
        assertEquals(0, loaded.runtimeControlCount());
        assertFalse(loaded.isDirty());
    }

    @Test
    void reloadedOfflineOwnerKeepsDistanceUnavailableAndRuntimeAbsent() {
        UUID companion = UUID.randomUUID();
        FollowStateStore original = new FollowStateStore();
        original.put(companion, state(true, false, null, null));
        FollowStateStore loaded = reload(original);
        loaded.setDirty(false);

        FollowStatus status = FollowService.status(
                loaded,
                FollowStage7ATestSupport.subject(
                        companion, true, false, false, false,
                        OptionalDouble.empty(), 100L),
                CONFIG);

        assertFalse(status.runtimeAvailable());
        assertEquals(FollowRuntimeState.WAITING_FOR_OWNER,
                status.runtimeState());
        assertEquals(FollowWaitingReason.OWNER_OFFLINE,
                status.waitingReason());
        assertTrue(status.distance().isEmpty());
        assertEquals(0, loaded.runtimeControlCount());
        assertFalse(loaded.isDirty());
    }
}
