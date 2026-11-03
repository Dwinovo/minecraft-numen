package com.dwinovo.numen.pathing.drive;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.api.entity.Look;
import com.dwinovo.numen.api.entity.Neck;
import com.dwinovo.numen.pathing.plan.Mobility;
import com.dwinovo.numen.pathing.world.Kinematics.Motion;

import net.minecraft.world.phys.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 滑翔的演练:同一个局部控制器在没有障碍的空场里把计划的线飞一遍——飞得到降落点、落得轻、烟花用得少;烟花一支没有,爬不起来飞不到。 */
class GlideRehearsalTest {

    private static final double GRAVITY = 0.08;
    private static final double EYE = 1.62;

    /** 起飞点、爬升十格(水平十格)、平飞、按滑翔比下滑、降落点:地面平,线的形状同 {@link Glide} 排的。 */
    private static Airway line(double run) {
        double climb = 10;
        double descent = climb * Glide.GLIDE_RATIO;
        Vec3 p0 = new Vec3(0.5, 0, 0.5);
        Vec3 p1 = new Vec3(0.5, climb, 0.5 + climb);
        Vec3 touchdown = new Vec3(0.5, 0, 0.5 + run);
        Vec3 p2 = new Vec3(0.5, climb, touchdown.z - descent);
        return Airway.through(List.of(p0, p1, p2, touchdown), touchdown);
    }

    private static final Mobility MOBILITY = new Mobility(false, false, Mobility.VANILLA_FLYING_SPEED, 400, 8, 10);
    private static final int LIMIT = 1200;

    /** 从起飞点起跳之后的空中(高 1.3 格、静止、抬头爬升)起,最多放 {@code rockets} 支烟花,飞到 {@code stopAt} 高度(NaN 是一直飞到落地)。 */
    private static Glide.Result fly(Airway way, int rockets, double stopAt) {
        Vec3 start = way.start();
        Vec3 aim = way.at(Math.min(way.length(), 8));
        return Glide.fly(way, GRAVITY, MOBILITY, EYE, new Motion(start.add(0, 1.3, 0), Vec3.ZERO),
                Neck.at(Look.yawOf(aim.x - start.x, aim.z - start.z), Glide.CLIMB_PITCH), rockets, stopAt, LIMIT, (a, b) -> null);
    }

    /** 一百一十五格的线:有烟花,飞得到降落点,用不了很多支,耗时远低于走路(疾跑一格不到四刻)。 */
    @Test
    void aLongLineIsFlownToTheTouchdownWithFewRockets() {
        Glide.Result result = fly(line(115), 8, Double.NaN);
        assertNotNull(result);
        assertTrue(result.rockets() >= 1 && result.rockets() <= 6, "烟花 " + result.rockets());
        assertTrue(result.ticks() < 115 * 3.5, "耗时 " + result.ticks());
        assertEquals(115.5, result.landedAt().z, 6.0);
    }

    /** 一支烟花也没有:从地面起跳爬不到巡航高度,演练不成。 */
    @Test
    void withoutRocketsTheClimbCannotBeFlown() {
        assertNull(fly(line(115), 0, Double.NaN));
    }

    /** 三百五十格的线(远途):演练飞得完,烟花按比例多用,耗时仍远低于走路。 */
    @Test
    void aFarLineIsStillFlownWithinTheRehearsalBudget() {
        Glide.Result result = fly(line(350), 16, Double.NaN);
        assertNotNull(result);
        assertTrue(result.ticks() < 350 * 3.5, "耗时 " + result.ticks());
        assertEquals(350.5, result.landedAt().z, 6.0);
    }

    /** 只飞线的头一段(爬升):走到那个弧长就停,轨迹随结果交出——从起跳的空中一路往上。 */
    @Test
    void theClimbLegIsFlownAloneAndItsTrajectoryHandedOver() {
        Airway way = line(115);
        double leg = Math.hypot(10, 10);
        Glide.Result climb = fly(way, 8, leg);
        assertNotNull(climb);
        assertEquals(1.3, climb.path().get(0).y, 1e-9);
        assertTrue(climb.landedAt().y > 3, "爬了 " + climb.landedAt());
        assertTrue(climb.ticks() < 200, "爬升 " + climb.ticks() + " 刻");
        assertTrue(climb.path().size() == climb.ticks() + 1, "每刻一点");
    }

    /** 往降落点去的线:富余的高度够长就先平飞、到按滑翔比下滑的那一点再下滑;高得下不去就绕着降落点盘旋着沉。 */
    @Test
    void theApproachGlidesFromWhereTheRatioReachesTheTouchdown() {
        Vec3 touchdown = new Vec3(0.5, 0, 200.5);
        List<Vec3> far = Glide.approachCorners(new Vec3(0.5, 10, 0.5), touchdown);
        assertEquals(3, far.size());
        assertEquals(200.5 - 10 * Glide.GLIDE_RATIO, far.get(1).z, 1e-9);
        assertEquals(10, far.get(1).y, 1e-9);
        // 高得下不去:不朝降落点硬俯冲,绕着它盘旋着往下沉,最后朝降落点下滑
        Vec3 high = new Vec3(0.5, 40, 150.5);
        List<Vec3> spiral = Glide.approachCorners(high, touchdown);
        assertEquals(high, spiral.get(0));
        assertEquals(touchdown, spiral.get(spiral.size() - 1));
        assertTrue(spiral.size() > 12, "盘旋至少一圈:" + spiral.size());
        for (int i = 2; i < spiral.size() - 1; i++) {
            assertTrue(spiral.get(i).y <= spiral.get(i - 1).y + 1e-9, "只往下沉");
            assertEquals(14.0, Math.hypot(spiral.get(i).x - touchdown.x, spiral.get(i).z - touchdown.z), 1e-6);
        }
        assertTrue((spiral.get(spiral.size() - 2).y - touchdown.y) * Glide.GLIDE_RATIO >= 14, "最后一段朝降落点放得下滑翔比");
    }
}
