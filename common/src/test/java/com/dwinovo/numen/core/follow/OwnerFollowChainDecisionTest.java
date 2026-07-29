package com.dwinovo.numen.core.follow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwinovo.numen.core.pathing.goal.GoalCompiler;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

class OwnerFollowChainDecisionTest {

    @Test
    void priorityIsExactlyMinusTwo() {
        OwnerFollowChain.Decision decision = OwnerFollowChain.decide(activeAt(5.5), false);

        assertEquals(-2.0f, OwnerFollowChain.PRIORITY);
        assertEquals(OwnerFollowChain.PRIORITY, decision.priority());
    }

    @Test
    void disabledStateIsDormantAndClearsLatch() {
        OwnerFollowChain.Decision decision = OwnerFollowChain.decide(
                snapshot(new FollowState(false, false, 1, null, null),
                        true, true, true, true, true, 8.0),
                true);

        assertDormant(decision);
    }

    @Test
    void manualPauseIsDormantAndClearsLatch() {
        OwnerFollowChain.Decision decision = OwnerFollowChain.decide(
                snapshot(new FollowState(true, true, 1, null, null),
                        true, true, true, true, true, 8.0),
                true);

        assertDormant(decision);
    }

    @Test
    void missingOwnerUuidIsDormantAndClearsLatch() {
        OwnerFollowChain.Decision decision = OwnerFollowChain.decide(
                snapshot(enabled(), true, false, false, false, false, Double.NaN),
                true);

        assertDormant(decision);
    }

    @Test
    void unresolvedOrOfflineOwnerIsDormantAndClearsLatch() {
        OwnerFollowChain.Decision decision = OwnerFollowChain.decide(
                snapshot(enabled(), true, true, false, false, false, Double.NaN),
                true);

        assertDormant(decision);
    }

    @Test
    void deadOwnerIsDormantAndClearsLatch() {
        OwnerFollowChain.Decision decision = OwnerFollowChain.decide(
                snapshot(enabled(), true, true, true, false, false, Double.NaN),
                true);

        assertDormant(decision);
    }

    @Test
    void invalidOrDeadCompanionIsDormantAndClearsLatch() {
        OwnerFollowChain.Decision decision = OwnerFollowChain.decide(
                snapshot(enabled(), false, true, true, true, true, 8.0),
                true);

        assertDormant(decision);
    }

    @Test
    void differentLevelIsDormantAndClearsLatch() {
        OwnerFollowChain.Decision decision = OwnerFollowChain.decide(
                snapshot(enabled(), true, true, true, true, false, Double.NaN),
                true);

        assertDormant(decision);
    }

    @Test
    void distanceAtOrBelowStopClosesLatch() {
        assertDormant(OwnerFollowChain.decide(activeAt(3.0), true));
        assertDormant(OwnerFollowChain.decide(activeAt(2.99), true));
    }

    @Test
    void distanceAtOrAboveStartOpensLatch() {
        assertActive(OwnerFollowChain.decide(activeAt(5.5), false));
        assertActive(OwnerFollowChain.decide(activeAt(5.51), false));
    }

    @Test
    void hysteresisBandRetainsPreviousLatchValue() {
        assertActive(OwnerFollowChain.decide(activeAt(4.0), true));
        assertDormant(OwnerFollowChain.decide(activeAt(4.0), false));
    }

    @Test
    void verticalSeparationUsesThreeDimensionalEuclideanDistance() {
        double distance = OwnerFollowChain.distance3d(10.0, 64.0, -4.0,
                10.0, 69.5, -4.0);

        assertEquals(5.5, distance, 1.0e-9);
        assertActive(OwnerFollowChain.decide(activeAt(distance), false));
    }

    @Test
    void validOverridePairReplacesBothDefaults() {
        FollowState overridden = new FollowState(true, false, 1, 4.0, 7.0);

        OwnerFollowChain.Decision stopped = OwnerFollowChain.decide(
                snapshot(overridden, true, true, true, true, true, 4.0), true);
        OwnerFollowChain.Decision started = OwnerFollowChain.decide(
                snapshot(overridden, true, true, true, true, true, 7.0), false);

        assertEquals(new OwnerFollowChain.Distances(4.0, 7.0), stopped.distances());
        assertDormant(stopped);
        assertActive(started);
    }

    @Test
    void everyInvalidOrPartialOverridePairFallsBackTogether() {
        assertDefaultDistances(OwnerFollowChain.resolveDistances(null, 7.0));
        assertDefaultDistances(OwnerFollowChain.resolveDistances(4.0, null));
        assertDefaultDistances(OwnerFollowChain.resolveDistances(Double.NaN, 7.0));
        assertDefaultDistances(OwnerFollowChain.resolveDistances(4.0, Double.NaN));
        assertDefaultDistances(OwnerFollowChain.resolveDistances(
                Double.POSITIVE_INFINITY, 7.0));
        assertDefaultDistances(OwnerFollowChain.resolveDistances(
                4.0, Double.NEGATIVE_INFINITY));
        assertDefaultDistances(OwnerFollowChain.resolveDistances(-1.0, 7.0));
        assertDefaultDistances(OwnerFollowChain.resolveDistances(4.0, 0.0));
        assertDefaultDistances(OwnerFollowChain.resolveDistances(7.0, 7.0));
        assertDefaultDistances(OwnerFollowChain.resolveDistances(8.0, 7.0));
    }

    @Test
    void nonFiniteMeasuredDistanceIsDormant() {
        assertDormant(OwnerFollowChain.decide(activeAt(Double.NaN), true));
        assertDormant(OwnerFollowChain.decide(activeAt(Double.POSITIVE_INFINITY), true));
    }

    @Test
    void defaultFollowStateLeavesChainDormant() {
        OwnerFollowChain.Decision decision = OwnerFollowChain.decide(
                snapshot(FollowState.defaults(), true, true, true, true, true, 20.0),
                false);

        assertDormant(decision);
    }

    @Test
    void registrationOrderIsExactlySixty() {
        assertEquals(60, OwnerFollowChain.REGISTRATION_ORDER);
    }

    @Test
    void navigationGoalUsesGoalCompilerNearSemantics() {
        BlockPos center = new BlockPos(12, 70, -5);
        GoalCompiler.Compiled compiled = OwnerFollowChain.compileNearGoal(center, 3.0);

        assertEquals(center, compiled.goal().center());
        assertTrue(compiled.goal().isAt(center.offset(3, 1, 0)));
        assertFalse(compiled.goal().isAt(center.offset(4, 0, 0)));
    }

    private static FollowState enabled() {
        return new FollowState(true, false, FollowState.CURRENT_SCHEMA_VERSION, null, null);
    }

    private static OwnerFollowChain.Snapshot activeAt(double distance) {
        return snapshot(enabled(), true, true, true, true, true, distance);
    }

    private static OwnerFollowChain.Snapshot snapshot(
            FollowState state,
            boolean companionValid,
            boolean ownerUuidPresent,
            boolean ownerOnline,
            boolean ownerValid,
            boolean sameLevel,
            double distance) {
        return new OwnerFollowChain.Snapshot(state, companionValid, ownerUuidPresent,
                ownerOnline, ownerValid, sameLevel, distance);
    }

    private static void assertActive(OwnerFollowChain.Decision decision) {
        assertTrue(decision.following());
        assertEquals(OwnerFollowChain.PRIORITY, decision.priority());
    }

    private static void assertDormant(OwnerFollowChain.Decision decision) {
        assertFalse(decision.following());
        assertEquals(Float.NEGATIVE_INFINITY, decision.priority());
    }

    private static void assertDefaultDistances(OwnerFollowChain.Distances distances) {
        assertEquals(OwnerFollowChain.DEFAULT_STOP_DISTANCE, distances.stop());
        assertEquals(OwnerFollowChain.DEFAULT_START_DISTANCE, distances.start());
    }
}
