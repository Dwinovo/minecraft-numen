package com.dwinovo.numen.core.pathing.goals;

import net.minecraft.core.BlockPos;

/** 球形邻域目标:与中心的三维距离平方不超过 rangeSq 即到达。 */
public class GoalNear implements Goal {

    public final int x;
    public final int y;
    public final int z;
    public final int rangeSq;

    public GoalNear(BlockPos pos, int range) {
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
        this.rangeSq = range * range;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        int xDiff = x - this.x;
        int yDiff = y - this.y;
        int zDiff = z - this.z;
        return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff <= rangeSq;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        // 不减 range:估到中心的全量成本(依旧是可行下界的乐观近似)
        int xDiff = x - this.x;
        int yDiff = y - this.y;
        int zDiff = z - this.z;
        return GoalBlock.calculate(xDiff, yDiff, zDiff);
    }

    @Override
    public String toString() {
        return String.format("GoalNear{x=%d,y=%d,z=%d,rangeSq=%d}", x, y, z, rangeSq);
    }
}
