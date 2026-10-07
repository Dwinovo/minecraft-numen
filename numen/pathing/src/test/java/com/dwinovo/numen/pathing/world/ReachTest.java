package com.dwinovo.numen.pathing.world;

import com.dwinovo.numen.pathing.Vanilla;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.pathing.Vanilla.CREATIVE;
import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 够得着:眼睛到那一格整块的最近距离小于交互距离。 */
class ReachTest {

    private static final int Y = 64;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    @Test
    void theEyeIsAtTheColumnCentreAtEyeHeight() {
        assertEquals(Y + 1.62, Reach.eye(SURVIVAL, Pose.STANDING, 0, Y, 0).y, 1e-6);
        assertEquals(Y + 1.27, Reach.eye(SURVIVAL, Pose.CROUCHING, 0, Y, 0).y, 1e-6);
        assertEquals(0.5, Reach.eye(SURVIVAL, Pose.STANDING, 0, Y, 0).x, 1e-9);
    }

    @Test
    void creativeReachesACellThatSurvivalDoesNot() {
        // 眼睛在 x = 0.5,那一格的近面在 x = 5:相距 4.5,生存够不着(要小于 4.5),创造够得着
        BlockPos target = new BlockPos(5, Y + 1, 0);
        assertFalse(Reach.reaches(SURVIVAL, Pose.STANDING, 0, Y, 0, target));
        assertTrue(Reach.reaches(CREATIVE, Pose.STANDING, 0, Y, 0, target));
        assertTrue(Reach.reaches(SURVIVAL, Pose.STANDING, 0, Y, 0, target.west()));
    }

    @Test
    void theBlockUnderfootIsInReachAndOneFarBelowIsNot() {
        assertTrue(Reach.reaches(SURVIVAL, Pose.STANDING, 0, Y, 0, new BlockPos(0, Y - 1, 0)));
        // 眼睛在 Y + 1.62,那一格顶面在 Y - 3:相距 4.62
        assertFalse(Reach.reaches(SURVIVAL, Pose.STANDING, 0, Y, 0, new BlockPos(0, Y - 4, 0)));
    }

    @Test
    void standingHigherOnASlabShiftsTheEyeUp() {
        // 顶上那格底面在 Y + 6:站在地上眼高 Y + 1.62 差 4.38 够得着,蹲下就够不着;站在下半砖上又够得着
        BlockPos high = new BlockPos(0, Y + 6, 0);
        assertTrue(Reach.reaches(SURVIVAL, Pose.STANDING, 0, Y, 0, high));
        assertFalse(Reach.reaches(SURVIVAL, Pose.CROUCHING, 0, Y, 0, high));
        assertTrue(Reach.reaches(SURVIVAL, Pose.CROUCHING, 0, Y + 0.5, 0, high));
    }
}
