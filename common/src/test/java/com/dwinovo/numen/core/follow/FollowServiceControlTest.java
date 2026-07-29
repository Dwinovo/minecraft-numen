package com.dwinovo.numen.core.follow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class FollowServiceControlTest {

    private static final FollowConfig CONFIG = FollowConfig.defaults();

    @Test
    void onAtomicallyEnablesAndClearsPause() {
        Fixture fixture = fixture(new FollowState(
                true, true, FollowState.CURRENT_SCHEMA_VERSION, 3.5, 6.0));

        FollowControlResult result = fixture.apply(FollowAction.ON);

        assertEquals(new FollowState(
                        true, false, FollowState.CURRENT_SCHEMA_VERSION, 3.5, 6.0),
                fixture.state());
        assertEquals("ENABLED", result.code());
        assertTrue(result.changed());
        assertTrue(fixture.store.isDirty());
    }

    @Test
    void onDoesNotCreateNavigationRuntimeOrCallRelease() {
        Fixture fixture = fixture(FollowState.defaults());

        fixture.apply(FollowAction.ON);

        assertEquals(0, fixture.store.runtimeControlCount());
    }

    @Test
    void repeatedOnIsIdempotentWithoutDirtying() {
        Fixture fixture = fixture(enabled());
        fixture.store.setDirty(false);

        FollowControlResult result = fixture.apply(FollowAction.ON);

        assertEquals("ALREADY_ENABLED", result.code());
        assertFalse(result.changed());
        assertFalse(fixture.store.isDirty());
    }

    @Test
    void offAtomicallyDisablesClearsPauseAndReleasesImmediately() {
        Fixture fixture = fixture(new FollowState(
                true, true, FollowState.CURRENT_SCHEMA_VERSION, null, null));
        FakeControl control = fixture.bindControl();

        FollowControlResult result = fixture.apply(FollowAction.OFF);

        assertEquals(new FollowState(
                        false, false, FollowState.CURRENT_SCHEMA_VERSION, null, null),
                fixture.state());
        assertEquals(List.of(FollowReleaseReason.FOLLOW_DISABLED), control.reasons);
        assertEquals("DISABLED", result.code());
    }

    @Test
    void alreadyOffStillDefensivelyReleasesAndRetainsBinding() {
        Fixture fixture = fixture(FollowState.defaults());
        FakeControl control = fixture.bindControl();
        fixture.store.setDirty(false);

        FollowControlResult result = fixture.apply(FollowAction.OFF);

        assertEquals("ALREADY_DISABLED", result.code());
        assertFalse(result.changed());
        assertFalse(fixture.store.isDirty());
        assertEquals(List.of(FollowReleaseReason.FOLLOW_DISABLED), control.reasons);
        assertSame(control,
                fixture.store.runtimeControl(fixture.uuid).orElseThrow());
    }

    @Test
    void pauseRequiresEnabledAndDoesNotReleaseWhenRejected() {
        Fixture fixture = fixture(FollowState.defaults());
        FakeControl control = fixture.bindControl();
        fixture.store.setDirty(false);

        FollowControlResult result = fixture.apply(FollowAction.PAUSE);

        assertFalse(result.success());
        assertEquals("PAUSE_REQUIRES_ENABLED", result.code());
        assertFalse(result.changed());
        assertEquals(List.of(), control.reasons);
        assertEquals(FollowState.defaults(), fixture.state());
        assertFalse(fixture.store.isDirty());
    }

    @Test
    void pauseEnabledStateAndReleasesImmediately() {
        Fixture fixture = fixture(enabled());
        FakeControl control = fixture.bindControl();

        FollowControlResult result = fixture.apply(FollowAction.PAUSE);

        assertEquals(new FollowState(
                        true, true, FollowState.CURRENT_SCHEMA_VERSION, null, null),
                fixture.state());
        assertEquals("PAUSED", result.code());
        assertEquals(List.of(FollowReleaseReason.MANUAL_PAUSE), control.reasons);
    }

    @Test
    void repeatedPauseIsIdempotentButStillDefensivelyReleases() {
        Fixture fixture = fixture(new FollowState(
                true, true, FollowState.CURRENT_SCHEMA_VERSION, null, null));
        FakeControl control = fixture.bindControl();
        fixture.store.setDirty(false);

        FollowControlResult result = fixture.apply(FollowAction.PAUSE);

        assertEquals("ALREADY_PAUSED", result.code());
        assertFalse(result.changed());
        assertFalse(fixture.store.isDirty());
        assertEquals(List.of(FollowReleaseReason.MANUAL_PAUSE), control.reasons);
    }

    @Test
    void resumeFromPausedSetsEnabledAndClearsPauseWithoutRelease() {
        Fixture fixture = fixture(new FollowState(
                true, true, FollowState.CURRENT_SCHEMA_VERSION, null, null));
        FakeControl control = fixture.bindControl();

        FollowControlResult result = fixture.apply(FollowAction.RESUME);

        assertEquals(enabled(), fixture.state());
        assertEquals("RESUMED", result.code());
        assertEquals(List.of(), control.reasons);
    }

    @Test
    void resumeFromDisabledEnablesWithoutCreatingRuntime() {
        Fixture fixture = fixture(FollowState.defaults());

        FollowControlResult result = fixture.apply(FollowAction.RESUME);

        assertEquals(enabled(), fixture.state());
        assertEquals("RESUMED", result.code());
        assertEquals(0, fixture.store.runtimeControlCount());
    }

    @Test
    void repeatedResumeIsIdempotentWithoutDirtying() {
        Fixture fixture = fixture(enabled());
        fixture.store.setDirty(false);

        FollowControlResult result = fixture.apply(FollowAction.RESUME);

        assertEquals("ALREADY_RESUMED", result.code());
        assertFalse(result.changed());
        assertFalse(fixture.store.isDirty());
    }

    @Test
    void statusIsCompletelyReadOnlyAndDoesNotCreateRuntime() {
        Fixture fixture = fixture(enabled());
        fixture.store.setDirty(false);

        FollowControlResult result = fixture.apply(FollowAction.STATUS);

        assertEquals("STATUS", result.code());
        assertFalse(result.changed());
        assertFalse(fixture.store.isDirty());
        assertEquals(0, fixture.store.runtimeControlCount());
    }

    @Test
    void everyActionPreservesSchemaAndDistanceOverrides() {
        Fixture fixture = fixture(new FollowState(
                true, false, FollowState.CURRENT_SCHEMA_VERSION, 3.5, 6.0));

        fixture.apply(FollowAction.PAUSE);
        fixture.apply(FollowAction.OFF);
        fixture.apply(FollowAction.RESUME);

        FollowState state = fixture.state();
        assertEquals(FollowState.CURRENT_SCHEMA_VERSION, state.schemaVersion());
        assertEquals(3.5, state.stopDistanceOverride());
        assertEquals(6.0, state.startDistanceOverride());
    }

    @Test
    void atomicStoreUpdateChangesBothBitsInOneVisibleValue() {
        Fixture fixture = fixture(new FollowState(
                true, true, FollowState.CURRENT_SCHEMA_VERSION, 3.5, 6.0));
        fixture.store.setDirty(false);

        boolean changed = fixture.store.setControlState(
                fixture.uuid, false, false);

        assertTrue(changed);
        assertEquals(new FollowState(
                        false, false, FollowState.CURRENT_SCHEMA_VERSION, 3.5, 6.0),
                fixture.state());
        assertTrue(fixture.store.isDirty());
    }

    @Test
    void atomicStoreNoOpDoesNotDirtyOrTouchRuntime() {
        Fixture fixture = fixture(enabled());
        FakeControl control = fixture.bindControl();
        fixture.store.setDirty(false);

        boolean changed = fixture.store.setControlState(
                fixture.uuid, true, false);

        assertFalse(changed);
        assertFalse(fixture.store.isDirty());
        assertSame(control,
                fixture.store.runtimeControl(fixture.uuid).orElseThrow());
    }

    @Test
    void releaseFailureDoesNotRollBackPersistentOffAndReturnsWarning() {
        Fixture fixture = fixture(enabled());
        FakeControl control = fixture.bindControl();
        control.throwOnRelease = true;

        FollowControlResult result = fixture.apply(FollowAction.OFF);

        assertEquals(FollowState.defaults(), fixture.state());
        assertTrue(result.success());
        assertTrue(result.changed());
        assertEquals("RUNTIME_RELEASE_WARNING", result.code());
        assertTrue(result.message().contains("未能确认完全释放"));
    }

    @Test
    void releaseFailureDoesNotRollBackPersistentPauseAndReturnsWarning() {
        Fixture fixture = fixture(enabled());
        FakeControl control = fixture.bindControl();
        control.throwOnRelease = true;

        FollowControlResult result = fixture.apply(FollowAction.PAUSE);

        assertTrue(fixture.state().enabled());
        assertTrue(fixture.state().manualPaused());
        assertEquals("RUNTIME_RELEASE_WARNING", result.code());
    }

    @Test
    void invalidCompanionOrMissingOwnerIsRejectedWithoutMutation() {
        FollowStateStore store = new FollowStateStore();
        UUID uuid = UUID.randomUUID();
        FollowService.Subject subject = new FollowService.Subject(
                uuid, "N", true, false, false, false, false,
                OptionalDouble.empty(), 1L);

        FollowControlResult result =
                FollowService.apply(store, subject, FollowAction.ON, CONFIG);

        assertFalse(result.success());
        assertEquals("INVALID_COMPANION", result.code());
        assertEquals(FollowState.defaults(), store.getOrDefault(uuid));
        assertFalse(store.isDirty());
    }

    @Test
    void statusStillExplainsMissingOwnerWithoutMutatingAnything() {
        FollowStateStore store = new FollowStateStore();
        UUID uuid = UUID.randomUUID();
        store.put(uuid, enabled());
        store.setDirty(false);
        FollowService.Subject subject = new FollowService.Subject(
                uuid, "N", true, false, false, false, false,
                OptionalDouble.empty(), 1L);

        FollowControlResult result =
                FollowService.apply(store, subject, FollowAction.STATUS, CONFIG);

        assertTrue(result.success());
        assertEquals("STATUS", result.code());
        assertEquals(FollowWaitingReason.OWNER_INVALID,
                result.status().waitingReason());
        assertFalse(store.isDirty());
        assertEquals(0, store.runtimeControlCount());
    }

    private static Fixture fixture(FollowState state) {
        Fixture fixture = new Fixture();
        fixture.store.put(fixture.uuid, state);
        return fixture;
    }

    private static FollowState enabled() {
        return new FollowState(
                true, false, FollowState.CURRENT_SCHEMA_VERSION, null, null);
    }

    private static final class Fixture {
        final UUID uuid = UUID.randomUUID();
        final FollowStateStore store = new FollowStateStore();
        final FollowService.Subject subject = new FollowService.Subject(
                uuid, "Numen", true, true, true, true, true,
                OptionalDouble.of(8.0), 50L);

        FollowControlResult apply(FollowAction action) {
            return FollowService.apply(store, subject, action, CONFIG);
        }

        FollowState state() {
            return store.getOrDefault(uuid);
        }

        FakeControl bindControl() {
            FakeControl control = new FakeControl(uuid);
            store.bindRuntime(uuid, control);
            return control;
        }
    }

    private static final class FakeControl implements FollowRuntimeControl {
        private final UUID uuid;
        private final List<FollowReleaseReason> reasons = new ArrayList<>();
        private boolean throwOnRelease;
        private FollowRuntimeSnapshot snapshot = new FollowRuntimeSnapshot(
                FollowRuntimeState.FOLLOWING,
                FollowWaitingReason.NONE,
                true, true, true, false,
                FollowDecisions.NO_FAILED_COOLDOWN, 0L);

        private FakeControl(UUID uuid) {
            this.uuid = uuid;
        }

        @Override
        public UUID companionUuid() {
            return uuid;
        }

        @Override
        public void release(FollowReleaseReason reason) {
            reasons.add(reason);
            snapshot = switch (reason) {
                case FOLLOW_DISABLED -> inactive(FollowRuntimeState.DISABLED);
                case MANUAL_PAUSE -> inactive(FollowRuntimeState.MANUALLY_PAUSED);
                default -> inactive(FollowRuntimeState.WAITING_FOR_OWNER);
            };
            if (throwOnRelease) {
                throw new IllegalStateException("deliberate release failure");
            }
        }

        @Override
        public FollowRuntimeSnapshot snapshot(long currentGameTime) {
            return snapshot;
        }

        private static FollowRuntimeSnapshot inactive(FollowRuntimeState state) {
            return new FollowRuntimeSnapshot(
                    state, FollowWaitingReason.NONE,
                    false, false, false, false,
                    FollowDecisions.NO_FAILED_COOLDOWN, 0L);
        }
    }
}
