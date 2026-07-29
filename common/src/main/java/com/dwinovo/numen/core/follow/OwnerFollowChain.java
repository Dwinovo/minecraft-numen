package com.dwinovo.numen.core.follow;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskChain;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;

/**
 * Lowest-priority, per-companion owner following.
 *
 * <p>The chain retains per-companion transient runtime state and at most one
 * navigation handle. Owner resolution remains live: eligibility, winning
 * ticks, the dynamic goal, and the arrival predicate all resolve the
 * companion's current owner again.
 */
public final class OwnerFollowChain implements TaskChain, FollowRuntimeControl {

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
    private final FollowConfig config;

    private Navigation navigation;
    private FollowRuntimeState runtimeState = FollowRuntimeState.DISABLED;
    private FollowWaitingReason waitingReason = FollowWaitingReason.NONE;
    private boolean following;
    private boolean sprintAllowed;
    private boolean catchingUp;
    private long failedUntilTick = FollowDecisions.NO_FAILED_COOLDOWN;
    private long lastObservedGameTime;
    private transient UUID boundCompanionUuid;
    private transient UUID terminalCompanionUuid;
    private transient NumenPlayer boundCompanion;
    private transient FollowStateStore boundStore;

    public OwnerFollowChain() {
        this(FollowConfig.defaults());
    }

    public OwnerFollowChain(FollowConfig config) {
        this(new LiveFollowAccess(), OwnerFollowChain::createPlayerNavigation,
                InputDriver::halt, config);
    }

    OwnerFollowChain(FollowAccess followAccess, NavigationFactory navigationFactory,
                     HaltAction haltAction) {
        this(followAccess, navigationFactory, haltAction, FollowConfig.defaults());
    }

    OwnerFollowChain(FollowAccess followAccess, NavigationFactory navigationFactory,
                     HaltAction haltAction, FollowConfig config) {
        this.followAccess = Objects.requireNonNull(followAccess, "followAccess");
        this.navigationFactory = Objects.requireNonNull(navigationFactory, "navigationFactory");
        this.haltAction = Objects.requireNonNull(haltAction, "haltAction");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public float getPriority(NumenPlayer companion) {
        ensureBound(companion);
        Snapshot snapshot = followAccess.snapshot(companion);
        observe(snapshot);
        Decision decision = decide(snapshot, following, failedUntilTick, config);
        apply(decision);
        return decision.priority();
    }

    @Override
    public void tick(NumenPlayer companion) {
        ensureBound(companion);
        Snapshot snapshot = followAccess.snapshot(companion);
        observe(snapshot);
        Decision decision = decide(snapshot, following, failedUntilTick, config);
        apply(decision);
        if (!decision.active()) {
            releaseControl(inactiveReason(snapshot), companion, true);
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
            Snapshot arrivalSnapshot = followAccess.snapshot(companion);
            observe(arrivalSnapshot);
            Decision arrival = decide(
                    arrivalSnapshot, following, failedUntilTick, config);
            apply(arrival);
            if (!arrival.active()) {
                releaseControl(inactiveReason(arrivalSnapshot), companion, true);
            }
            return;
        }
        if (status == PlayerNav.Status.FAILED) {
            releaseControl(
                    FollowReleaseReason.INTERNAL_STATE_CHANGE, companion, true);
            apply(failedAt(decision, snapshot.gameTime(), config));
        }
    }

    @Override
    public void onInterrupt(NumenPlayer companion) {
        releaseControl(FollowReleaseReason.SCHEDULER_INTERRUPT, companion, true);
    }

    @Override
    public String name() {
        return "owner_follow";
    }

    @Override
    public UUID companionUuid() {
        return boundCompanionUuid;
    }

    @Override
    public void release(FollowReleaseReason reason) {
        releaseControl(Objects.requireNonNull(reason, "reason"), null, false);
    }

    @Override
    public FollowRuntimeSnapshot snapshot(long currentGameTime) {
        return new FollowRuntimeSnapshot(
                runtimeState,
                waitingReason,
                following,
                navigation != null,
                sprintAllowed,
                catchingUp,
                failedUntilTick,
                FollowDecisions.remainingCooldownTicks(
                        failedUntilTick, currentGameTime));
    }

    /**
     * Idempotently relinquishes pathing and movement input, then applies the
     * reason-specific transient-state policy. It never changes persistent
     * {@link FollowState}.
     */
    private void releaseControl(
            FollowReleaseReason reason,
            NumenPlayer immediateCompanion,
            boolean haltWithoutBoundForSchedulerCall) {
        Navigation active = navigation;
        NumenPlayer haltTarget =
                immediateCompanion != null ? immediateCompanion : boundCompanion;
        RuntimeException failure = null;
        try {
            if (active != null) {
                active.stop();
            }
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            if (haltTarget != null || haltWithoutBoundForSchedulerCall) {
                haltAction.halt(haltTarget);
            }
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        } finally {
            navigation = null;
            sprintAllowed = false;
            catchingUp = false;
        }

        applyReleaseState(reason);
        if (failure != null) {
            throw failure;
        }
    }

    private void applyReleaseState(FollowReleaseReason reason) {
        switch (reason) {
            case SCHEDULER_INTERRUPT, INTERNAL_STATE_CHANGE -> {
                // Preserve the Stage 4 hysteresis latch and failure deadline.
            }
            case FOLLOW_DISABLED -> resetRuntime(
                    FollowRuntimeState.DISABLED, FollowWaitingReason.NONE, false);
            case MANUAL_PAUSE -> resetRuntime(
                    FollowRuntimeState.MANUALLY_PAUSED, FollowWaitingReason.NONE, false);
            case COMPANION_DEATH, COMPANION_REMOVED -> {
                resetRuntime(FollowRuntimeState.WAITING_FOR_OWNER,
                        FollowWaitingReason.COMPANION_NOT_ACTIVE, true);
            }
            case SERVER_STOPPING, RUNTIME_REPLACED -> {
                resetRuntime(FollowRuntimeState.DISABLED,
                        FollowWaitingReason.NONE, true);
            }
        }
    }

    private void resetRuntime(
            FollowRuntimeState newState,
            FollowWaitingReason newWaitingReason,
            boolean terminateBinding) {
        runtimeState = newState;
        waitingReason = newWaitingReason;
        following = false;
        failedUntilTick = FollowDecisions.NO_FAILED_COOLDOWN;
        if (terminateBinding) {
            terminalCompanionUuid = boundCompanionUuid;
            boundCompanionUuid = null;
            boundCompanion = null;
            boundStore = null;
        }
    }

    private void ensureBound(NumenPlayer companion) {
        if (companion == null || companion.isRemoved() || !companion.isAlive()) {
            return;
        }
        MinecraftServer server = companion.level().getServer();
        if (server == null) {
            return;
        }

        UUID companionUuid = companion.getUUID();
        if (companionUuid.equals(terminalCompanionUuid)) {
            return;
        }
        FollowStateStore store = FollowStateStore.get(server);
        if (companionUuid.equals(boundCompanionUuid) && store == boundStore) {
            if (boundCompanion != companion) {
                store.bindRuntime(companionUuid, companion, this);
            }
            boundCompanion = companion;
            return;
        }

        if (boundCompanionUuid != null && boundStore != null) {
            UUID previousUuid = boundCompanionUuid;
            try {
                boundStore.removeRuntime(
                        previousUuid, this,
                        FollowReleaseReason.RUNTIME_REPLACED);
            } finally {
                if (previousUuid.equals(terminalCompanionUuid)) {
                    terminalCompanionUuid = null;
                }
            }
        }

        boundCompanionUuid = companionUuid;
        boundCompanion = companion;
        boundStore = store;
        store.bindRuntime(companionUuid, companion, this);
    }

    private void observe(Snapshot snapshot) {
        lastObservedGameTime = snapshot.gameTime();
    }

    private static FollowReleaseReason inactiveReason(Snapshot snapshot) {
        if (!snapshot.state().enabled()) {
            return FollowReleaseReason.FOLLOW_DISABLED;
        }
        if (snapshot.state().manualPaused()) {
            return FollowReleaseReason.MANUAL_PAUSE;
        }
        return FollowReleaseReason.INTERNAL_STATE_CHANGE;
    }

    static Decision decide(Snapshot snapshot, boolean wasFollowing) {
        return decide(snapshot, wasFollowing, FollowDecisions.NO_FAILED_COOLDOWN);
    }

    static Decision decide(
            Snapshot snapshot, boolean wasFollowing, long failedUntilTick) {
        return decide(snapshot, wasFollowing, failedUntilTick, FollowConfig.defaults());
    }

    static Decision decide(
            Snapshot snapshot,
            boolean wasFollowing,
            long failedUntilTick,
            FollowConfig config) {
        FollowDecisions.Result result = FollowDecisions.decide(
                toInput(snapshot), wasFollowing, failedUntilTick, config);
        return fromResult(result);
    }

    static Decision failedAt(Decision previous, long currentTick) {
        return failedAt(previous, currentTick, FollowConfig.defaults());
    }

    static Decision failedAt(
            Decision previous, long currentTick, FollowConfig config) {
        return fromResult(
                FollowDecisions.failedAt(toResult(previous), currentTick, config));
    }

    static Distances resolveDistances(Double stopOverride, Double startOverride) {
        return resolveDistances(
                stopOverride, startOverride, FollowConfig.defaults());
    }

    static Distances resolveDistances(
            Double stopOverride, Double startOverride, FollowConfig config) {
        FollowDecisions.Distances distances =
                FollowDecisions.resolveDistances(stopOverride, startOverride, config);
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

    FollowRuntimeSnapshot runtimeView() {
        return snapshot(lastObservedGameTime);
    }

    long remainingCooldownTicks(long currentTick) {
        return FollowDecisions.remainingCooldownTicks(failedUntilTick, currentTick);
    }

    FollowConfig config() {
        return config;
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
