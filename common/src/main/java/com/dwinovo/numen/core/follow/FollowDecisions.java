package com.dwinovo.numen.core.follow;

import java.util.Objects;

/**
 * Pure owner-follow state machine.
 *
 * <p>The inputs and outputs contain no Minecraft objects, perform no I/O, and
 * retain no mutable global state.
 */
final class FollowDecisions {

    static final float PRIORITY = -2.0f;
    static final double DEFAULT_STOP_DISTANCE =
            FollowConfig.DEFAULT_STOP_DISTANCE;
    static final double DEFAULT_START_DISTANCE =
            FollowConfig.DEFAULT_START_DISTANCE;
    static final double DEFAULT_SPRINT_DISTANCE =
            FollowConfig.DEFAULT_SPRINT_DISTANCE;
    static final double DEFAULT_CATCH_UP_DISTANCE =
            FollowConfig.DEFAULT_CATCH_UP_DISTANCE;
    static final double DEFAULT_LOST_DISTANCE =
            FollowConfig.DEFAULT_LOST_DISTANCE;
    static final long FAILED_COOLDOWN_TICKS =
            FollowConfig.DEFAULT_FAILED_COOLDOWN_TICKS;
    static final long NO_FAILED_COOLDOWN = Long.MIN_VALUE;

    private FollowDecisions() {}

    static Result decide(Input input, boolean wasFollowing, long failedUntilTick) {
        return decide(input, wasFollowing, failedUntilTick, FollowConfig.defaults());
    }

    static Result decide(
            Input input,
            boolean wasFollowing,
            long failedUntilTick,
            FollowConfig config) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(config, "config");
        Distances distances = resolveDistances(
                input.stopOverride(), input.startOverride(), config);

        if (!input.companionValid()) {
            return dormant(FollowRuntimeState.WAITING_FOR_OWNER,
                    FollowWaitingReason.COMPANION_NOT_ACTIVE, false, distances);
        }
        if (!input.enabled()) {
            return dormant(FollowRuntimeState.DISABLED,
                    FollowWaitingReason.NONE, false, distances);
        }
        if (input.manualPaused()) {
            return dormant(FollowRuntimeState.MANUALLY_PAUSED,
                    FollowWaitingReason.NONE, false, distances);
        }
        if (!input.ownerUuidPresent()) {
            return dormant(FollowRuntimeState.WAITING_FOR_OWNER,
                    FollowWaitingReason.OWNER_INVALID, false, distances);
        }
        if (!input.ownerOnline()) {
            return dormant(FollowRuntimeState.WAITING_FOR_OWNER,
                    FollowWaitingReason.OWNER_OFFLINE, false, distances);
        }
        if (!input.ownerValid()) {
            return dormant(FollowRuntimeState.WAITING_FOR_OWNER,
                    FollowWaitingReason.OWNER_INVALID, false, distances);
        }
        if (!input.sameLevel()) {
            return dormant(FollowRuntimeState.WAITING_FOR_OWNER,
                    FollowWaitingReason.OWNER_OTHER_DIMENSION, false, distances);
        }
        if (!Double.isFinite(input.distance())) {
            return dormant(FollowRuntimeState.WAITING_FOR_OWNER,
                    FollowWaitingReason.OWNER_INVALID, false, distances);
        }
        if (input.distance() >= config.lostDistance()) {
            return dormant(FollowRuntimeState.WAITING_FOR_OWNER,
                    FollowWaitingReason.OWNER_TOO_FAR, wasFollowing, distances);
        }
        if (input.distance() <= distances.stop()) {
            return dormant(FollowRuntimeState.IDLE_NEAR_OWNER,
                    FollowWaitingReason.NONE, false, distances);
        }
        if (input.currentTick() < failedUntilTick) {
            return new Result(FollowRuntimeState.FAILED_COOLDOWN,
                    FollowWaitingReason.NONE, Float.NEGATIVE_INFINITY,
                    wasFollowing, false, false, failedUntilTick, distances);
        }

        boolean following = input.distance() >= distances.start()
                || wasFollowing && input.distance() > distances.stop();
        if (!following) {
            return dormant(FollowRuntimeState.IDLE_NEAR_OWNER,
                    FollowWaitingReason.NONE, false, distances);
        }
        return new Result(FollowRuntimeState.FOLLOWING, FollowWaitingReason.NONE,
                PRIORITY, true,
                input.distance() >= config.sprintDistance(),
                input.distance() >= config.catchUpDistance(),
                NO_FAILED_COOLDOWN, distances);
    }

    static Result failedAt(Result previous, long currentTick) {
        return failedAt(previous, currentTick, FollowConfig.defaults());
    }

    static Result failedAt(
            Result previous, long currentTick, FollowConfig config) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(config, "config");
        return new Result(FollowRuntimeState.FAILED_COOLDOWN,
                FollowWaitingReason.NONE, Float.NEGATIVE_INFINITY,
                previous.following(), false, false,
                currentTick + config.failedCooldownTicks(), previous.distances());
    }

    static long remainingCooldownTicks(long failedUntilTick, long currentTick) {
        if (failedUntilTick == NO_FAILED_COOLDOWN || currentTick >= failedUntilTick) {
            return 0L;
        }
        return failedUntilTick - currentTick;
    }

    static Distances resolveDistances(Double stopOverride, Double startOverride) {
        return resolveDistances(
                stopOverride, startOverride, FollowConfig.defaults());
    }

    static Distances resolveDistances(
            Double stopOverride,
            Double startOverride,
            FollowConfig config) {
        Objects.requireNonNull(config, "config");
        if (stopOverride == null || startOverride == null
                || !Double.isFinite(stopOverride) || !Double.isFinite(startOverride)
                || stopOverride <= 0.0 || startOverride <= 0.0
                || stopOverride >= startOverride
                || startOverride >= config.sprintDistance()) {
            return new Distances(config.stopDistance(), config.startDistance());
        }
        return new Distances(stopOverride, startOverride);
    }

    static double distance3d(double firstX, double firstY, double firstZ,
                             double secondX, double secondY, double secondZ) {
        double dx = secondX - firstX;
        double dy = secondY - firstY;
        double dz = secondZ - firstZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static Result dormant(FollowRuntimeState state, FollowWaitingReason reason,
                                   boolean following, Distances distances) {
        return new Result(state, reason, Float.NEGATIVE_INFINITY, following,
                false, false, NO_FAILED_COOLDOWN, distances);
    }

    record Input(
            boolean enabled,
            boolean manualPaused,
            boolean companionValid,
            boolean ownerUuidPresent,
            boolean ownerOnline,
            boolean ownerValid,
            boolean sameLevel,
            double distance,
            Double stopOverride,
            Double startOverride,
            long currentTick) {}

    record Distances(double stop, double start) {}

    record Result(
            FollowRuntimeState runtimeState,
            FollowWaitingReason waitingReason,
            float priority,
            boolean following,
            boolean sprintAllowed,
            boolean catchingUp,
            long failedUntilTick,
            Distances distances) {

        Result {
            Objects.requireNonNull(runtimeState, "runtimeState");
            Objects.requireNonNull(waitingReason, "waitingReason");
            Objects.requireNonNull(distances, "distances");
        }

        boolean active() {
            return priority == PRIORITY;
        }
    }
}
