package com.dwinovo.numen.core.pathing.goals;

import com.dwinovo.numen.core.pathing.moves.ActionCosts;

/** 高度目标:到达指定 Y 层即可,水平位置不限。 */
public class GoalYLevel implements Goal {

    public final int level;

    public GoalYLevel(int level) {
        this.level = level;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return y == level;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return calculate(level, y);
    }

    /**
     * 竖直移动的乐观单格成本:下降按坠落 2 格耗时的一半
     * (3.894/格),上升按起跳单格耗时(3.163/格,刻意不含跳跃罚金)。
     */
    public static double calculate(int goalY, int currentY) {
        if (currentY > goalY) {
            return ActionCosts.FALL_N_BLOCKS_COST[2] / 2 * (currentY - goalY);
        }
        if (currentY < goalY) {
            return ActionCosts.JUMP_ONE_BLOCK_COST * (goalY - currentY);
        }
        return 0;
    }

    @Override
    public String toString() {
        return String.format("GoalYLevel{y=%d}", level);
    }
}
