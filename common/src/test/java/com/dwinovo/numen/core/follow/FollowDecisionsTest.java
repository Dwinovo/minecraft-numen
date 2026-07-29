package com.dwinovo.numen.core.follow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FollowDecisionsTest {

    @Test
    void constantsHaveTheRequiredOrdering() {
        assertTrue(FollowDecisions.DEFAULT_STOP_DISTANCE
                < FollowDecisions.DEFAULT_START_DISTANCE);
        assertTrue(FollowDecisions.DEFAULT_START_DISTANCE
                < FollowDecisions.DEFAULT_SPRINT_DISTANCE);
        assertTrue(FollowDecisions.DEFAULT_SPRINT_DISTANCE
                <= FollowDecisions.DEFAULT_CATCH_UP_DISTANCE);
        assertTrue(FollowDecisions.DEFAULT_CATCH_UP_DISTANCE
                < FollowDecisions.DEFAULT_LOST_DISTANCE);
        assertEquals(100L, FollowDecisions.FAILED_COOLDOWN_TICKS);
    }

    @Test
    void disabledHasExplicitRuntimeState() {
        FollowDecisions.Result result = decide(input(false, false, 20.0, 0L), true);

        assertDormant(result, FollowRuntimeState.DISABLED, FollowWaitingReason.NONE);
        assertFalse(result.following());
    }

    @Test
    void manualPauseHasExplicitRuntimeState() {
        FollowDecisions.Result result = decide(input(true, true, 20.0, 0L), true);

        assertDormant(result, FollowRuntimeState.MANUALLY_PAUSED, FollowWaitingReason.NONE);
        assertFalse(result.following());
    }

    @Test
    void inactiveCompanionWinsOverOtherConditions() {
        FollowDecisions.Input input = new FollowDecisions.Input(
                false, true, false, false, false, false, false,
                Double.NaN, null, null, 10L);

        FollowDecisions.Result result = decide(input, true);

        assertDormant(result, FollowRuntimeState.WAITING_FOR_OWNER,
                FollowWaitingReason.COMPANION_NOT_ACTIVE);
    }

    @Test
    void missingOwnerUuidIsOwnerInvalid() {
        FollowDecisions.Input input = validInput(8.0, 0L, null, null);
        input = new FollowDecisions.Input(true, false, true, false,
                false, false, false, Double.NaN, null, null, 0L);

        FollowDecisions.Result result = decide(input, true);

        assertDormant(result, FollowRuntimeState.WAITING_FOR_OWNER,
                FollowWaitingReason.OWNER_INVALID);
        assertFalse(result.following());
    }

    @Test
    void unresolvedOwnerIsOffline() {
        FollowDecisions.Input input = new FollowDecisions.Input(
                true, false, true, true, false, false, false,
                Double.NaN, null, null, 0L);

        FollowDecisions.Result result = decide(input, true);

        assertDormant(result, FollowRuntimeState.WAITING_FOR_OWNER,
                FollowWaitingReason.OWNER_OFFLINE);
    }

    @Test
    void deadOrRemovedOwnerIsInvalid() {
        FollowDecisions.Input input = new FollowDecisions.Input(
                true, false, true, true, true, false, false,
                Double.NaN, null, null, 0L);

        FollowDecisions.Result result = decide(input, true);

        assertDormant(result, FollowRuntimeState.WAITING_FOR_OWNER,
                FollowWaitingReason.OWNER_INVALID);
    }

    @Test
    void ownerInAnotherLevelHasDedicatedReason() {
        FollowDecisions.Input input = new FollowDecisions.Input(
                true, false, true, true, true, true, false,
                Double.NaN, null, null, 0L);

        FollowDecisions.Result result = decide(input, true);

        assertDormant(result, FollowRuntimeState.WAITING_FOR_OWNER,
                FollowWaitingReason.OWNER_OTHER_DIMENSION);
    }

    @Test
    void nanDistanceIsOwnerInvalid() {
        FollowDecisions.Result result = decide(input(true, false, Double.NaN, 0L), true);

        assertDormant(result, FollowRuntimeState.WAITING_FOR_OWNER,
                FollowWaitingReason.OWNER_INVALID);
    }

    @Test
    void infiniteDistanceIsOwnerInvalid() {
        FollowDecisions.Result result = decide(
                input(true, false, Double.POSITIVE_INFINITY, 0L), true);

        assertDormant(result, FollowRuntimeState.WAITING_FOR_OWNER,
                FollowWaitingReason.OWNER_INVALID);
    }

    @Test
    void exactStopDistanceIsIdleAndClosesLatch() {
        FollowDecisions.Result result = decide(
                input(true, false, FollowDecisions.DEFAULT_STOP_DISTANCE, 0L), true);

        assertDormant(result, FollowRuntimeState.IDLE_NEAR_OWNER,
                FollowWaitingReason.NONE);
        assertFalse(result.following());
    }

    @Test
    void exactStartDistanceStartsFollowing() {
        FollowDecisions.Result result = decide(
                input(true, false, FollowDecisions.DEFAULT_START_DISTANCE, 0L), false);

        assertFollowing(result);
    }

    @Test
    void hysteresisBandRetainsOpenLatch() {
        FollowDecisions.Result result = decide(input(true, false, 4.0, 0L), true);

        assertFollowing(result);
    }

    @Test
    void hysteresisBandRetainsClosedLatch() {
        FollowDecisions.Result result = decide(input(true, false, 4.0, 0L), false);

        assertDormant(result, FollowRuntimeState.IDLE_NEAR_OWNER,
                FollowWaitingReason.NONE);
    }

    @Test
    void distanceBelowSprintThresholdDisallowsSprint() {
        FollowDecisions.Result result = decide(input(true, false, 11.99, 0L), false);

        assertFollowing(result);
        assertFalse(result.sprintAllowed());
    }

    @Test
    void exactSprintThresholdAllowsSprint() {
        FollowDecisions.Result result = decide(input(true, false, 12.0, 0L), false);

        assertFollowing(result);
        assertTrue(result.sprintAllowed());
    }

    @Test
    void distanceBelowCatchUpThresholdIsNotCatchingUp() {
        FollowDecisions.Result result = decide(input(true, false, 23.99, 0L), false);

        assertTrue(result.sprintAllowed());
        assertFalse(result.catchingUp());
    }

    @Test
    void exactCatchUpThresholdEntersCatchUpMode() {
        FollowDecisions.Result result = decide(input(true, false, 24.0, 0L), false);

        assertTrue(result.sprintAllowed());
        assertTrue(result.catchingUp());
        assertEquals(FollowRuntimeState.FOLLOWING, result.runtimeState());
    }

    @Test
    void distanceJustBelowLostThresholdStillFollows() {
        FollowDecisions.Result result = decide(input(true, false, 63.99, 0L), false);

        assertFollowing(result);
        assertTrue(result.catchingUp());
    }

    @Test
    void exactLostThresholdWaitsWithoutCooldown() {
        FollowDecisions.Result result = decide(input(true, false, 64.0, 0L), true,
                100L);

        assertDormant(result, FollowRuntimeState.WAITING_FOR_OWNER,
                FollowWaitingReason.OWNER_TOO_FAR);
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN, result.failedUntilTick());
    }

    @Test
    void distanceMetricIsThreeDimensionalEuclidean() {
        assertEquals(13.0, FollowDecisions.distance3d(
                1.0, 2.0, 3.0, 4.0, 6.0, 15.0), 1.0e-9);
    }

    @Test
    void validOverridePairIsAcceptedTogether() {
        FollowDecisions.Distances distances =
                FollowDecisions.resolveDistances(4.0, 8.0);

        assertEquals(new FollowDecisions.Distances(4.0, 8.0), distances);
    }

    @Test
    void startOverrideAtSprintThresholdFallsBackAsAPair() {
        assertDefaultDistances(FollowDecisions.resolveDistances(4.0, 12.0));
    }

    @Test
    void startOverrideAboveSprintThresholdFallsBackAsAPair() {
        assertDefaultDistances(FollowDecisions.resolveDistances(4.0, 12.01));
    }

    @Test
    void partialAndNonFiniteOverridesFallBackAsAPair() {
        assertDefaultDistances(FollowDecisions.resolveDistances(null, 8.0));
        assertDefaultDistances(FollowDecisions.resolveDistances(4.0, null));
        assertDefaultDistances(FollowDecisions.resolveDistances(Double.NaN, 8.0));
        assertDefaultDistances(FollowDecisions.resolveDistances(
                4.0, Double.POSITIVE_INFINITY));
    }

    @Test
    void nonPositiveAndMisorderedOverridesFallBackAsAPair() {
        assertDefaultDistances(FollowDecisions.resolveDistances(0.0, 8.0));
        assertDefaultDistances(FollowDecisions.resolveDistances(-1.0, 8.0));
        assertDefaultDistances(FollowDecisions.resolveDistances(8.0, 8.0));
        assertDefaultDistances(FollowDecisions.resolveDistances(9.0, 8.0));
    }

    @Test
    void failedNavigationStartsExactOneHundredTickCooldown() {
        FollowDecisions.Result active = decide(input(true, false, 8.0, 50L), false);

        FollowDecisions.Result failed = FollowDecisions.failedAt(active, 50L);

        assertEquals(FollowRuntimeState.FAILED_COOLDOWN, failed.runtimeState());
        assertEquals(150L, failed.failedUntilTick());
        assertFalse(failed.sprintAllowed());
        assertFalse(failed.catchingUp());
    }

    @Test
    void cooldownIsActiveOneTickBeforeDeadline() {
        FollowDecisions.Result result = decide(input(true, false, 8.0, 149L),
                true, 150L);

        assertDormant(result, FollowRuntimeState.FAILED_COOLDOWN,
                FollowWaitingReason.NONE);
        assertEquals(150L, result.failedUntilTick());
    }

    @Test
    void cooldownExpiresExactlyAtDeadline() {
        FollowDecisions.Result result = decide(input(true, false, 8.0, 150L),
                true, 150L);

        assertFollowing(result);
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN, result.failedUntilTick());
    }

    @Test
    void repeatedFailureResetsDeadlineFromCurrentTick() {
        FollowDecisions.Result active = decide(input(true, false, 8.0, 150L), true);

        FollowDecisions.Result failedAgain = FollowDecisions.failedAt(active, 150L);

        assertEquals(250L, failedAgain.failedUntilTick());
    }

    @Test
    void disabledClearsExistingCooldown() {
        FollowDecisions.Result result = decide(input(false, false, 8.0, 60L),
                true, 150L);

        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN, result.failedUntilTick());
    }

    @Test
    void ownerInvalidClearsExistingCooldown() {
        FollowDecisions.Input input = new FollowDecisions.Input(
                true, false, true, true, true, false, false,
                Double.NaN, null, null, 60L);

        FollowDecisions.Result result = decide(input, true, 150L);

        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN, result.failedUntilTick());
    }

    @Test
    void everyIneligibleWaitingStateClearsExistingCooldown() {
        long deadline = 150L;
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN,
                decide(input(true, true, 8.0, 60L), true, deadline)
                        .failedUntilTick());
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN,
                decide(new FollowDecisions.Input(true, false, false, true,
                        true, true, true, 8.0, null, null, 60L),
                        true, deadline).failedUntilTick());
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN,
                decide(new FollowDecisions.Input(true, false, true, false,
                        false, false, false, Double.NaN, null, null, 60L),
                        true, deadline).failedUntilTick());
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN,
                decide(new FollowDecisions.Input(true, false, true, true,
                        false, false, false, Double.NaN, null, null, 60L),
                        true, deadline).failedUntilTick());
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN,
                decide(new FollowDecisions.Input(true, false, true, true,
                        true, true, false, Double.NaN, null, null, 60L),
                        true, deadline).failedUntilTick());
    }

    @Test
    void stopDistanceClearsExistingCooldownBeforeCooldownCheck() {
        FollowDecisions.Result result = decide(input(true, false, 3.0, 60L),
                true, 150L);

        assertEquals(FollowRuntimeState.IDLE_NEAR_OWNER, result.runtimeState());
        assertEquals(FollowDecisions.NO_FAILED_COOLDOWN, result.failedUntilTick());
    }

    @Test
    void remainingCooldownUsesExactDeadlineBoundary() {
        assertEquals(100L, FollowDecisions.remainingCooldownTicks(150L, 50L));
        assertEquals(1L, FollowDecisions.remainingCooldownTicks(150L, 149L));
        assertEquals(0L, FollowDecisions.remainingCooldownTicks(150L, 150L));
        assertEquals(0L, FollowDecisions.remainingCooldownTicks(
                FollowDecisions.NO_FAILED_COOLDOWN, 50L));
    }

    private static FollowDecisions.Result decide(
            FollowDecisions.Input input, boolean wasFollowing) {
        return decide(input, wasFollowing, FollowDecisions.NO_FAILED_COOLDOWN);
    }

    private static FollowDecisions.Result decide(
            FollowDecisions.Input input, boolean wasFollowing, long failedUntilTick) {
        return FollowDecisions.decide(input, wasFollowing, failedUntilTick);
    }

    private static FollowDecisions.Input input(
            boolean enabled, boolean manualPaused, double distance, long currentTick) {
        return new FollowDecisions.Input(enabled, manualPaused,
                true, true, true, true, true, distance,
                null, null, currentTick);
    }

    private static FollowDecisions.Input validInput(
            double distance, long currentTick, Double stopOverride, Double startOverride) {
        return new FollowDecisions.Input(true, false,
                true, true, true, true, true, distance,
                stopOverride, startOverride, currentTick);
    }

    private static void assertDormant(
            FollowDecisions.Result result,
            FollowRuntimeState expectedState,
            FollowWaitingReason expectedReason) {
        assertEquals(expectedState, result.runtimeState());
        assertEquals(expectedReason, result.waitingReason());
        assertEquals(Float.NEGATIVE_INFINITY, result.priority());
        assertFalse(result.sprintAllowed());
        assertFalse(result.catchingUp());
    }

    private static void assertFollowing(FollowDecisions.Result result) {
        assertEquals(FollowRuntimeState.FOLLOWING, result.runtimeState());
        assertEquals(FollowWaitingReason.NONE, result.waitingReason());
        assertEquals(FollowDecisions.PRIORITY, result.priority());
        assertTrue(result.following());
    }

    private static void assertDefaultDistances(FollowDecisions.Distances distances) {
        assertEquals(new FollowDecisions.Distances(3.0, 5.5), distances);
    }
}
