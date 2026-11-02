package com.dwinovo.numen.pathing.drive;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.pathing.plan.Mobility;

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

    private static Mobility mobility(int rockets) {
        return new Mobility(false, false, Mobility.VANILLA_FLYING_SPEED, 400, rockets, 10);
    }

    /** 一百一十五格的线:有烟花,飞得到降落点,用不了很多支,耗时远低于走路(疾跑一格不到四刻)。 */
    @Test
    void aLongLineIsFlownToTheTouchdownWithFewRockets() {
        Glide.Result result = Glide.rehearse(line(115), GRAVITY, mobility(8), EYE);
        assertNotNull(result);
        assertTrue(result.rockets() >= 1 && result.rockets() <= 6, "烟花 " + result.rockets());
        assertTrue(result.ticks() < 115 * 3.5, "耗时 " + result.ticks());
        assertEquals(115.5, result.landedAt().z, 6.0);
    }

    /** 一支烟花也没有:从地面起跳爬不到巡航高度,演练不成。 */
    @Test
    void withoutRocketsTheClimbCannotBeFlown() {
        assertNull(Glide.rehearse(line(115), GRAVITY, mobility(0), EYE));
    }

    /** 三百五十格的线(远途):演练飞得完,烟花按比例多用,耗时仍远低于走路。 */
    @Test
    void aFarLineIsStillFlownWithinTheRehearsalBudget() {
        Glide.Result result = Glide.rehearse(line(350), GRAVITY, mobility(16), EYE);
        assertNotNull(result);
        assertTrue(result.ticks() < 350 * 3.5, "耗时 " + result.ticks());
        assertEquals(350.5, result.landedAt().z, 6.0);
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
