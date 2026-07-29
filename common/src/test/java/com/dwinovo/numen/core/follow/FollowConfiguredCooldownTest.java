package com.dwinovo.numen.core.follow;

import static com.dwinovo.numen.core.follow.OwnerFollowChainTestHarness.activeAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;

class FollowConfiguredCooldownTest {

    private static final FollowConfig CUSTOM = new FollowConfig(
            2.0, 4.0, 9.0, 20.0, 50.0, 240L,
            false, false, false, false, false);

    @ParameterizedTest(name = "{0}")
    @MethodSource("configs")
    void configuredDeadlineHasExactRetryBoundary(
            String name, FollowConfig config) {
        long failureTick = 500L;
        FollowDecisions.Result active = decide(
                config, config.startDistance(), failureTick,
                FollowDecisions.NO_FAILED_COOLDOWN);

        FollowDecisions.Result failed =
                FollowDecisions.failedAt(active, failureTick, config);
        long deadline = failureTick + config.failedCooldownTicks();
        FollowDecisions.Result before = decide(
                config, config.startDistance(), deadline - 1L, deadline);
        FollowDecisions.Result exact = decide(
                config, config.startDistance(), deadline, deadline);
        FollowDecisions.Result after = decide(
                config, config.startDistance(), deadline + 1L, deadline);

        assertEquals(deadline, failed.failedUntilTick(), name);
        assertEquals(FollowRuntimeState.FAILED_COOLDOWN,
                before.runtimeState(), name);
        assertEquals(1L, FollowDecisions.remainingCooldownTicks(
                deadline, deadline - 1L), name);
        assertEquals(FollowRuntimeState.FOLLOWING,
                exact.runtimeState(), name);
        assertEquals(FollowRuntimeState.FOLLOWING,
                after.runtimeState(), name);
        assertEquals(0L, FollowDecisions.remainingCooldownTicks(
                deadline, deadline), name);
        assertEquals(0L, FollowDecisions.remainingCooldownTicks(
                deadline, deadline + 1L), name);
    }

    @ParameterizedTest
    @EnumSource(value = FollowReleaseReason.class,
            names = {"FOLLOW_DISABLED", "MANUAL_PAUSE"})
    void offAndPauseReleaseClearExistingFailureDeadline(
            FollowReleaseReason reason) {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(activeAt(8.0, 50L));
        harness.factory.nextStatus = PlayerNav.Status.FAILED;
        harness.chain.tick(null);
        assertEquals(150L, harness.chain.runtimeView().failedUntilTick());

        harness.chain.release(reason);

        FollowRuntimeSnapshot snapshot = harness.chain.runtimeView();
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN,
                snapshot.failedUntilTick());
        assertEquals(0L, snapshot.remainingCooldownTicks());
        assertFalse(snapshot.following());
        assertFalse(snapshot.navigationActive());
        if (reason == FollowReleaseReason.FOLLOW_DISABLED) {
            assertEquals(FollowRuntimeState.DISABLED, snapshot.runtimeState());
        } else {
            assertEquals(FollowRuntimeState.MANUALLY_PAUSED,
                    snapshot.runtimeState());
        }
    }

    @Test
    void threeDimensionalDistanceCanLandExactlyOnConfiguredBoundary() {
        double distance = FollowDecisions.distance3d(
                0.0, 0.0, 0.0, 0.0, CUSTOM.startDistance(), 0.0);

        FollowDecisions.Result result = decide(
                CUSTOM, distance, 10L, FollowDecisions.NO_FAILED_COOLDOWN);

        assertEquals(CUSTOM.startDistance(), distance);
        assertEquals(FollowRuntimeState.FOLLOWING, result.runtimeState());
        assertTrue(result.following());
    }

    private static Stream<Arguments> configs() {
        return Stream.of(
                Arguments.of("default", FollowConfig.defaults()),
                Arguments.of("custom", CUSTOM));
    }

    private static FollowDecisions.Result decide(
            FollowConfig config,
            double distance,
            long currentTick,
            long failedUntilTick) {
        return FollowDecisions.decide(
                new FollowDecisions.Input(
                        true, false, true, true, true, true, true,
                        distance, null, null, currentTick),
                true,
                failedUntilTick,
                config);
    }
}
