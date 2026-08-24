package com.dwinovo.numen.core.pathing.goals;

/**
 * 反向目标:远离原目标。永不"到达"(isInGoal 恒 false),
 * 启发式取原目标的相反数——离得越远越优。
 */
public class GoalInverted implements Goal {

    public final Goal origin;

    public GoalInverted(Goal origin) {
        this.origin = origin;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return -origin.heuristic(x, y, z);
    }

    @Override
    public String toString() {
        return String.format("GoalInverted{%s}", origin);
    }
}
