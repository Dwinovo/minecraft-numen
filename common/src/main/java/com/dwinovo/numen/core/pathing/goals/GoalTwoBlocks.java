package com.dwinovo.numen.core.pathing.goals;

import net.minecraft.core.BlockPos;

/**
 * 双格目标:身体占进目标格或其下一格即算到达(挖穿目标方块后
 * 站进去的场景:脚可以在目标格,也可以在其正下)。
 */
public class GoalTwoBlocks implements Goal {

    public final int x;
    public final int y;
    public final int z;

    public GoalTwoBlocks(BlockPos pos) {
        this(pos.getX(), pos.getY(), pos.getZ());
    }

    public GoalTwoBlocks(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return x == this.x && (y == this.y || y == this.y - 1) && z == this.z;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        int xDiff = x - this.x;
        int yDiff = y - this.y;
        int zDiff = z - this.z;
        if (yDiff < 0) {
            yDiff++;
        }
        return GoalBlock.calculate(xDiff, yDiff, zDiff);
    }

    public BlockPos getGoalPos() {
        return new BlockPos(x, y, z);
    }

    @Override
    public String toString() {
        return String.format("GoalTwoBlocks{x=%d,y=%d,z=%d}", x, y, z);
    }
}
