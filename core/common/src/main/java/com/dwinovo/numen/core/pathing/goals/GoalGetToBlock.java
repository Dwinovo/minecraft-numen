package com.dwinovo.numen.core.pathing.goals;

import net.minecraft.core.BlockPos;

/**
 * 贴脸目标:走到目标方块旁即可——六邻格、本格,以及正下方两格内
 * 都算到达(yDiff 为负时加一再判,身体两格高,脚低一格也够得着)。
 */
public class GoalGetToBlock implements Goal {

    public final int x;
    public final int y;
    public final int z;

    public GoalGetToBlock(BlockPos pos) {
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        int xDiff = x - this.x;
        int yDiff = y - this.y;
        int zDiff = z - this.z;
        if (yDiff < 0) {
            yDiff++;
        }
        return Math.abs(xDiff) + Math.abs(yDiff) + Math.abs(zDiff) <= 1;
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
        return String.format("GoalGetToBlock{x=%d,y=%d,z=%d}", x, y, z);
    }
}
