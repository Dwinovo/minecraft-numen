package com.dwinovo.numen.core.follow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FollowDecisionBoundaryMatrixTest {

    private static final double EPSILON = 1.0e-6;
    private static final FollowConfig DEFAULT = FollowConfig.defaults();
    private static final FollowConfig CUSTOM = new FollowConfig(
            2.0, 4.0, 9.0, 20.0, 50.0, 240L,
            false, false, false, false, false);

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("exactBoundaryCases")
    void exactThresholdAndBothSidesUseApprovedComparisons(
            String configName,
            String boundary,
            FollowConfig config,
            double distance,
            FollowRuntimeState expectedState,
            FollowWaitingReason expectedReason,
            boolean expectedFollowing,
            boolean expectedSprint,
            boolean expectedCatchUp) {
        FollowDecisions.Result result = decide(config, distance, false);

        assertEquals(expectedState, result.runtimeState(),
                configName + " " + boundary);
        assertEquals(expectedReason, result.waitingReason(),
                configName + " " + boundary);
        assertEquals(expectedFollowing, result.following(),
                configName + " " + boundary);
        assertEquals(expectedSprint, result.sprintAllowed(),
                configName + " " + boundary);
        assertEquals(expectedCatchUp, result.catchingUp(),
                configName + " " + boundary);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configs")
    void hysteresisBandPreservesBothClosedAndOpenLatch(
            String configName, FollowConfig config) {
        double midpoint = (config.stopDistance() + config.startDistance()) / 2.0;

        FollowDecisions.Result closed = decide(config, midpoint, false);
        FollowDecisions.Result open = decide(config, midpoint, true);

        assertEquals(FollowRuntimeState.IDLE_NEAR_OWNER,
                closed.runtimeState(), configName);
        assertFalse(closed.following(), configName);
        assertEquals(FollowRuntimeState.FOLLOWING,
                open.runtimeState(), configName);
        assertTrue(open.following(), configName);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCustomOverrides")
    void everyInvalidOverrideFallsBackAsOnePair(
            String label, Double stopOverride, Double startOverride) {
        FollowDecisions.Distances distances =
                FollowDecisions.resolveDistances(
                        stopOverride, startOverride, CUSTOM);

        assertEquals(
                new FollowDecisions.Distances(
                        CUSTOM.stopDistance(), CUSTOM.startDistance()),
                distances,
                label);
    }

    @Test
    void validOverrideChangesOnlyStopAndStart() {
        FollowDecisions.Distances override =
                FollowDecisions.resolveDistances(2.5, 6.5, CUSTOM);
        FollowDecisions.Result beforeSprint = decide(
                CUSTOM, 8.999999, false, 2.5, 6.5);
        FollowDecisions.Result atSprint = decide(
                CUSTOM, 9.0, false, 2.5, 6.5);
        FollowDecisions.Result atCatchUp = decide(
                CUSTOM, 20.0, false, 2.5, 6.5);
        FollowDecisions.Result atLost = decide(
                CUSTOM, 50.0, true, 2.5, 6.5);
        FollowDecisions.Result failed =
                FollowDecisions.failedAt(atSprint, 10L, CUSTOM);

        assertEquals(new FollowDecisions.Distances(2.5, 6.5), override);
        assertFalse(beforeSprint.sprintAllowed());
        assertTrue(atSprint.sprintAllowed());
        assertTrue(atCatchUp.catchingUp());
        assertEquals(FollowWaitingReason.OWNER_TOO_FAR,
                atLost.waitingReason());
        assertEquals(250L, failed.failedUntilTick());
    }

    @Test
    void overrideCanBeLegalForDefaultButIllegalForCustomSprint() {
        FollowDecisions.Distances acceptedByDefault =
                FollowDecisions.resolveDistances(4.0, 10.0, DEFAULT);
        FollowDecisions.Distances rejectedByCustom =
                FollowDecisions.resolveDistances(4.0, 10.0, CUSTOM);

        assertEquals(new FollowDecisions.Distances(4.0, 10.0),
                acceptedByDefault);
        assertEquals(new FollowDecisions.Distances(2.0, 4.0),
                rejectedByCustom);
    }

    private static Stream<Arguments> exactBoundaryCases() {
        return Stream.concat(
                boundaryCases("default", DEFAULT),
                boundaryCases("custom", CUSTOM));
    }

    private static Stream<Arguments> boundaryCases(
            String name, FollowConfig config) {
        return Stream.of(
                boundary(name, "stop-epsilon", config,
                        config.stopDistance() - EPSILON,
                        FollowRuntimeState.IDLE_NEAR_OWNER,
                        FollowWaitingReason.NONE, false, false, false),
                boundary(name, "stop", config,
                        config.stopDistance(),
                        FollowRuntimeState.IDLE_NEAR_OWNER,
                        FollowWaitingReason.NONE, false, false, false),
                boundary(name, "stop+epsilon", config,
                        config.stopDistance() + EPSILON,
                        FollowRuntimeState.IDLE_NEAR_OWNER,
                        FollowWaitingReason.NONE, false, false, false),
                boundary(name, "start-epsilon", config,
                        config.startDistance() - EPSILON,
                        FollowRuntimeState.IDLE_NEAR_OWNER,
                        FollowWaitingReason.NONE, false, false, false),
                boundary(name, "start", config,
                        config.startDistance(),
                        FollowRuntimeState.FOLLOWING,
                        FollowWaitingReason.NONE, true, false, false),
                boundary(name, "start+epsilon", config,
                        config.startDistance() + EPSILON,
                        FollowRuntimeState.FOLLOWING,
                        FollowWaitingReason.NONE, true, false, false),
                boundary(name, "sprint-epsilon", config,
                        config.sprintDistance() - EPSILON,
                        FollowRuntimeState.FOLLOWING,
                        FollowWaitingReason.NONE, true, false, false),
                boundary(name, "sprint", config,
                        config.sprintDistance(),
                        FollowRuntimeState.FOLLOWING,
                        FollowWaitingReason.NONE, true, true, false),
                boundary(name, "sprint+epsilon", config,
                        config.sprintDistance() + EPSILON,
                        FollowRuntimeState.FOLLOWING,
                        FollowWaitingReason.NONE, true, true, false),
                boundary(name, "catch-up-epsilon", config,
                        config.catchUpDistance() - EPSILON,
                        FollowRuntimeState.FOLLOWING,
                        FollowWaitingReason.NONE, true, true, false),
                boundary(name, "catch-up", config,
                        config.catchUpDistance(),
                        FollowRuntimeState.FOLLOWING,
                        FollowWaitingReason.NONE, true, true, true),
                boundary(name, "catch-up+epsilon", config,
                        config.catchUpDistance() + EPSILON,
                        FollowRuntimeState.FOLLOWING,
                        FollowWaitingReason.NONE, true, true, true),
                boundary(name, "lost-epsilon", config,
                        config.lostDistance() - EPSILON,
                        FollowRuntimeState.FOLLOWING,
                        FollowWaitingReason.NONE, true, true, true),
                boundary(name, "lost", config,
                        config.lostDistance(),
                        FollowRuntimeState.WAITING_FOR_OWNER,
                        FollowWaitingReason.OWNER_TOO_FAR,
                        false, false, false),
                boundary(name, "lost+epsilon", config,
                        config.lostDistance() + EPSILON,
                        FollowRuntimeState.WAITING_FOR_OWNER,
                        FollowWaitingReason.OWNER_TOO_FAR,
                        false, false, false));
    }

    private static Arguments boundary(
            String name,
            String boundary,
            FollowConfig config,
            double distance,
            FollowRuntimeState state,
            FollowWaitingReason reason,
            boolean following,
            boolean sprint,
            boolean catchUp) {
        return Arguments.of(
                name, boundary, config, distance,
                state, reason, following, sprint, catchUp);
    }

    private static Stream<Arguments> configs() {
        return Stream.of(
                Arguments.of("default", DEFAULT),
                Arguments.of("custom", CUSTOM));
    }

    private static Stream<Arguments> invalidCustomOverrides() {
        return Stream.of(
                Arguments.of("only stop", 2.5, null),
                Arguments.of("only start", null, 6.5),
                Arguments.of("NaN", Double.NaN, 6.5),
                Arguments.of("infinite stop", Double.POSITIVE_INFINITY, 6.5),
                Arguments.of("infinite start", 2.5, Double.POSITIVE_INFINITY),
                Arguments.of("negative", -1.0, 6.5),
                Arguments.of("equal", 6.5, 6.5),
                Arguments.of("stop above start", 7.0, 6.5),
                Arguments.of("start equals sprint", 2.5, 9.0),
                Arguments.of("start above sprint", 2.5, 9.000001));
    }

    private static FollowDecisions.Result decide(
            FollowConfig config, double distance, boolean wasFollowing) {
        return decide(config, distance, wasFollowing, null, null);
    }

    private static FollowDecisions.Result decide(
            FollowConfig config,
            double distance,
            boolean wasFollowing,
            Double stopOverride,
            Double startOverride) {
        return FollowDecisions.decide(
                new FollowDecisions.Input(
                        true, false, true, true, true, true, true,
                        distance, stopOverride, startOverride, 10L),
                wasFollowing,
                FollowDecisions.NO_FAILED_COOLDOWN,
                config);
    }
}
