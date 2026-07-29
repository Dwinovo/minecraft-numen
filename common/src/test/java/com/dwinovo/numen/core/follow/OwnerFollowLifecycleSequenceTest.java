package com.dwinovo.numen.core.follow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OwnerFollowLifecycleSequenceTest {

    @Test
    void delayedOldRemoveCannotDeleteRespawnedRuntimeWithSameUuid() {
        UUID companionUuid = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        Object oldBody = new Object();
        Object respawnedBody = new Object();
        TrackingControl deadRuntime = bind(store, companionUuid, oldBody);

        store.removeRuntime(
                companionUuid, oldBody, FollowReleaseReason.COMPANION_DEATH);
        TrackingControl respawnedRuntime =
                bind(store, companionUuid, respawnedBody);
        store.removeRuntime(
                companionUuid, oldBody, FollowReleaseReason.COMPANION_REMOVED);

        assertCurrent(store, companionUuid, respawnedRuntime);
        assertEquals(
                List.of(FollowReleaseReason.COMPANION_DEATH),
                deadRuntime.reasons);
        assertEquals(List.of(), respawnedRuntime.reasons);
    }

    @Test
    void currentDeathRemovesAndReleasesCurrentRuntime() {
        UUID companionUuid = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        Object body = new Object();
        TrackingControl runtime = bind(store, companionUuid, body);

        store.removeRuntime(
                companionUuid, body, FollowReleaseReason.COMPANION_DEATH);

        assertTrue(store.runtimeControl(companionUuid).isEmpty());
        assertEquals(
                List.of(FollowReleaseReason.COMPANION_DEATH),
                runtime.reasons);
    }

    @Test
    void currentRemoveRemovesAndReleasesCurrentRuntime() {
        UUID companionUuid = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        Object body = new Object();
        TrackingControl runtime = bind(store, companionUuid, body);

        store.removeRuntime(
                companionUuid, body, FollowReleaseReason.COMPANION_REMOVED);

        assertTrue(store.runtimeControl(companionUuid).isEmpty());
        assertEquals(
                List.of(FollowReleaseReason.COMPANION_REMOVED),
                runtime.reasons);
    }

    @Test
    void deathThenDelayedRemoveForSameOldBodyIsSafeNoOp() {
        UUID companionUuid = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        Object body = new Object();
        TrackingControl runtime = bind(store, companionUuid, body);

        store.removeRuntime(
                companionUuid, body, FollowReleaseReason.COMPANION_DEATH);
        store.removeRuntime(
                companionUuid, body, FollowReleaseReason.COMPANION_REMOVED);

        assertEquals(
                List.of(FollowReleaseReason.COMPANION_DEATH),
                runtime.reasons);
        assertTrue(store.runtimeControl(companionUuid).isEmpty());
    }

    @Test
    void staleDeathAfterRespawnDoesNotReleaseNewRuntime() {
        UUID companionUuid = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        Object oldBody = new Object();
        Object respawnedBody = new Object();
        TrackingControl oldRuntime = bind(store, companionUuid, oldBody);
        store.removeRuntime(
                companionUuid, oldBody, FollowReleaseReason.COMPANION_DEATH);
        TrackingControl respawnedRuntime =
                bind(store, companionUuid, respawnedBody);

        store.removeRuntime(
                companionUuid, oldBody, FollowReleaseReason.COMPANION_DEATH);

        assertCurrent(store, companionUuid, respawnedRuntime);
        assertEquals(
                List.of(FollowReleaseReason.COMPANION_DEATH),
                oldRuntime.reasons);
        assertEquals(List.of(), respawnedRuntime.reasons);
    }

    @Test
    void repeatedStaleCallbacksNeverTouchRespawnedRuntime() {
        UUID companionUuid = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        Object oldBody = new Object();
        Object respawnedBody = new Object();
        TrackingControl oldRuntime = bind(store, companionUuid, oldBody);
        store.removeRuntime(
                companionUuid, oldBody, FollowReleaseReason.COMPANION_DEATH);
        TrackingControl respawnedRuntime =
                bind(store, companionUuid, respawnedBody);

        for (int attempt = 0; attempt < 3; attempt++) {
            store.removeRuntime(
                    companionUuid,
                    oldBody,
                    FollowReleaseReason.COMPANION_REMOVED);
            store.removeRuntime(
                    companionUuid,
                    oldRuntime,
                    FollowReleaseReason.COMPANION_DEATH);
        }

        assertCurrent(store, companionUuid, respawnedRuntime);
        assertEquals(List.of(), respawnedRuntime.reasons);
    }

    @Test
    void runtimeReplacementStaysVisibleDuringAndAfterOldCallback() {
        UUID companionUuid = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        Object body = new Object();
        TrackingControl oldRuntime = new TrackingControl(companionUuid);
        TrackingControl replacement = new TrackingControl(companionUuid);
        store.bindRuntime(companionUuid, body, oldRuntime);
        oldRuntime.onRelease = () -> {
            assertCurrent(store, companionUuid, replacement);
            store.removeRuntime(
                    companionUuid,
                    oldRuntime,
                    FollowReleaseReason.COMPANION_REMOVED);
            assertCurrent(store, companionUuid, replacement);
        };

        store.bindRuntime(companionUuid, body, replacement);
        store.removeRuntime(
                companionUuid,
                oldRuntime,
                FollowReleaseReason.COMPANION_REMOVED);

        assertCurrent(store, companionUuid, replacement);
        assertEquals(
                List.of(FollowReleaseReason.RUNTIME_REPLACED),
                oldRuntime.reasons);
        assertEquals(List.of(), replacement.reasons);
    }

    @Test
    void rebindingSameRuntimeToNewBodyRefreshesLifecycleIdentity() {
        UUID companionUuid = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        Object oldBody = new Object();
        Object newBody = new Object();
        TrackingControl runtime = new TrackingControl(companionUuid);
        store.bindRuntime(companionUuid, oldBody, runtime);

        store.bindRuntime(companionUuid, newBody, runtime);
        store.removeRuntime(
                companionUuid,
                oldBody,
                FollowReleaseReason.COMPANION_REMOVED);

        assertCurrent(store, companionUuid, runtime);
        assertEquals(List.of(), runtime.reasons);

        store.removeRuntime(
                companionUuid,
                newBody,
                FollowReleaseReason.COMPANION_REMOVED);

        assertTrue(store.runtimeControl(companionUuid).isEmpty());
        assertEquals(
                List.of(FollowReleaseReason.COMPANION_REMOVED),
                runtime.reasons);
    }

    @Test
    void equalButDistinctBodyIdentityIsStale() {
        UUID companionUuid = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        EqualIdentity currentBody = new EqualIdentity("body");
        EqualIdentity staleEqualBody = new EqualIdentity("body");
        TrackingControl runtime = bind(store, companionUuid, currentBody);

        store.removeRuntime(
                companionUuid,
                staleEqualBody,
                FollowReleaseReason.COMPANION_REMOVED);

        assertCurrent(store, companionUuid, runtime);
        assertEquals(List.of(), runtime.reasons);
    }

    @Test
    void differentCompanionUuidsRemainGenerationIsolated() {
        UUID firstUuid = UUID.randomUUID();
        UUID secondUuid = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        Object firstBody = new Object();
        Object secondBody = new Object();
        TrackingControl first = bind(store, firstUuid, firstBody);
        TrackingControl second = bind(store, secondUuid, secondBody);

        store.removeRuntime(
                firstUuid, firstBody, FollowReleaseReason.COMPANION_REMOVED);

        assertTrue(store.runtimeControl(firstUuid).isEmpty());
        assertCurrent(store, secondUuid, second);
        assertEquals(
                List.of(FollowReleaseReason.COMPANION_REMOVED),
                first.reasons);
        assertEquals(List.of(), second.reasons);
    }

    @Test
    void staleCallbackDoesNotModifyPersistentFollowState() {
        UUID companionUuid = UUID.randomUUID();
        FollowState intent = new FollowState(
                true,
                true,
                FollowState.CURRENT_SCHEMA_VERSION,
                2.5,
                6.5);
        FollowStateStore store = new FollowStateStore();
        store.put(companionUuid, intent);
        store.setDirty(false);
        Object currentBody = new Object();
        TrackingControl runtime =
                bind(store, companionUuid, currentBody);

        store.removeRuntime(
                companionUuid,
                new Object(),
                FollowReleaseReason.COMPANION_REMOVED);

        assertCurrent(store, companionUuid, runtime);
        assertEquals(intent, store.getOrDefault(companionUuid));
        assertFalse(store.isDirty());
    }

    @Test
    void serverStoppingStillReleasesEveryCurrentRuntime() {
        FollowStateStore store = new FollowStateStore();
        TrackingControl first =
                bind(store, UUID.randomUUID(), new Object());
        TrackingControl second =
                bind(store, UUID.randomUUID(), new Object());

        int failures = store.releaseAllRuntime(
                FollowReleaseReason.SERVER_STOPPING);

        assertEquals(0, failures);
        assertEquals(0, store.runtimeControlCount());
        assertEquals(
                List.of(FollowReleaseReason.SERVER_STOPPING),
                first.reasons);
        assertEquals(
                List.of(FollowReleaseReason.SERVER_STOPPING),
                second.reasons);
    }

    @Test
    void releaseFailureStillLeavesConditionallyRemovedRegistryEmpty() {
        UUID companionUuid = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        Object body = new Object();
        TrackingControl faulty = bind(store, companionUuid, body);
        faulty.throwOnRelease = true;

        store.removeRuntime(
                companionUuid, body, FollowReleaseReason.COMPANION_REMOVED);

        assertTrue(store.runtimeControl(companionUuid).isEmpty());
        assertEquals(
                List.of(FollowReleaseReason.COMPANION_REMOVED),
                faulty.reasons);
    }

    @Test
    void staleIdentityNeverInvokesFaultyCurrentRuntime() {
        UUID companionUuid = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        Object currentBody = new Object();
        TrackingControl faulty =
                bind(store, companionUuid, currentBody);
        faulty.throwOnRelease = true;

        store.removeRuntime(
                companionUuid,
                new Object(),
                FollowReleaseReason.COMPANION_REMOVED);

        assertCurrent(store, companionUuid, faulty);
        assertEquals(List.of(), faulty.reasons);
    }

    private static TrackingControl bind(
            FollowStateStore store, UUID companionUuid, Object identity) {
        TrackingControl control = new TrackingControl(companionUuid);
        store.bindRuntime(companionUuid, identity, control);
        return control;
    }

    private static void assertCurrent(
            FollowStateStore store,
            UUID companionUuid,
            FollowRuntimeControl expected) {
        assertSame(
                expected,
                store.runtimeControl(companionUuid).orElseThrow());
    }

    private record EqualIdentity(String value) {}

    private static final class TrackingControl implements FollowRuntimeControl {
        private final UUID companionUuid;
        private final List<FollowReleaseReason> reasons = new ArrayList<>();
        private Runnable onRelease = () -> {};
        private boolean throwOnRelease;

        private TrackingControl(UUID companionUuid) {
            this.companionUuid = companionUuid;
        }

        @Override
        public UUID companionUuid() {
            return companionUuid;
        }

        @Override
        public void release(FollowReleaseReason reason) {
            reasons.add(reason);
            onRelease.run();
            if (throwOnRelease) {
                throw new IllegalStateException("deliberate release failure");
            }
        }

        @Override
        public FollowRuntimeSnapshot snapshot(long currentGameTime) {
            return new FollowRuntimeSnapshot(
                    FollowRuntimeState.FOLLOWING,
                    FollowWaitingReason.NONE,
                    true,
                    false,
                    false,
                    false,
                    FollowDecisions.NO_FAILED_COOLDOWN,
                    0L);
        }
    }
}
