package com.dwinovo.numen.core.pathing.exec;

import com.dwinovo.numen.core.pathing.calc.NavGoal;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerNavFollowTest {

    @Test
    void followEntityGoalUsesThreeDimensionalRadius() {
        BlockPos target = new BlockPos(10, 64, -5);
        NavGoal goal = PlayerNav.followEntityGoal(target, 3.0);

        assertTrue(goal.isAt(target));
        assertTrue(goal.isAt(target.offset(3, 0, 0)));
        assertTrue(goal.isAt(target.offset(0, 3, 0)));
        assertFalse(goal.isAt(target.offset(3, 1, 0)));
        assertFalse(goal.isAt(target.offset(0, 4, 0)));
    }

    @Test
    void followEntityGoalClampsNegativeRadiusToCurrentCell() {
        BlockPos target = new BlockPos(10, 64, -5);
        NavGoal goal = PlayerNav.followEntityGoal(target, -5.0);

        assertTrue(goal.isAt(target));
        assertFalse(goal.isAt(target.east()));
    }
}