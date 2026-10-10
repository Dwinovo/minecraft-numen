package com.dwinovo.numen.pathing.world;

import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwinovo.numen.pathing.Vanilla;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 运动学对照原版逐刻的数。参照在测试里另写:一刻一刻地积分原版的运动方程(先把输入加进速度、按速度移动、再乘摩擦与阻力),
 * 或用等比数列的闭式解;产品代码里只有 {@link Kinematics} 一份。
 */
class KinematicsTest {

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    /** 同一具身体换一个移动速度、起跳力度、重力。 */
    private static BodyStats body(double speed, double jump, double gravity) {
        return new BodyStats(SURVIVAL.standing(), 0.6, jump, gravity, speed, 0.3, 4.5, false, false);
    }

    /** 参照:地上一直按前进,逐刻推到稳定,返回最后一刻走的距离(格)。 */
    private static double integratedStride(double speed, double friction) {
        double v = 0;
        double moved = 0;
        for (int t = 0; t < 400; t++) {
            v += speed * (0.21600002 / (friction * friction * friction)) * 0.98;
            moved = v;
            v *= friction * 0.91;
        }
        return moved;
    }

    @Test
    void groundSpeedsMatchTheTickByTickIntegrationAndTheVanillaFigures() {
        double walk = integratedStride(0.1, 0.6);
        assertEquals(walk, 1 / Kinematics.walkTicksPerBlock(SURVIVAL), 1e-9);
        assertEquals(4.317, walk * 20, 1e-3, "原版平走 4.317 格每秒");
        double sprint = integratedStride(0.1 * 1.3, 0.6);
        assertEquals(sprint, 1 / Kinematics.sprintTicksPerBlock(SURVIVAL), 1e-9);
        assertEquals(5.612, sprint * 20, 1e-3, "原版疾跑 5.612 格每秒");
        assertEquals(1.295, 20 / Kinematics.sneakTicksPerBlock(SURVIVAL), 1e-3, "原版潜行约 1.3 格每秒");
    }

    @Test
    void groundSpeedFollowsTheBodysMovementSpeed() {
        BodyStats swift = body(0.2, 0.42, 0.08);
        assertEquals(Kinematics.walkTicksPerBlock(SURVIVAL) / 2, Kinematics.walkTicksPerBlock(swift), 1e-9, "速度翻倍,走一格的刻数减半");
        assertEquals(integratedStride(0.2 * 1.3, 0.6), 1 / Kinematics.sprintTicksPerBlock(swift), 1e-9);
    }

    @Test
    void steadyStrideFollowsTheBlockFriction() {
        assertEquals(integratedStride(0.1, 0.98), Kinematics.steadyStride(0.1, 0.98), 1e-9);
        assertEquals(integratedStride(0.1, 0.8), Kinematics.steadyStride(0.1, 0.8), 1e-9);
    }

    @Test
    void wadingBlendsFromWaterToLandWithTheEfficiency() {
        assertEquals(Kinematics.walkTicksPerBlock(SURVIVAL), Kinematics.wadeTicksPerBlock(SURVIVAL, 1), 1e-6, "效率 1 与陆上平走一样快(原版的 0.54600006 是 float)");
        // 水里:摩擦 0.8、加速度 0.02 × 0.98,逐刻积分
        double v = 0;
        double moved = 0;
        for (int t = 0; t < 400; t++) {
            v += 0.02 * 0.98;
            moved = v;
            v *= 0.8;
        }
        assertEquals(moved, 1 / Kinematics.wadeTicksPerBlock(SURVIVAL, 0), 1e-9);
        assertTrue(Kinematics.wadeTicksPerBlock(SURVIVAL, 0.5) < Kinematics.wadeTicksPerBlock(SURVIVAL, 0));
    }

    /** 参照:起跳后一刻一刻地走,返回过了顶点、脚第一次不高于 {@code rise} 的那一刻;顶点够不着就是无穷。 */
    private static double integratedLanding(double jump, double gravity, double rise) {
        double y = 0;
        double v = jump;
        double apex = 0;
        for (int t = 1; t < 300; t++) {
            y += v;
            apex = Math.max(apex, y);
            if (v < 0 && y <= rise) {
                return apex >= rise ? t : Double.POSITIVE_INFINITY;
            }
            v = (v - gravity) * 0.98;
        }
        return Double.POSITIVE_INFINITY;
    }

    @Test
    void aVanillaJumpPeaksAt1point252AndLandsOnTheNextBlockInNineTicks() {
        double peak = Kinematics.jumpHeight(SURVIVAL, 1.0);
        assertTrue(peak > 1.25 && peak < 1.26, "原版玩家起跳约 1.252,实为 " + peak);
        assertTrue(Kinematics.jumpHeight(SURVIVAL, 0.5) < 0.5, "蜂蜜块上跳不过半格");
        assertEquals(9, Kinematics.landTicks(SURVIVAL, 1.0, 1.0), "落在 +1 上是 9 刻");
        assertEquals(12, Kinematics.landTicks(SURVIVAL, 1.0, 0.0), "落回同一高度是 12 刻");
        assertEquals(Double.POSITIVE_INFINITY, Kinematics.landTicks(SURVIVAL, 1.0, 1.5), "顶点够不着 +1.5");
    }

    @Test
    void chainedJumpsWaitOutTheJumpDelay() {
        assertEquals(10, Kinematics.jumpCycleTicks(SURVIVAL, 1.0, 1.0), "落地要 9 刻,可起跳间隔 10 刻");
        assertEquals(12, Kinematics.jumpCycleTicks(SURVIVAL, 1.0, 0.0), "落回同一高度本来就比间隔长");
    }

    @Test
    void landingTicksMatchTheTickByTickIntegrationForOtherBodies() {
        for (double[] b : new double[][] {{0.42, 0.08}, {0.52, 0.08}, {0.42, 0.04}, {0.6, 0.12}}) {
            BodyStats body = body(0.1, b[0], b[1]);
            for (double rise : new double[] {-3, -1, 0, 0.5, 1, 1.25}) {
                assertEquals(integratedLanding(b[0], b[1], rise), Kinematics.landTicks(body, 1.0, rise),
                        "起跳力度 " + b[0] + " 重力 " + b[1] + " 落在 " + rise);
            }
        }
    }

    @Test
    void risingOneBlockTakesAboutThreeTicksAndIsALowerBoundOnTheJump() {
        double rise = Kinematics.riseTicks(SURVIVAL, 1.0, 1.0);
        // 逐刻:0.42 → 0.7532 → 1.0013,第三刻(速度 0.248136)越过一格
        assertEquals(2 + (1 - (0.42 + 0.3332)) / 0.248136, rise, 1e-3);
        assertTrue(rise < Kinematics.landTicks(SURVIVAL, 1.0, 1.0));
        assertEquals(Double.POSITIVE_INFINITY, Kinematics.riseTicks(SURVIVAL, 1.0, 1.3));
    }

    @Test
    void freeFallMatchesTheClosedFormOfTheTickRecurrence() {
        // v(k+1) = (v(k) + g) × 0.98,v(0) = 0;先移动再加速,落 n 刻共 y(n) = A (n − (1 − d^n)/(1 − d)),A = g d/(1 − d)
        double g = 0.08;
        double d = 0.98;
        double a = g * d / (1 - d);
        for (double distance : new double[] {1, 2, 3, 4.5, 10, 20}) {
            int n = 0;
            while (a * (n + 1 - (1 - Math.pow(d, n + 1)) / (1 - d)) < distance) {
                n++;
            }
            double before = a * (n - (1 - Math.pow(d, n)) / (1 - d));
            double after = a * (n + 1 - (1 - Math.pow(d, n + 1)) / (1 - d));
            assertEquals(n + (distance - before) / (after - before), Kinematics.fallTicks(g, distance), 1e-9, "落 " + distance + " 格");
        }
        assertEquals(0, Kinematics.fallTicks(g, 0));
        assertTrue(Kinematics.fallTicks(0.04, 4) > Kinematics.fallTicks(0.08, 4), "重力小落得慢");
    }

    @Test
    void ticksToLandStepsThePlayerDownToTheLandingHeight() {
        // 从 y = 70 静止落到 65:逐刻
        double y = 70;
        double v = 0;
        int ticks = 0;
        while (y > 65) {
            y += v;
            v = (v - 0.08) * 0.98;
            ticks++;
        }
        assertEquals(ticks, Kinematics.ticksToLand(70, 0, 65, 0.08, 80));
        assertEquals(1, Kinematics.ticksToLand(65, 0, 65, 0.08, 80), "已经在落点上也算一刻");
    }

    @Test
    void laddersAreSlowerGoingUpThanDown() {
        assertEquals(20 / 2.35, Kinematics.climbUpTicksPerBlock(SURVIVAL), 0.05, "原版爬梯子约 2.35 格每秒");
        assertEquals(20 / 3.0, Kinematics.climbDownTicksPerBlock(), 1e-9, "往下滑 3 格每秒");
    }

    @Test
    void edgeGeometryAddsUpToOneBlockOfWalking() {
        double walk = Kinematics.walkTicksPerBlock(SURVIVAL);
        assertEquals(walk, Kinematics.walkOffEdgeTicks(SURVIVAL) + Kinematics.centerAfterFallTicks(SURVIVAL), 1e-6);
        assertEquals(0.8 * walk, Kinematics.walkOffEdgeTicks(SURVIVAL), 1e-6, "半格走到边再 0.3 格让身体离开边沿");
    }

    @Test
    void aLeapIsNeverFasterThanItsFlight() {
        double sprint = Kinematics.sprintTicksPerBlock(SURVIVAL);
        assertEquals(4 * sprint, Kinematics.leapTicks(SURVIVAL, 4, true, 0), 1e-9, "四列远的助跑跳按步速");
        assertEquals(12, Kinematics.leapTicks(SURVIVAL, 2, true, 0), 1e-9, "两列远的跳受空中时间限制");
        assertEquals(9, Kinematics.leapTicks(SURVIVAL, 1, true, 1.0), 1e-9, "跳上一格是 9 刻");
    }

    @Test
    void stopDistanceCoastsOneTickThenBrakesAndGrowsWithSpeed() {
        assertEquals(0, Kinematics.stopDistance(SURVIVAL, 0), 1e-9);
        double sprint = Kinematics.sprintSpeed(SURVIVAL);
        assertEquals(1 / Kinematics.sprintTicksPerBlock(SURVIVAL), sprint, 1e-9);
        // 疾跑稳态 0.2806 格每刻:先滑一刻(×0.546)0.153,再按反向键减去 0.098 滑 0.055,第三刻停住
        assertEquals(0.153 + 0.055, Kinematics.stopDistance(SURVIVAL, sprint), 2e-3);
        assertTrue(Kinematics.stopDistance(SURVIVAL, sprint * Math.sqrt(0.5)) < Kinematics.stopDistance(SURVIVAL, sprint));
    }
}
