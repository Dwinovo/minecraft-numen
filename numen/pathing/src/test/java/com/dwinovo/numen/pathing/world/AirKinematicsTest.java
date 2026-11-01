package com.dwinovo.numen.pathing.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwinovo.numen.pathing.world.Kinematics.Keys;
import com.dwinovo.numen.pathing.world.Kinematics.Motion;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

/**
 * 空中的逐刻步进对着原版公式。参照在测试里用最朴素的写法另写一遍(逐刻把输入加进速度、挪、乘阻力;滑翔一项项照
 * {@code LivingEntity.travel} 的次序算),产品代码里只有 {@link Kinematics} 一份;与真身体逐刻对得上由 GameTest 管。
 */
class AirKinematicsTest {

    private static final float FLYING = 0.05F;
    private static final Motion REST = new Motion(Vec3.ZERO, Vec3.ZERO);

    private static Keys cruise(float yaw, boolean sprint) {
        return new Keys(1, 0, false, false, sprint, yaw, 0, false);
    }

    @Test
    void flyingHorizontallyConvergesToTheSteadySpeedOfTheVanillaFigures() {
        // 参照:v = (v + a) * 0.91F,每刻挪 v + a
        double v = 0;
        double a = (double) 0.98F * (double) (FLYING * 2.0F);
        double moved = 0;
        for (int t = 0; t < 300; t++) {
            moved = v + a;
            v = (v + a) * (double) 0.91F;
        }
        Motion m = REST;
        for (int t = 0; t < 300; t++) {
            m = Kinematics.fly(m, cruise(0, true), FLYING);
        }
        double last = Kinematics.fly(m, cruise(0, true), FLYING).pos().z - m.pos().z;
        assertEquals(moved, last, 1e-9);
        assertEquals(moved, Kinematics.flySpeed(FLYING, true), 1e-4, "稳态速度的闭式解");
        assertEquals(1.0888, last, 1e-3, "疾飞每刻约 1.09 格,是不疾跑平飞(0.544)的两倍");
        assertEquals(0.5444, Kinematics.flySpeed(FLYING, false), 1e-3);
    }

    @Test
    void theYawTurnsTheInputIntoTheWorld() {
        Motion west = Kinematics.fly(REST, cruise(90, false), FLYING);
        assertTrue(west.pos().x < 0 && Math.abs(west.pos().z) < 1e-6, "偏航 90 度朝西(-x):" + west.pos());
        Motion left = Kinematics.fly(REST, new Keys(0, 1, false, false, false, 0, 0, false), FLYING);
        assertTrue(left.pos().x > 0 && Math.abs(left.pos().z) < 1e-6, "朝南(+z)时左边是东(+x):" + left.pos());
    }

    @Test
    void holdingJumpClimbsAndReleasingItLetsTheSpeedDieAwayToSixTenths() {
        Keys up = new Keys(0, 0, true, false, false, 0, 0, false);
        // 参照:每刻先加 0.15F(3 × 0.05F),挪这个速度,之后只剩六成
        double v = 0;
        Motion m = REST;
        for (int t = 0; t < 40; t++) {
            double moving = v + (double) (3.0F * FLYING);
            double y = m.pos().y;
            m = Kinematics.fly(m, up, FLYING);
            assertEquals(moving, m.pos().y - y, 1e-12, "第 " + t + " 刻挪的");
            v = moving * 0.6;
            assertEquals(v, m.velocity().y, 1e-12, "第 " + t + " 刻之后的竖直速度");
        }
        Motion before = m;
        Motion coast = Kinematics.fly(m, Keys.idle(0, 0), FLYING);
        assertEquals(before.velocity().y, coast.pos().y - before.pos().y, 1e-12, "松开后这一刻还挪着上一刻留下的速度");
        assertEquals(before.velocity().y * 0.6, coast.velocity().y, 1e-12);
        assertEquals(0.375, Kinematics.flyClimb(FLYING), 1e-6, "按住跳稳态每刻升 0.375 格");
        double down = Kinematics.fly(REST, new Keys(0, 0, false, true, false, 0, 0, false), FLYING).pos().y;
        assertEquals(-(double) (3.0F * FLYING), down, 1e-12, "按潜行往下,力度相同");
    }

    @Test
    void sneakingAndJumpingTogetherCancel() {
        Motion m = Kinematics.fly(REST, new Keys(0, 0, true, true, false, 0, 0, false), FLYING);
        assertEquals(Vec3.ZERO, m.pos());
    }

    @Test
    void glidingLevelFollowsTheVanillaTermsInOrder() {
        // 视线朝南(+z)、水平,起手速度 (0, -0.2, 0.8):重力折掉四分之一,下落的速度转一部分成前进,水平速度向视线靠拢,最后乘阻力
        Motion m = new Motion(Vec3.ZERO, new Vec3(0, -0.2, 0.8));
        double gravity = 0.08;
        double vy = -0.2 + gravity * (-1.0 + 1.0 * 0.75);
        double dive = vy * -0.1 * 1.0;
        vy += dive;
        double vz = 0.8 + dive;
        vz += (1.0 * 0.8 - vz) * 0.1;
        Motion next = Kinematics.glide(m, Keys.idle(0, 0), gravity);
        assertEquals(0, next.pos().x, 1e-12);
        assertEquals(vy * (double) 0.98F, next.pos().y, 1e-9);
        assertEquals(vz * (double) 0.99F, next.pos().z, 1e-9);
        assertEquals(next.pos().z, next.velocity().z, 1e-12, "没有烟花,这一刻挪的就是留下的速度");
    }

    @Test
    void divingStraightDownGainsSpeedAndPitchingUpTradesSpeedForHeight() {
        Motion m = new Motion(Vec3.ZERO, new Vec3(0, 0, 0.5));
        for (int t = 0; t < 60; t++) {
            m = Kinematics.glide(m, Keys.idle(0, 90), 0.08);
        }
        assertTrue(m.velocity().y < -2.0, "垂直俯冲每刻下落远超 2 格:" + m.velocity());
        Motion fast = new Motion(Vec3.ZERO, new Vec3(0, 0, 1.2));
        Motion up = Kinematics.glide(fast, Keys.idle(0, -45), 0.08);
        assertTrue(up.velocity().y > 0, "抬头把前进的速度转成上升:" + up.velocity());
        assertTrue(up.velocity().z < 1.2, "上升靠的是前进的速度");
    }

    @Test
    void aRocketPullsTheVelocityTowardOnePointFiveAlongTheLook() {
        Vec3 look = new Vec3(0, 0, 1);
        Vec3 v = Vec3.ZERO;
        for (int t = 0; t < 40; t++) {
            Vec3 expected = v.add(look.x * 0.1 + (look.x * 1.5 - v.x) * 0.5, look.y * 0.1 + (look.y * 1.5 - v.y) * 0.5,
                    look.z * 0.1 + (look.z * 1.5 - v.z) * 0.5);
            v = Kinematics.boost(v, look);
            assertEquals(expected, v);
        }
        assertEquals(1.7, v.z, 1e-9, "单独推,每刻 v' = 0.5v + 0.85,不动点 1.7");
        Motion boosted = Kinematics.glide(new Motion(Vec3.ZERO, new Vec3(0, 0, 0.5)), new Keys(0, 0, false, false, false, 0, 0, true), 0.08);
        Motion plain = Kinematics.glide(new Motion(Vec3.ZERO, new Vec3(0, 0, 0.5)), Keys.idle(0, 0), 0.08);
        assertEquals(plain.pos(), boosted.pos(), "推力在这一刻的滑翔之后加,这一刻的位置不变");
        assertTrue(boosted.velocity().z > plain.velocity().z + 0.5, "下一刻速度被推高");
    }

    @Test
    void stoppingFromCruiseCostsTicksAndSlidesTheWorkedOutDistance() {
        double cruise = Kinematics.flySpeed(FLYING, true);
        Kinematics.Stop stop = Kinematics.flyStop(FLYING, cruise);
        // 参照:每刻先抵一下(0.05 × 0.98),速度还大于零就挪,再乘阻力
        double v = cruise;
        double distance = 0;
        int ticks = 0;
        while (v > Kinematics.REST) {
            ticks++;
            double moving = v - (double) 0.98F * (double) FLYING;
            if (moving <= 0) {
                break;
            }
            distance += moving;
            v = moving * (double) 0.91F;
        }
        assertEquals(ticks, stop.ticks());
        assertEquals(distance, stop.distance(), 1e-12);
        assertTrue(stop.lost(cruise) > 0, "停下比一路巡航滑完这段距离慢");
        assertTrue(Kinematics.flyStop(FLYING, cruise / 2).distance() < stop.distance(), "冲得慢停得近");
    }

    @Test
    void releasingTheKeysAtCruiseSlidesAboutFiveAndAHalfBlocks() {
        // 不疾跑平飞的稳态:松开之后速度只剩阻力,滑出 v × 0.91 / (1 − 0.91) 格
        double v = Kinematics.flySpeed(FLYING, false) * (double) 0.91F;
        Motion cruising = new Motion(Vec3.ZERO, new Vec3(0, 0, v));
        Vec3 rest = Kinematics.flyRest(cruising, Keys.idle(0, 0), FLYING);
        assertEquals(v / (1 - (double) 0.91F), rest.z, 0.06, "松键滑约 5.5 格");
    }
}
