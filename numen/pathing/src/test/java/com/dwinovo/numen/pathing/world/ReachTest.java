package com.dwinovo.numen.pathing.world;

import com.dwinovo.numen.api.entity.Reach;
import com.dwinovo.numen.pathing.Vanilla;

import net.minecraft.core.BlockPos;

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
        assertEquals(Y + 1.62, SURVIVAL.eye(0, Y, 0).y, 1e-6);
        assertEquals(0.5, SURVIVAL.eye(0, Y, 0).x, 1e-9);
    }

    @Test
    void creativeReachesACellThatSurvivalDoesNot() {
        // 眼睛在 x = 0.5,那一格的近面在 x = 5:相距 4.5,生存够不着(要小于 4.5),创造够得着
        BlockPos target = new BlockPos(5, Y + 1, 0);
        assertFalse(Reach.reaches(SURVIVAL.eye(0, Y, 0), target, SURVIVAL.blockReach()));
        assertTrue(Reach.reaches(CREATIVE.eye(0, Y, 0), target, CREATIVE.blockReach()));
        assertTrue(Reach.reaches(SURVIVAL.eye(0, Y, 0), target.west(), SURVIVAL.blockReach()));
    }

    @Test
    void theBlockUnderfootIsInReachAndOneFarBelowIsNot() {
        assertTrue(Reach.reaches(SURVIVAL.eye(0, Y, 0), new BlockPos(0, Y - 1, 0), SURVIVAL.blockReach()));
        // 眼睛在 Y + 1.62,那一格顶面在 Y - 3:相距 4.62
        assertFalse(Reach.reaches(SURVIVAL.eye(0, Y, 0), new BlockPos(0, Y - 4, 0), SURVIVAL.blockReach()));
    }

    @Test
    void standingHigherOnASlabShiftsTheEyeUp() {
        // 顶上那格底面在 Y + 6:站在地上眼高 Y + 1.62 差 4.38 够得着;站在下半砖上更够得着
        BlockPos high = new BlockPos(0, Y + 6, 0);
        assertTrue(Reach.reaches(SURVIVAL.eye(0, Y, 0), high, SURVIVAL.blockReach()));
        assertTrue(Reach.reaches(SURVIVAL.eye(0, Y + 0.5, 0), high, SURVIVAL.blockReach()));
    }
}
