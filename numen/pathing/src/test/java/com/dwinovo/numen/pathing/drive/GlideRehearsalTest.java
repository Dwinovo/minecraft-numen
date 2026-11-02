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
}
