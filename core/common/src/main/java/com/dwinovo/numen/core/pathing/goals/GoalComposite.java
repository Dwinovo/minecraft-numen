package com.dwinovo.numen.core.pathing.goals;

import java.util.Arrays;

/** 复合目标:任一子目标满足即到达,启发式取各子目标的最小值。 */
public class GoalComposite implements Goal {

    private final Goal[] goals;

    public GoalComposite(Goal... goals) {
        this.goals = goals;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        for (Goal goal : goals) {
            if (goal.isInGoal(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double min = Double.MAX_VALUE;
        for (Goal goal : goals) {
            min = Math.min(min, goal.heuristic(x, y, z));
        }
        return min;
    }

    public Goal[] goals() {
        return goals;
    }

    @Override
    public String toString() {
        return "GoalComposite" + Arrays.toString(goals);
    }
}
