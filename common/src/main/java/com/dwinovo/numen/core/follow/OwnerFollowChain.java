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
 * <p>The chain retains per-companion transient runtime state and at most one
 * navigation handle. Owner resolution remains live: eligibility, winning
 * ticks, the dynamic goal, and the arrival predicate all resolve the
 * companion's current owner again.
 */
public final class OwnerFollowChain implements TaskChain {

    public static final float PRIORITY = FollowDecisions.PRIORITY;
    public static final double DEFAULT_STOP_DISTANCE =
            FollowDecisions.DEFAULT_STOP_DISTANCE;
    public static final double DEFAULT_START_DISTANCE =
            FollowDecisions.DEFAULT_START_DISTANCE;
    public static final double DEFAULT_SPRINT_DISTANCE =
            FollowDecisions.DEFAULT_SPRINT_DISTANCE;
    public static final double DEFAULT_CATCH_UP_DISTANCE =
            FollowDecisions.DEFAULT_CATCH_UP_DISTANCE;
    public static final double DEFAULT_LOST_DISTANCE =
            FollowDecisions.DEFAULT_LOST_DISTANCE;
    public static final long FAILED_COOLDOWN_TICKS =
            FollowDecisions.FAILED_COOLDOWN_TICKS;
    public static final int REGISTRATION_ORDER = 60;

    private static final double NAV_SPEED = 1.0;

    private final FollowAccess followAccess;
    private final NavigationFactory navigationFactory;
    private final HaltAction haltAction;

    private Navigation navigation;
    private FollowRuntimeState runtimeState = FollowRuntimeState.DISABLED;
    private FollowWaitingReason waitingReason = FollowWaitingReason.NONE;
    private boolean following;
    private boolean sprintAllowed;
    private boolean catchingUp;
    private long failedUntilTick = FollowDecisions.NO_FAILED_COOLDOWN;

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
        Decision decision = decide(
                followAccess.snapshot(companion), following, failedUntilTick);
        apply(decision);
        return decision.priority();
    }

    @Override
    public void tick(NumenPlayer companion) {
        Snapshot snapshot = followAccess.snapshot(companion);
        Decision decision = decide(snapshot, following, failedUntilTick);
        apply(decision);
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
                    companion, goal, reached, FollowContextProvider.INSTANCE,
                    () -> sprintAllowed);
        }

        PlayerNav.Status status = navigation.tick();
        if (status == PlayerNav.Status.RUNNING) {
            return;
        }

        if (status == PlayerNav.Status.ARRIVED) {
            Decision arrival = decide(
                    followAccess.snapshot(companion), following, failedUntilTick);
            apply(arrival);
            if (!arrival.active()) {
                releaseControl(companion);
            }
            return;
        }
        if (status == PlayerNav.Status.FAILED) {
            releaseControl(companion);
            apply(failedAt(decision, snapshot.gameTime()));
        }
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
                sprintAllowed = false;
                catchingUp = false;
            }
        }
    }

    static Decision decide(Snapshot snapshot, boolean wasFollowing) {
        return decide(snapshot, wasFollowing, FollowDecisions.NO_FAILED_COOLDOWN);
    }

    static Decision decide(
            Snapshot snapshot, boolean wasFollowing, long failedUntilTick) {
        FollowDecisions.Result result = FollowDecisions.decide(
                toInput(snapshot), wasFollowing, failedUntilTick);
        return fromResult(result);
    }

    static Decision failedAt(Decision previous, long currentTick) {
        return fromResult(FollowDecisions.failedAt(toResult(previous), currentTick));
    }

    static Distances resolveDistances(Double stopOverride, Double startOverride) {
        FollowDecisions.Distances distances =
                FollowDecisions.resolveDistances(stopOverride, startOverride);
        return new Distances(distances.stop(), distances.start());
    }

    static double distance3d(double firstX, double firstY, double firstZ,
                             double secondX, double secondY, double secondZ) {
        return FollowDecisions.distance3d(
                firstX, firstY, firstZ, secondX, secondY, secondZ);
    }

    static GoalCompiler.Compiled compileNearGoal(
            net.minecraft.core.BlockPos center, double stopDistance) {
        return GoalCompiler.near(center, stopDistance);
    }

    boolean hasNavigation() {
        return navigation != null;
    }

    RuntimeView runtimeView() {
        return new RuntimeView(runtimeState, waitingReason, following,
                sprintAllowed, catchingUp, failedUntilTick, navigation != null);
    }

    long remainingCooldownTicks(long currentTick) {
        return FollowDecisions.remainingCooldownTicks(failedUntilTick, currentTick);
    }

    private void apply(Decision decision) {
        runtimeState = decision.runtimeState();
        waitingReason = decision.waitingReason();
        following = decision.following();
        sprintAllowed = decision.sprintAllowed();
        catchingUp = decision.catchingUp();
        failedUntilTick = decision.failedUntilTick();
    }

    private static Navigation createPlayerNavigation(
            NumenPlayer companion,
            Supplier<GoalCompiler.Compiled> goal,
            BooleanSupplier reached,
            PlayerNav.ContextProvider contextProvider,
            BooleanSupplier sprintAllowed) {
        PlayerNav playerNav = PlayerNav.toRevalidating(
                companion, goal, NAV_SPEED, reached, contextProvider, sprintAllowed);
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

    record Decision(
            float priority,
            boolean following,
            Distances distances,
            FollowRuntimeState runtimeState,
            FollowWaitingReason waitingReason,
            boolean sprintAllowed,
            boolean catchingUp,
            long failedUntilTick) {

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
            double distance,
            long gameTime) {

        Snapshot {
            Objects.requireNonNull(state, "state");
        }

        Snapshot(FollowState state, boolean companionValid, boolean ownerUuidPresent,
                 boolean ownerOnline, boolean ownerValid, boolean sameLevel,
                 double distance) {
            this(state, companionValid, ownerUuidPresent, ownerOnline, ownerValid,
                    sameLevel, distance, 0L);
        }
    }

    record RuntimeView(
            FollowRuntimeState runtimeState,
            FollowWaitingReason waitingReason,
            boolean following,
            boolean sprintAllowed,
            boolean catchingUp,
            long failedUntilTick,
            boolean navigationActive) {}

    interface FollowAccess {
        Snapshot snapshot(NumenPlayer companion);

        GoalCompiler.Compiled compileOwnerGoal(NumenPlayer companion, double stopDistance);

        boolean isWithinStopDistance(NumenPlayer companion, double stopDistance);
    }

    interface NavigationFactory {
        Navigation create(NumenPlayer companion, Supplier<GoalCompiler.Compiled> goal,
                          BooleanSupplier reached, PlayerNav.ContextProvider contextProvider,
                          BooleanSupplier sprintAllowed);
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

            long gameTime = companion.level().getGameTime();
            FollowState state = FollowStateStore.get(companion.level().getServer())
                    .getOrDefault(companion.getUUID());
            boolean ownerUuidPresent = companion.getOwnerUuid() != null;
            if (!companionValid || !ownerUuidPresent) {
                return new Snapshot(state, companionValid, ownerUuidPresent,
                        false, false, false, Double.NaN, gameTime);
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
                    ownerOnline, ownerValid, sameLevel, distance, gameTime);
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

    private static FollowDecisions.Input toInput(Snapshot snapshot) {
        FollowState state = snapshot.state();
        return new FollowDecisions.Input(
                state.enabled(),
                state.manualPaused(),
                snapshot.companionValid(),
                snapshot.ownerUuidPresent(),
                snapshot.ownerOnline(),
                snapshot.ownerValid(),
                snapshot.sameLevel(),
                snapshot.distance(),
                state.stopDistanceOverride(),
                state.startDistanceOverride(),
                snapshot.gameTime());
    }

    private static Decision fromResult(FollowDecisions.Result result) {
        FollowDecisions.Distances resolved = result.distances();
        return new Decision(
                result.priority(),
                result.following(),
                new Distances(resolved.stop(), resolved.start()),
                result.runtimeState(),
                result.waitingReason(),
                result.sprintAllowed(),
                result.catchingUp(),
                result.failedUntilTick());
    }

    private static FollowDecisions.Result toResult(Decision decision) {
        Distances distances = decision.distances();
        return new FollowDecisions.Result(
                decision.runtimeState(),
                decision.waitingReason(),
                decision.priority(),
                decision.following(),
                decision.sprintAllowed(),
                decision.catchingUp(),
                decision.failedUntilTick(),
                new FollowDecisions.Distances(distances.stop(), distances.start()));
    }
}
