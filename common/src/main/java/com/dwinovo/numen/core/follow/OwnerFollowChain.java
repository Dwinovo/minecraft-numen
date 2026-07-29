package com.dwinovo.numen.core.follow;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskChain;

import net.minecraft.server.level.ServerPlayer;

/**
 * Lowest-priority, per-companion owner following.
 *
 * <p>The chain retains only its hysteresis latch and one navigation handle.
 * Owner resolution remains live: eligibility, winning ticks, the dynamic goal,
 * and the arrival predicate all resolve the companion's current owner again.
 */
public final class OwnerFollowChain implements TaskChain {

    public static final float PRIORITY = -2.0f;
    public static final double DEFAULT_STOP_DISTANCE = 3.0;
    public static final double DEFAULT_START_DISTANCE = 5.5;
    public static final int REGISTRATION_ORDER = 60;

    private static final double NAV_SPEED = 1.0;

    private final FollowAccess followAccess;
    private final NavigationFactory navigationFactory;
    private final HaltAction haltAction;

    private Navigation navigation;
    private boolean following;

    public OwnerFollowChain() {
        this(new LiveFollowAccess(), OwnerFollowChain::createPlayerNavigation, InputDriver::halt);
    }

    OwnerFollowChain(FollowAccess followAccess, NavigationFactory navigationFactory,
                     HaltAction haltAction) {
        this.followAccess = Objects.requireNonNull(followAccess, "followAccess");
        this.navigationFactory = Objects.requireNonNull(navigationFactory, "navigationFactory");
        this.haltAction = Objects.requireNonNull(haltAction, "haltAction");
    }

    @Override
    public float getPriority(NumenPlayer companion) {
        Decision decision = decide(followAccess.snapshot(companion), following);
        following = decision.following();
        return decision.priority();
    }

    @Override
    public void tick(NumenPlayer companion) {
        Decision decision = decide(followAccess.snapshot(companion), following);
        following = decision.following();
        if (!decision.active()) {
            releaseControl(companion);
            return;
        }

        if (navigation == null) {
            double stopDistance = decision.distances().stop();
            Supplier<GoalCompiler.Compiled> goal =
                    () -> followAccess.compileOwnerGoal(companion, stopDistance);
            BooleanSupplier reached =
                    () -> followAccess.isWithinStopDistance(companion, stopDistance);
            navigation = navigationFactory.create(
                    companion, goal, reached, FollowContextProvider.INSTANCE);
        }

        PlayerNav.Status status = navigation.tick();
        if (status == PlayerNav.Status.RUNNING) {
            return;
        }

        if (status == PlayerNav.Status.ARRIVED) {
            Decision arrival = decide(followAccess.snapshot(companion), following);
            following = arrival.following();
            if (!arrival.active()) {
                releaseControl(companion);
            }
            return;
        }
        releaseControl(companion);
    }

    @Override
    public void onInterrupt(NumenPlayer companion) {
        releaseControl(companion);
    }

    @Override
    public String name() {
        return "owner_follow";
    }

    /**
     * Idempotently relinquishes both pathing and movement input. The hysteresis
     * latch is intentionally retained across scheduler pre-emption.
     */
    private void releaseControl(NumenPlayer companion) {
        Navigation active = navigation;
        try {
            if (active != null) {
                active.stop();
            }
        } finally {
            try {
                haltAction.halt(companion);
            } finally {
                navigation = null;
            }
        }
    }

    static Decision decide(Snapshot snapshot, boolean wasFollowing) {
        Distances distances = resolveDistances(
                snapshot.state().stopDistanceOverride(),
                snapshot.state().startDistanceOverride());

        if (!snapshot.companionValid()
                || !snapshot.state().enabled()
                || snapshot.state().manualPaused()
                || !snapshot.ownerUuidPresent()
                || !snapshot.ownerOnline()
                || !snapshot.ownerValid()
                || !snapshot.sameLevel()
                || !Double.isFinite(snapshot.distance())) {
            return new Decision(Float.NEGATIVE_INFINITY, false, distances);
        }

        boolean nowFollowing;
        if (snapshot.distance() <= distances.stop()) {
            nowFollowing = false;
        } else if (snapshot.distance() >= distances.start()) {
            nowFollowing = true;
        } else {
            nowFollowing = wasFollowing;
        }
        return new Decision(nowFollowing ? PRIORITY : Float.NEGATIVE_INFINITY,
                nowFollowing, distances);
    }

    static Distances resolveDistances(Double stopOverride, Double startOverride) {
        if (stopOverride == null || startOverride == null
                || !Double.isFinite(stopOverride) || !Double.isFinite(startOverride)
                || stopOverride <= 0.0 || startOverride <= 0.0
                || stopOverride >= startOverride) {
            return new Distances(DEFAULT_STOP_DISTANCE, DEFAULT_START_DISTANCE);
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

    static GoalCompiler.Compiled compileNearGoal(
            net.minecraft.core.BlockPos center, double stopDistance) {
        return GoalCompiler.near(center, stopDistance);
    }

    boolean hasNavigation() {
        return navigation != null;
    }

    private static Navigation createPlayerNavigation(
            NumenPlayer companion,
            Supplier<GoalCompiler.Compiled> goal,
            BooleanSupplier reached,
            PlayerNav.ContextProvider contextProvider) {
        PlayerNav playerNav = PlayerNav.toRevalidating(
                companion, goal, NAV_SPEED, reached, contextProvider);
        return new Navigation() {
            @Override
            public PlayerNav.Status tick() {
                return playerNav.tick();
            }

            @Override
            public void stop() {
                playerNav.stop();
            }
        };
    }

    record Distances(double stop, double start) {}

    record Decision(float priority, boolean following, Distances distances) {
        boolean active() {
            return priority == PRIORITY;
        }
    }

    record Snapshot(
            FollowState state,
            boolean companionValid,
            boolean ownerUuidPresent,
            boolean ownerOnline,
            boolean ownerValid,
            boolean sameLevel,
            double distance) {

        Snapshot {
            Objects.requireNonNull(state, "state");
        }
    }

    interface FollowAccess {
        Snapshot snapshot(NumenPlayer companion);

        GoalCompiler.Compiled compileOwnerGoal(NumenPlayer companion, double stopDistance);

        boolean isWithinStopDistance(NumenPlayer companion, double stopDistance);
    }

    interface NavigationFactory {
        Navigation create(NumenPlayer companion, Supplier<GoalCompiler.Compiled> goal,
                          BooleanSupplier reached, PlayerNav.ContextProvider contextProvider);
    }

    interface Navigation {
        PlayerNav.Status tick();

        void stop();
    }

    interface HaltAction {
        void halt(NumenPlayer companion);
    }

    private static final class LiveFollowAccess implements FollowAccess {

        @Override
        public Snapshot snapshot(NumenPlayer companion) {
            boolean companionValid =
                    companion != null && !companion.isRemoved() && companion.isAlive();
            if (companion == null) {
                return unavailable(companionValid, false);
            }

            FollowState state = FollowStateStore.get(companion.level().getServer())
                    .getOrDefault(companion.getUUID());
            boolean ownerUuidPresent = companion.getOwnerUuid() != null;
            if (!companionValid || !ownerUuidPresent) {
                return new Snapshot(state, companionValid, ownerUuidPresent,
                        false, false, false, Double.NaN);
            }

            ServerPlayer owner = companion.resolveOwnerPlayer();
            boolean ownerOnline = owner != null;
            boolean ownerValid = ownerOnline && !owner.isRemoved() && owner.isAlive();
            boolean sameLevel = ownerValid && owner.level() == companion.level();
            double distance = sameLevel
                    ? distance3d(companion.getX(), companion.getY(), companion.getZ(),
                            owner.getX(), owner.getY(), owner.getZ())
                    : Double.NaN;
            return new Snapshot(state, companionValid, ownerUuidPresent,
                    ownerOnline, ownerValid, sameLevel, distance);
        }

        @Override
        public GoalCompiler.Compiled compileOwnerGoal(
                NumenPlayer companion, double stopDistance) {
            ServerPlayer owner = resolveLiveSameLevelOwner(companion);
            return owner == null ? null : compileNearGoal(owner.blockPosition(), stopDistance);
        }

        @Override
        public boolean isWithinStopDistance(NumenPlayer companion, double stopDistance) {
            ServerPlayer owner = resolveLiveSameLevelOwner(companion);
            return owner != null
                    && distance3d(companion.getX(), companion.getY(), companion.getZ(),
                            owner.getX(), owner.getY(), owner.getZ()) <= stopDistance;
        }

        private static Snapshot unavailable(boolean companionValid, boolean ownerUuidPresent) {
            return new Snapshot(FollowState.defaults(), companionValid, ownerUuidPresent,
                    false, false, false, Double.NaN);
        }

        private static ServerPlayer resolveLiveSameLevelOwner(NumenPlayer companion) {
            if (companion == null || companion.isRemoved() || !companion.isAlive()
                    || companion.getOwnerUuid() == null) {
                return null;
            }
            ServerPlayer owner = companion.resolveOwnerPlayer();
            if (owner == null || owner.isRemoved() || !owner.isAlive()
                    || owner.level() != companion.level()) {
                return null;
            }
            return owner;
        }
    }
}
