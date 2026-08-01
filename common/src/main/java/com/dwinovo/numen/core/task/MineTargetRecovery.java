package com.dwinovo.numen.core.task;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/** Bounded, target-scoped retry counter shared by mine arrival and shot recovery. */
final class MineTargetRecovery {

    enum Decision { WAIT, REPATH, GIVE_UP }

    private final int ticksPerAttempt;
    private final int maxRepaths;
    private BlockPos target;
    private int ticks;
    private int repaths;

    MineTargetRecovery(int ticksPerAttempt, int maxRepaths) {
        if (ticksPerAttempt < 1 || maxRepaths < 0) {
            throw new IllegalArgumentException("ticks must be positive and repaths non-negative");
        }
        this.ticksPerAttempt = ticksPerAttempt;
        this.maxRepaths = maxRepaths;
    }

    Decision miss(BlockPos nextTarget) {
        Objects.requireNonNull(nextTarget, "nextTarget");
        if (!nextTarget.equals(target)) {
            target = nextTarget.immutable();
            ticks = 0;
            repaths = 0;
        }
        if (++ticks < ticksPerAttempt) {
            return Decision.WAIT;
        }
        ticks = 0;
        if (repaths < maxRepaths) {
            repaths++;
            return Decision.REPATH;
        }
        return Decision.GIVE_UP;
    }

    void clear() {
        target = null;
        ticks = 0;
        repaths = 0;
    }

    BlockPos target() {
        return target;
    }

    int ticks() {
        return ticks;
    }

    int repaths() {
        return repaths;
    }
}
