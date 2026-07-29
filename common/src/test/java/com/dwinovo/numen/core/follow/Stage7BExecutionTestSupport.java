package com.dwinovo.numen.core.follow;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.ChainScheduler;
import com.dwinovo.numen.task.TaskChain;

final class Stage7BExecutionTestSupport {

    private Stage7BExecutionTestSupport() {}

    static FollowService.Subject subject(UUID uuid, double distance, long gameTime) {
        return new FollowService.Subject(
                uuid, "Numen", true, true, true, true, true,
                OptionalDouble.of(distance), gameTime);
    }

    static void syncIntent(
            OwnerFollowChainTestHarness harness,
            FollowStateStore store,
            UUID uuid,
            double distance,
            long gameTime) {
        harness.access.snapshot = OwnerFollowChainTestHarness.snapshot(
                store.getOrDefault(uuid),
                true, true, true, true, true, distance, gameTime);
    }

    static final class SchedulerDriver {
        private TaskChain running;
        private int winnerTicks;

        TaskChain step(List<TaskChain> chains) {
            TaskChain best = ChainScheduler.select(chains, null);
            if (best == null) {
                if (running != null) {
                    running.onInterrupt(null);
                    running = null;
                }
                return null;
            }
            if (running != null && running != best) {
                running.onInterrupt(null);
            }
            running = best;
            best.tick(null);
            winnerTicks++;
            return best;
        }

        TaskChain running() {
            return running;
        }

        int winnerTicks() {
            return winnerTicks;
        }
    }

    static final class MutableChain implements TaskChain {
        private final String name;
        private final List<String> events;
        private float priority = Float.NEGATIVE_INFINITY;
        private int ticks;
        private int interrupts;

        MutableChain(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        void priority(float priority) {
            this.priority = priority;
        }

        int ticks() {
            return ticks;
        }

        int interrupts() {
            return interrupts;
        }

        @Override
        public float getPriority(NumenPlayer companion) {
            return priority;
        }

        @Override
        public void tick(NumenPlayer companion) {
            ticks++;
            events.add(name + ".tick");
        }

        @Override
        public void onInterrupt(NumenPlayer companion) {
            interrupts++;
            events.add(name + ".interrupt");
        }

        @Override
        public String name() {
            return name;
        }
    }

    static final class BoundChainControl implements FollowRuntimeControl {
        private final UUID uuid;
        private final OwnerFollowChain chain;
        private final List<FollowReleaseReason> reasons = new ArrayList<>();

        BoundChainControl(UUID uuid, OwnerFollowChain chain) {
            this.uuid = uuid;
            this.chain = chain;
        }

        @Override
        public UUID companionUuid() {
            return uuid;
        }

        @Override
        public void release(FollowReleaseReason reason) {
            reasons.add(reason);
            chain.release(reason);
        }

        @Override
        public FollowRuntimeSnapshot snapshot(long currentGameTime) {
            return chain.snapshot(currentGameTime);
        }

        List<FollowReleaseReason> reasons() {
            return List.copyOf(reasons);
        }
    }
}
