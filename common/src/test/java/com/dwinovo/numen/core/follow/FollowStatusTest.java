package com.dwinovo.numen.core.follow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class FollowStatusTest {

    private static final FollowConfig CONFIG = new FollowConfig(
            2.0, 4.0, 9.0, 20.0, 50.0, 240L,
            false, false, false, false, false);

    @Test
    void existingRuntimeMapsCompleteSnapshot() {
        Fixture fixture = fixture(enabled(), OptionalDouble.of(13.0));
        fixture.store.bindRuntime(fixture.uuid, new FixedControl(
                fixture.uuid,
                new FollowRuntimeSnapshot(
                        FollowRuntimeState.FOLLOWING,
                        FollowWaitingReason.NONE,
                        true, true, true, true, 250L, 200L)));

        FollowStatus status = fixture.status();

        assertTrue(status.runtimeAvailable());
        assertEquals(FollowRuntimeState.FOLLOWING, status.runtimeState());
        assertTrue(status.following());
        assertTrue(status.navigationActive());
        assertTrue(status.sprintAllowed());
        assertTrue(status.catchingUp());
        assertEquals(200L, status.remainingCooldownTicks());
    }

    @Test
    void missingRuntimeDoesNotCreateOrBindOne() {
        Fixture fixture = fixture(enabled(), OptionalDouble.of(8.0));

        FollowStatus status = fixture.status();

        assertFalse(status.runtimeAvailable());
        assertEquals(0, fixture.store.runtimeControlCount());
    }

    @Test
    void missingRuntimeDerivesDisabledState() {
        assertEquals(FollowRuntimeState.DISABLED,
                fixture(FollowState.defaults(), OptionalDouble.of(8.0))
                        .status().runtimeState());
    }

    @Test
    void missingRuntimeDerivesManualPauseState() {
        FollowState paused = new FollowState(
                true, true, FollowState.CURRENT_SCHEMA_VERSION, null, null);

        FollowStatus status =
                fixture(paused, OptionalDouble.of(8.0)).status();

        assertEquals(FollowRuntimeState.MANUALLY_PAUSED, status.runtimeState());
    }

    @Test
    void missingRuntimeEnabledStateUsesDocumentedConservativeWaitingState() {
        FollowStatus status =
                fixture(enabled(), OptionalDouble.of(8.0)).status();

        assertEquals(FollowRuntimeState.WAITING_FOR_OWNER, status.runtimeState());
        assertEquals(FollowWaitingReason.OWNER_INVALID, status.waitingReason());
    }

    @Test
    void statusCarriesTrueThreeDimensionalDistance() {
        double distance =
                FollowDecisions.distance3d(0.0, 0.0, 0.0, 3.0, 4.0, 12.0);
        FollowStatus status =
                fixture(enabled(), OptionalDouble.of(distance)).status();

        assertEquals(13.0, status.distance().orElseThrow());
    }

    @Test
    void ownerOfflineHasUnavailableDistanceAndExplicitReason() {
        Fixture fixture = fixture(enabled(), OptionalDouble.empty());
        fixture.subject = new FollowService.Subject(
                fixture.uuid, "Numen", true, true, false, false, false,
                OptionalDouble.empty(), 50L);

        FollowStatus status = fixture.status();

        assertFalse(status.ownerOnline());
        assertTrue(status.distance().isEmpty());
        assertEquals(FollowWaitingReason.OWNER_OFFLINE, status.waitingReason());
    }

    @Test
    void otherDimensionHasUnavailableDistanceAndExplicitReason() {
        Fixture fixture = fixture(enabled(), OptionalDouble.empty());
        fixture.subject = new FollowService.Subject(
                fixture.uuid, "Numen", true, true, true, true, false,
                OptionalDouble.empty(), 50L);

        FollowStatus status = fixture.status();

        assertFalse(status.sameDimension());
        assertTrue(status.distance().isEmpty());
        assertEquals(
                FollowWaitingReason.OWNER_OTHER_DIMENSION,
                status.waitingReason());
    }

    @Test
    void negativeCooldownCanNeverEscapeSnapshotOrStatus() {
        Fixture fixture = fixture(enabled(), OptionalDouble.of(8.0));
        fixture.store.bindRuntime(fixture.uuid, new FixedControl(
                fixture.uuid,
                new FollowRuntimeSnapshot(
                        FollowRuntimeState.FAILED_COOLDOWN,
                        FollowWaitingReason.NONE,
                        true, false, false, false, 1L, -5L)));

        assertEquals(0L, fixture.status().remainingCooldownTicks());
    }

    @Test
    void effectiveCompanionOverridesAndGlobalThresholdsAreReported() {
        FollowState override = new FollowState(
                true, false, FollowState.CURRENT_SCHEMA_VERSION, 2.5, 6.5);

        FollowStatus status =
                fixture(override, OptionalDouble.of(8.0)).status();

        assertEquals(2.5, status.effectiveStopDistance());
        assertEquals(6.5, status.effectiveStartDistance());
        assertEquals(9.0, status.sprintDistance());
        assertEquals(20.0, status.catchUpDistance());
        assertEquals(50.0, status.lostDistance());
        assertEquals(240L, status.failedCooldownTicks());
    }

    @Test
    void invalidOverridePairFallsBackToGlobalThresholds() {
        FollowState override = new FollowState(
                true, false, FollowState.CURRENT_SCHEMA_VERSION, 2.5, 9.5);

        FollowStatus status =
                fixture(override, OptionalDouble.of(8.0)).status();

        assertEquals(2.0, status.effectiveStopDistance());
        assertEquals(4.0, status.effectiveStartDistance());
    }

    @Test
    void formattingIsReadOnlyAndDoesNotExposeAnyOwnerUuid() {
        Fixture fixture = fixture(enabled(), OptionalDouble.of(8.0));
        fixture.store.setDirty(false);
        FollowStatus before = fixture.status();

        String text = before.compactText();
        FollowStatus after = fixture.status();

        assertEquals(before, after);
        assertFalse(fixture.store.isDirty());
        assertFalse(text.toLowerCase().contains("uuid"));
        assertTrue(text.contains("thresholds"));
        assertTrue(text.contains("distance=8.00"));
    }

    @Test
    void statusQueryNeverMutatesPersistentState() {
        Fixture fixture = fixture(enabled(), OptionalDouble.of(8.0));
        FollowState before = fixture.store.getOrDefault(fixture.uuid);
        fixture.store.setDirty(false);

        fixture.status();

        assertEquals(before, fixture.store.getOrDefault(fixture.uuid));
        assertFalse(fixture.store.isDirty());
    }

    private static Fixture fixture(
            FollowState state, OptionalDouble distance) {
        Fixture fixture = new Fixture();
        fixture.store.put(fixture.uuid, state);
        fixture.subject = new FollowService.Subject(
                fixture.uuid, "Numen", true, true, true, true, true,
                distance, 50L);
        return fixture;
    }

    private static FollowState enabled() {
        return new FollowState(
                true, false, FollowState.CURRENT_SCHEMA_VERSION, null, null);
    }

    private static final class Fixture {
        final UUID uuid = UUID.randomUUID();
        final FollowStateStore store = new FollowStateStore();
        FollowService.Subject subject;

        FollowStatus status() {
            return FollowService.status(store, subject, CONFIG);
        }
    }

    private record FixedControl(
            UUID companionUuid,
            FollowRuntimeSnapshot value) implements FollowRuntimeControl {

        @Override
        public void release(FollowReleaseReason reason) {}

        @Override
        public FollowRuntimeSnapshot snapshot(long currentGameTime) {
            return value;
        }
    }
}
