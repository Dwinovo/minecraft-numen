package com.dwinovo.numen.core.follow;

import static com.dwinovo.numen.core.follow.FollowStage7ATestSupport.reload;
import static com.dwinovo.numen.core.follow.FollowStage7ATestSupport.state;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class FollowMultiCompanionPersistenceTest {

    @Test
    void threeCompanionCombinationRemainsIsolatedAcrossRestartMutationAndDelete() {
        UUID companionA = UUID.randomUUID();
        UUID companionB = UUID.randomUUID();
        UUID companionC = UUID.randomUUID();
        FollowState stateA = state(true, false, 2.5, 6.5);
        FollowState stateB = state(true, true, null, null);
        FollowState stateC = FollowState.defaults();
        FollowStateStore store = new FollowStateStore();
        store.put(companionA, stateA);
        store.put(companionB, stateB);
        store.put(companionC, stateC);

        FollowStateStore loaded = reload(store);
        assertEquals(stateA, loaded.getOrDefault(companionA));
        assertEquals(stateB, loaded.getOrDefault(companionB));
        assertEquals(stateC, loaded.getOrDefault(companionC));

        loaded.setControlState(companionA, false, false);
        assertEquals(stateB, loaded.getOrDefault(companionB));
        assertEquals(stateC, loaded.getOrDefault(companionC));
        assertEquals(2.5,
                loaded.getOrDefault(companionA).stopDistanceOverride());
        assertEquals(null,
                loaded.getOrDefault(companionB).stopDistanceOverride());
        assertEquals(null,
                loaded.getOrDefault(companionC).stopDistanceOverride());

        loaded.remove(companionB);
        assertEquals(2, loaded.size());
        assertTrue(loaded.find(companionB).isEmpty());
        assertTrue(loaded.find(companionA).isPresent());
        assertTrue(loaded.find(companionC).isPresent());
    }

    @Test
    void runtimeStatusAndStoreInstancesNeverCrossCompanionBoundaries() {
        UUID companionA = UUID.randomUUID();
        UUID companionB = UUID.randomUUID();
        UUID companionC = UUID.randomUUID();
        FollowStateStore firstStore = new FollowStateStore();
        firstStore.put(companionA, state(true, false, 2.5, 6.5));
        firstStore.put(companionB, state(true, true, null, null));
        firstStore.put(companionC, FollowState.defaults());
        firstStore.bindRuntime(
                companionA,
                new FollowStage7ATestSupport.TrackingControl(
                        companionA,
                        new FollowRuntimeSnapshot(
                                FollowRuntimeState.FOLLOWING,
                                FollowWaitingReason.NONE,
                                true, true, false, false,
                                FollowDecisions.NO_FAILED_COOLDOWN, 0L)));

        FollowStatus statusA = FollowService.status(
                firstStore,
                FollowStage7ATestSupport.subject(
                        companionA, true, true, true, true,
                        OptionalDouble.of(8.0), 100L),
                FollowConfig.defaults());

        assertTrue(statusA.runtimeAvailable());
        assertEquals(1, firstStore.runtimeControlCount());
        assertTrue(firstStore.runtimeControl(companionB).isEmpty());
        assertTrue(firstStore.runtimeControl(companionC).isEmpty());

        FollowStateStore restartedStore = reload(firstStore);
        FollowStateStore unrelatedStore = new FollowStateStore();

        assertEquals(state(true, false, 2.5, 6.5),
                restartedStore.getOrDefault(companionA));
        assertEquals(0, restartedStore.runtimeControlCount());
        assertEquals(0, unrelatedStore.runtimeControlCount());
        assertFalse(unrelatedStore.find(companionA).isPresent());
    }
}
