package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.FakeWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 够得着:眼睛到那一格整块的最近距离小于交互距离。 */
class ReachTest {

    private static final int Y = 64;
    /** 生存与创造的方块交互距离。 */
    private static final double SURVIVAL = 4.5;
    private static final double CREATIVE = 5.0;

    /** 站在 (0, Y, 0) 那一格上的眼睛。 */
    private static Vec3 eye(double feetY) {
        return new Vec3(0.5, feetY + 1.62, 0.5);
    }

    @BeforeAll
    static void boot() {
        assertTrue(FakeWorld.boot(), "Minecraft 引导不可用");
    }

    @Test
    void creativeReachesACellThatSurvivalDoesNot() {
        // 眼睛在 x = 0.5,那一格的近面在 x = 5:相距 4.5,生存够不着(要小于 4.5),创造够得着
        BlockPos target = new BlockPos(5, Y + 1, 0);
        assertFalse(Reach.reaches(eye(Y), target, SURVIVAL));
        assertTrue(Reach.reaches(eye(Y), target, CREATIVE));
        assertTrue(Reach.reaches(eye(Y), target.west(), SURVIVAL));
    }

    @Test
    void theBlockUnderfootIsInReachAndOneFarBelowIsNot() {
        assertTrue(Reach.reaches(eye(Y), new BlockPos(0, Y - 1, 0), SURVIVAL));
        // 眼睛在 Y + 1.62,那一格顶面在 Y - 3:相距 4.62
        assertFalse(Reach.reaches(eye(Y), new BlockPos(0, Y - 4, 0), SURVIVAL));
    }

    @Test
    void standingHigherOnASlabShiftsTheEyeUp() {
        // 顶上那格底面在 Y + 6:站在地上眼高 Y + 1.62 差 4.38 够得着;站在下半砖上更够得着
        BlockPos high = new BlockPos(0, Y + 6, 0);
        assertTrue(Reach.reaches(eye(Y), high, SURVIVAL));
        assertTrue(Reach.reaches(eye(Y + 0.5), high, SURVIVAL));
    }
}
