package com.dwinovo.numen.core.follow;

import static com.dwinovo.numen.core.follow.FollowStage7ATestSupport.reload;
import static com.dwinovo.numen.core.follow.FollowStage7ATestSupport.state;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import net.minecraft.nbt.CompoundTag;

class FollowStateRestartBoundaryTest {

    private static final FollowConfig CONFIG = FollowConfig.defaults();

    @ParameterizedTest(name = "{0}")
    @MethodSource("controlCases")
    void controlActionsPersistTheirExactRestartState(
            String label,
            FollowAction action,
            FollowState initial,
            FollowState expected) {
        UUID companion = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        store.put(companion, initial);
        store.setDirty(false);

        FollowControlResult result = FollowService.apply(
                store, activeSubject(companion), action, CONFIG);
        FollowStateStore loaded = reload(store);

        assertTrue(result.success(), label);
        assertEquals(expected, store.getOrDefault(companion), label);
        assertEquals(expected, loaded.getOrDefault(companion), label);
        assertEquals(FollowState.CURRENT_SCHEMA_VERSION,
                loaded.getOrDefault(companion).schemaVersion(), label);
        assertEquals(2.5,
                loaded.getOrDefault(companion).stopDistanceOverride(), label);
        assertEquals(6.5,
                loaded.getOrDefault(companion).startDistanceOverride(), label);
    }

    @ParameterizedTest
    @EnumSource(value = FollowAction.class, names = {"OFF", "PAUSE"})
    void releaseSideEffectsNeverEnterRestartedStore(FollowAction action) {
        UUID companion = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        store.put(companion, state(true, false, 2.5, 6.5));
        FollowStage7ATestSupport.TrackingControl control =
                new FollowStage7ATestSupport.TrackingControl(
                        companion,
                        new FollowRuntimeSnapshot(
                                FollowRuntimeState.FOLLOWING,
                                FollowWaitingReason.NONE,
                                true, true, true, false, 400L, 300L));
        store.bindRuntime(companion, control);

        FollowService.apply(store, activeSubject(companion), action, CONFIG);
        FollowStateStore loaded = reload(store);

        FollowReleaseReason expectedReason = action == FollowAction.OFF
                ? FollowReleaseReason.FOLLOW_DISABLED
                : FollowReleaseReason.MANUAL_PAUSE;
        assertEquals(List.of(expectedReason), control.reasons());
        assertEquals(0, loaded.runtimeControlCount());
        assertEquals(action == FollowAction.PAUSE,
                loaded.getOrDefault(companion).enabled());
        assertEquals(action == FollowAction.PAUSE,
                loaded.getOrDefault(companion).manualPaused());
    }

    @Test
    void failedCooldownLatchAndRuntimeStateNeverRestart() {
        UUID companion = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        store.put(companion, state(true, false, 2.5, 6.5));
        store.bindRuntime(
                companion,
                new FollowStage7ATestSupport.TrackingControl(
                        companion,
                        new FollowRuntimeSnapshot(
                                FollowRuntimeState.FAILED_COOLDOWN,
                                FollowWaitingReason.NONE,
                                true, false, false, false, 500L, 400L)));

        FollowStateStore loaded = reload(store);
        FollowStatus status = FollowService.status(
                loaded,
                FollowStage7ATestSupport.subject(
                        companion, true, false, false, false,
                        OptionalDouble.empty(), 100L),
                CONFIG);

        assertFalse(status.runtimeAvailable());
        assertFalse(status.following());
        assertFalse(status.navigationActive());
        assertEquals(0L, status.remainingCooldownTicks());
        assertEquals(FollowRuntimeState.WAITING_FOR_OWNER, status.runtimeState());
        assertEquals(FollowWaitingReason.OWNER_OFFLINE, status.waitingReason());
        assertEquals(0, loaded.runtimeControlCount());
    }

    @Test
    void noOpControlAndStatusAreSerializationNeutral() {
        UUID companion = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        FollowState expected = state(true, false, 2.5, 6.5);
        store.put(companion, expected);
        String before = store.save(new CompoundTag()).toString();
        store.setDirty(false);

        FollowControlResult on = FollowService.apply(
                store, activeSubject(companion), FollowAction.ON, CONFIG);
        FollowControlResult status = FollowService.apply(
                store, activeSubject(companion), FollowAction.STATUS, CONFIG);
        String after = store.save(new CompoundTag()).toString();

        assertFalse(on.changed());
        assertFalse(status.changed());
        assertFalse(store.isDirty());
        assertEquals(expected, store.getOrDefault(companion));
        assertEquals(before, after);
    }

    private static Stream<Arguments> controlCases() {
        FollowState paused = state(true, true, 2.5, 6.5);
        FollowState enabled = state(true, false, 2.5, 6.5);
        FollowState disabled = state(false, false, 2.5, 6.5);
        return Stream.of(
                Arguments.of("ON", FollowAction.ON, paused, enabled),
                Arguments.of("OFF", FollowAction.OFF, paused, disabled),
                Arguments.of("PAUSE", FollowAction.PAUSE, enabled, paused),
                Arguments.of("RESUME", FollowAction.RESUME, disabled, enabled));
    }

    private static FollowService.Subject activeSubject(UUID companion) {
        return FollowStage7ATestSupport.subject(
                companion, true, true, true, true,
                OptionalDouble.of(8.0), 100L);
    }
}
