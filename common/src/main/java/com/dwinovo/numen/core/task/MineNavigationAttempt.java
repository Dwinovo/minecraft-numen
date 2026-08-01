package com.dwinovo.numen.core.task;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Set;

/** Business target owned by one mine navigation attempt. */
record MineNavigationAttempt(BlockPos pos, Kind kind) {

    enum Kind { ORE, DROP }

    MineNavigationAttempt {
        pos = pos.immutable();
    }

    static MineNavigationAttempt nearest(BlockPos feet, List<BlockPos> ores, List<BlockPos> drops) {
        MineNavigationAttempt best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos ore : ores) {
            double distance = feet.distSqr(ore);
            if (distance < bestDistance) {
                best = new MineNavigationAttempt(ore, Kind.ORE);
                bestDistance = distance;
            }
        }
        for (BlockPos drop : drops) {
            double distance = feet.distSqr(drop);
            if (distance < bestDistance) {
                best = new MineNavigationAttempt(drop, Kind.DROP);
                bestDistance = distance;
            }
        }
        return best;
    }

    void recordFailure(Set<BlockPos> oreBlacklist, Set<BlockPos> dropBlacklist) {
        if (kind == Kind.ORE) {
            oreBlacklist.add(pos);
        } else {
            dropBlacklist.add(pos);
        }
    }
}
