package com.dwinovo.numen.core.follow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.entity.NumenPlayer;

final class OwnerFollowChainTestHarness {

    final FakeAccess access;
    final FakeNavigationFactory factory = new FakeNavigationFactory();
    final FakeHalt halt = new FakeHalt();
    final OwnerFollowChain chain;

    OwnerFollowChainTestHarness(OwnerFollowChain.Snapshot snapshot) {
        access = new FakeAccess(snapshot);
        chain = new OwnerFollowChain(access, factory, halt);
    }

    static FollowState enabled() {
        return new FollowState(
                true, false, FollowState.CURRENT_SCHEMA_VERSION, null, null);
    }

    static OwnerFollowChain.Snapshot activeAt(double distance, long gameTime) {
        return snapshot(enabled(), true, true, true, true, true, distance, gameTime);
    }

    static OwnerFollowChain.Snapshot snapshot(
            FollowState state,
            boolean companionValid,
            boolean ownerUuidPresent,
            boolean ownerOnline,
            boolean ownerValid,
            boolean sameLevel,
            double distance,
            long gameTime) {
        return new OwnerFollowChain.Snapshot(state, companionValid, ownerUuidPresent,
                ownerOnline, ownerValid, sameLevel, distance, gameTime);
    }

    static final class FakeAccess implements OwnerFollowChain.FollowAccess {
        OwnerFollowChain.Snapshot snapshot;
        int snapshots;
        int goalCompiles;
        double lastGoalStopDistance;

        FakeAccess(OwnerFollowChain.Snapshot snapshot) {
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
        public boolean isWithinStopDistance(
                NumenPlayer companion, double stopDistance) {
            return snapshot.distance() <= stopDistance;
        }
    }

    static final class FakeNavigationFactory
            implements OwnerFollowChain.NavigationFactory {
        final List<FakeNavigation> created = new ArrayList<>();
        PlayerNav.Status nextStatus = PlayerNav.Status.RUNNING;
        PlayerNav.ContextProvider contextProvider;
        BooleanSupplier sprintAllowed;

        @Override
        public OwnerFollowChain.Navigation create(
                NumenPlayer companion,
                Supplier<GoalCompiler.Compiled> goal,
                BooleanSupplier reached,
                PlayerNav.ContextProvider contextProvider,
                BooleanSupplier sprintAllowed) {
            FakeNavigation navigation = new FakeNavigation(nextStatus);
            navigation.goal = goal;
            navigation.reached = reached;
            navigation.sprintAllowed = sprintAllowed;
            created.add(navigation);
            this.contextProvider = contextProvider;
            this.sprintAllowed = sprintAllowed;
            return navigation;
        }

        FakeNavigation last() {
            return created.get(created.size() - 1);
        }
    }

    static final class FakeNavigation implements OwnerFollowChain.Navigation {
        PlayerNav.Status status;
        int ticks;
        int stops;
        Runnable onTick = () -> {};
        Supplier<GoalCompiler.Compiled> goal;
        BooleanSupplier reached;
        BooleanSupplier sprintAllowed;

        FakeNavigation(PlayerNav.Status status) {
            this.status = status;
        }

        @Override
        public PlayerNav.Status tick() {
            ticks++;
            onTick.run();
            return status;
        }

        @Override
        public void stop() {
            stops++;
        }
    }

    static final class FakeHalt implements OwnerFollowChain.HaltAction {
        int calls;

        @Override
        public void halt(NumenPlayer companion) {
            calls++;
        }
    }
}
