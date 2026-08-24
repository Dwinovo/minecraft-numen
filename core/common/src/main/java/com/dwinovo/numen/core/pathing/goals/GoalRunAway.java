package com.dwinovo.numen.core.pathing.goals;

import net.minecraft.core.BlockPos;

/**
 * 逃离目标:与所有威胁点的 XZ 距离都拉开到指定值即到达;
 * 可选 maintainY 要求同时保持指定高度。启发式为"到最近威胁点的
 * XZ 估价"取负——越远越优;维持高度时按 0.6/1.5 加权混入高度项。
 */
public class GoalRunAway implements Goal {

    private final BlockPos[] from;
    private final int distanceSq;
    private final Integer maintainY;

    public GoalRunAway(double distance, BlockPos... from) {
        this(distance, null, from);
    }

    public GoalRunAway(double distance, Integer maintainY, BlockPos... from) {
        if (from.length == 0) {
            throw new IllegalArgumentException("逃离目标至少需要一个威胁点");
        }
        this.from = from;
        this.distanceSq = (int) (distance * distance);
        this.maintainY = maintainY;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        if (maintainY != null && maintainY != y) {
            return false;
        }
        for (BlockPos p : from) {
            int diffX = x - p.getX();
            int diffZ = z - p.getZ();
            int distSq = diffX * diffX + diffZ * diffZ;
            if (distSq < distanceSq) {
                return false;
            }
        }
        return true;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double min = Double.MAX_VALUE;
        for (BlockPos p : from) {
            double h = GoalXZ.calculate(p.getX() - x, p.getZ() - z);
            if (h < min) {
                min = h;
            }
        }
        min = -min;
        if (maintainY != null) {
            min = min * 0.6 + GoalYLevel.calculate(maintainY, y) * 1.5;
        }
        return min;
    }

    @Override
    public String toString() {
        return maintainY != null
                ? String.format("GoalRunAway{distSq=%d,y=%d}", distanceSq, maintainY)
                : String.format("GoalRunAway{distSq=%d}", distanceSq);
    }
}
