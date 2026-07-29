package com.dwinovo.numen.core.follow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.ChainScheduler;
import com.dwinovo.numen.task.TaskChain;

import org.junit.jupiter.api.Test;

class OwnerFollowChainLifecycleTest {

    @Test
    void chainThatDoesNotWinNeverCreatesOrTicksNavigation() {
        Harness harness = new Harness(activeAt(8.0));
        CountingChain explicitTask = new CountingChain(TaskChain.LLM_BASE_PRIORITY);

        TaskChain winner = ChainScheduler.select(List.of(harness.chain, explicitTask), null);
        winner.tick(null);

        assertSame(explicitTask, winner);
        assertEquals(0, harness.factory.creates);
        assertEquals(0, harness.navigation.ticks);
    }

    @Test
    void chainTicksNavigationOnlyAfterSchedulerSelectsIt() {
        Harness harness = new Harness(activeAt(8.0));

        TaskChain winner = ChainScheduler.select(List.of(harness.chain), null);
        winner.tick(null);

        assertSame(harness.chain, winner);
        assertEquals(1, harness.factory.creates);
        assertEquals(1, harness.navigation.ticks);
    }

    @Test
    void oneChainCreatesAtMostOneNavigationAtATime() {
        Harness harness = new Harness(activeAt(8.0));

        harness.chain.tick(null);
        harness.chain.tick(null);
        harness.chain.tick(null);

        assertEquals(1, harness.factory.creates);
        assertTrue(harness.chain.hasNavigation());
    }

    @Test
    void consecutiveWinningTicksReuseTheSameNavigation() {
        Harness harness = new Harness(activeAt(8.0));

        harness.chain.tick(null);
        OwnerFollowChain.Navigation created = harness.factory.lastCreated;
        harness.chain.tick(null);

        assertSame(created, harness.factory.lastCreated);
        assertEquals(2, harness.navigation.ticks);
        assertEquals(1, harness.factory.creates);
    }

    @Test
    void navigationReceivesTheSafeFollowContextProvider() {
        Harness harness = new Harness(activeAt(8.0));

        harness.chain.tick(null);

        assertSame(FollowContextProvider.INSTANCE, harness.factory.contextProvider);
    }

    @Test
    void navigationUsesTheDynamicOwnerGoalSupplier() {
        Harness harness = new Harness(activeAt(8.0));
        harness.navigation.readGoalOnTick = true;

        harness.chain.tick(null);

        assertEquals(1, harness.access.goalCompiles);
        assertEquals(OwnerFollowChain.DEFAULT_STOP_DISTANCE,
                harness.access.lastGoalStopDistance);
    }

    @Test
    void interruptStopsHaltsAndClearsNavigation() {
        Harness harness = new Harness(activeAt(8.0));
        harness.chain.tick(null);

        harness.chain.onInterrupt(null);

        assertEquals(1, harness.navigation.stops);
        assertEquals(1, harness.halt.calls);
        assertFalse(harness.chain.hasNavigation());
    }

    @Test
    void repeatedInterruptIsIdempotentAndSafe() {
        Harness harness = new Harness(activeAt(8.0));
        harness.chain.tick(null);

        harness.chain.onInterrupt(null);
        harness.chain.onInterrupt(null);

        assertEquals(1, harness.navigation.stops);
        assertEquals(2, harness.halt.calls);
        assertFalse(harness.chain.hasNavigation());
    }

    @Test
    void winningTickRevalidationFailureDoesNotTickExistingNavigation() {
        Harness harness = new Harness(activeAt(8.0));
        harness.chain.tick(null);
        harness.access.snapshot = snapshot(enabled(), true, true, false,
                false, false, Double.NaN);

        harness.chain.tick(null);

        assertEquals(1, harness.navigation.ticks);
        assertEquals(1, harness.navigation.stops);
        assertEquals(1, harness.halt.calls);
        assertFalse(harness.chain.hasNavigation());
    }

    @Test
    void reachingStopDistanceStopsHaltsAndClearsNavigation() {
        Harness harness = new Harness(activeAt(8.0));
        harness.chain.tick(null);
        harness.access.snapshot = activeAt(OwnerFollowChain.DEFAULT_STOP_DISTANCE);

        harness.chain.tick(null);

        assertEquals(1, harness.navigation.ticks);
        assertEquals(1, harness.navigation.stops);
        assertEquals(1, harness.halt.calls);
        assertFalse(harness.chain.hasNavigation());
    }

    @Test
    void failedNavigationStopsHaltsAndClearsNavigation() {
        Harness harness = new Harness(activeAt(8.0));
        harness.navigation.status = PlayerNav.Status.FAILED;

        harness.chain.tick(null);

        assertEquals(1, harness.navigation.ticks);
        assertEquals(1, harness.navigation.stops);
        assertEquals(1, harness.halt.calls);
        assertFalse(harness.chain.hasNavigation());
    }

    @Test
    void arrivedNavigationRemeasuresAndDoesNotCreateSecondNavInSameTick() {
        Harness harness = new Harness(activeAt(8.0));
        harness.navigation.status = PlayerNav.Status.ARRIVED;
        harness.navigation.onTick = () ->
                harness.access.snapshot = activeAt(OwnerFollowChain.DEFAULT_STOP_DISTANCE);

        harness.chain.tick(null);

        assertEquals(2, harness.access.snapshots);
        assertEquals(1, harness.factory.creates);
        assertEquals(1, harness.navigation.ticks);
        assertEquals(1, harness.navigation.stops);
        assertFalse(harness.chain.hasNavigation());
    }

    @Test
    void arrivedOutsideStopDistanceRetainsRevalidatingNavigation() {
        Harness harness = new Harness(activeAt(8.0));
        harness.navigation.status = PlayerNav.Status.ARRIVED;

        harness.chain.tick(null);

        assertEquals(2, harness.access.snapshots);
        assertEquals(1, harness.factory.creates);
        assertEquals(1, harness.navigation.ticks);
        assertEquals(0, harness.navigation.stops);
        assertEquals(0, harness.halt.calls);
        assertTrue(harness.chain.hasNavigation());
    }

    @Test
    void runningNavigationRemainsOwnedWithoutRelease() {
        Harness harness = new Harness(activeAt(8.0));

        harness.chain.tick(null);

        assertEquals(0, harness.navigation.stops);
        assertEquals(0, harness.halt.calls);
        assertTrue(harness.chain.hasNavigation());
    }

    @Test
    void separateChainInstancesKeepIndependentHysteresisLatches() {
        Harness first = new Harness(activeAt(8.0));
        Harness second = new Harness(activeAt(4.0));

        assertEquals(OwnerFollowChain.PRIORITY, first.chain.getPriority(null));
        assertEquals(Float.NEGATIVE_INFINITY, second.chain.getPriority(null));
        assertNotSame(first.chain, second.chain);
    }

    @Test
    void defaultDormantStateNeverCreatesNavigation() {
        Harness harness = new Harness(snapshot(FollowState.defaults(),
                true, true, true, true, true, 20.0));

        harness.chain.tick(null);

        assertEquals(0, harness.factory.creates);
        assertEquals(0, harness.navigation.ticks);
        assertEquals(1, harness.halt.calls);
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

    private static final class Harness {
        private final FakeAccess access;
        private final FakeNavigation navigation = new FakeNavigation();
        private final FakeNavigationFactory factory = new FakeNavigationFactory(navigation);
        private final FakeHalt halt = new FakeHalt();
        private final OwnerFollowChain chain;

        private Harness(OwnerFollowChain.Snapshot snapshot) {
            access = new FakeAccess(snapshot);
            chain = new OwnerFollowChain(access, factory, halt);
        }
    }

    private static final class FakeAccess implements OwnerFollowChain.FollowAccess {
        private OwnerFollowChain.Snapshot snapshot;
        private int snapshots;
        private int goalCompiles;
        private double lastGoalStopDistance;

        private FakeAccess(OwnerFollowChain.Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public OwnerFollowChain.Snapshot snapshot(NumenPlayer companion) {
            snapshots++;
            return snapshot;
        }

        @Override
        public GoalCompiler.Compiled compileOwnerGoal(
                NumenPlayer companion, double stopDistance) {
            goalCompiles++;
            lastGoalStopDistance = stopDistance;
            return null;
        }

        @Override
        public boolean isWithinStopDistance(NumenPlayer companion, double stopDistance) {
            return snapshot.distance() <= stopDistance;
        }
    }

    private static final class FakeNavigationFactory
            implements OwnerFollowChain.NavigationFactory {
        private final FakeNavigation navigation;
        private int creates;
        private OwnerFollowChain.Navigation lastCreated;
        private PlayerNav.ContextProvider contextProvider;

        private FakeNavigationFactory(FakeNavigation navigation) {
            this.navigation = navigation;
        }

        @Override
        public OwnerFollowChain.Navigation create(
                NumenPlayer companion,
                Supplier<GoalCompiler.Compiled> goal,
                BooleanSupplier reached,
                PlayerNav.ContextProvider contextProvider) {
            creates++;
            navigation.goal = goal;
            navigation.reached = reached;
            this.contextProvider = contextProvider;
            lastCreated = navigation;
            return navigation;
        }
    }

    private static final class FakeNavigation implements OwnerFollowChain.Navigation {
        private PlayerNav.Status status = PlayerNav.Status.RUNNING;
        private int ticks;
        private int stops;
        private boolean readGoalOnTick;
        private Runnable onTick = () -> {};
        private Supplier<GoalCompiler.Compiled> goal;
        @SuppressWarnings("unused")
        private BooleanSupplier reached;

        @Override
        public PlayerNav.Status tick() {
            ticks++;
            if (readGoalOnTick) {
                goal.get();
            }
            onTick.run();
            return status;
        }

        @Override
        public void stop() {
            stops++;
        }
    }

    private static final class FakeHalt implements OwnerFollowChain.HaltAction {
        private int calls;

        @Override
        public void halt(NumenPlayer companion) {
            calls++;
        }
    }

    private static final class CountingChain implements TaskChain {
        private final float priority;
        private int ticks;

        private CountingChain(float priority) {
            this.priority = priority;
        }

        @Override
        public float getPriority(NumenPlayer companion) {
            return priority;
        }

        @Override
        public void tick(NumenPlayer companion) {
            ticks++;
        }

        @Override
        public void onInterrupt(NumenPlayer companion) {}

        @Override
        public String name() {
            return "counting";
        }
    }
}
