package com.dwinovo.numen.pathing.drive;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.api.entity.Neck;
import com.dwinovo.numen.pathing.drive.Dynamics.Action;
import com.dwinovo.numen.pathing.drive.Pilot.Supply;
import com.dwinovo.numen.pathing.world.Kinematics.Motion;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 局部控制器:撞方块的推演被否决;飞着能停的(创造飞行)挑刹得住的,停不下来的(滑翔)一组都过不去也挑撞上之前飞得最久的。 */
class PilotTest {

    private static final double EYE = 1.62;

    /** z 大于 {@code z} 的整个半空间都是墙。 */
    private static Airway.Sweep wallAt(double z) {
        return (from, to) -> Math.max(from.z, to.z) > z ? new BlockPos(0, 0, (int) z + 1) : null;
    }

    private static Airway line() {
        return Airway.through(List.of(new Vec3(0, 10, 0), new Vec3(0, 10, 100)), null);
    }

    /** 飞着朝墙冲,松开键滑出去要撞:不挑疾飞也不挑往前,挑往后刹。 */
    @Test
    void aFlyingBodyHeadedForAWallBrakesInsteadOfCruising() {
        Pilot pilot = new Pilot(Dynamics.flying(0.05F), wallAt(20), EYE);
        Motion now = new Motion(new Vec3(0, 10, 14), new Vec3(0, 0, 0.6));
        Action pick = pilot.choose(now, Neck.at(0, 0), line(), 14, false, Supply.NONE);
        assertNotNull(pick);
        assertTrue(pick.keys().forward() < 0, "该往后刹:" + pick.keys());
    }

    /** 离墙还远:疾飞,不用刹车。 */
    @Test
    void farFromTheWallItCruises() {
        Pilot pilot = new Pilot(Dynamics.flying(0.05F), wallAt(90), EYE);
        Motion now = new Motion(new Vec3(0, 10, 0), Vec3.ZERO);
        Action pick = pilot.choose(now, Neck.at(0, 0), line(), 0, false, Supply.NONE);
        assertNotNull(pick);
        assertTrue(pick.keys().forward() > 0 && pick.keys().sprint(), "该疾飞:" + pick.keys());
    }

    /** 滑翔挡在整个前方(哪个方向都撞)时不放弃:还是交出一组(撞上之前飞得最久的),并说得出撞的是哪一格。 */
    @Test
    void aGliderBoxedInStillPicksTheLongestFlight() {
        Pilot pilot = new Pilot(Dynamics.gliding(0.08, 10, 3), wallAt(20), EYE);
        Motion now = new Motion(new Vec3(0, 10, 10), new Vec3(0, 0, 1.5));
        Action pick = pilot.choose(now, Neck.at(0, 0), line(), 10, false, new Supply(3, 0));
        assertNotNull(pick);
        assertNotNull(pilot.blocker());
    }

    /**
     * 滑翔带着会伤身的坠落距离(竖直速度每刻 -0.8,已攒了四格)飞到降落点附近、离地七格半:不接着俯冲,拉平——
     * 竖直速度要拉回每刻 -0.5 之上坠落距离才清零,等到离地不到五格再拉,拉回来的这几刻身体还要往下掉。
     */
    @Test
    void aGliderCarryingAHurtingFallPullsUpBeforeItIsTooCloseToTheGround() {
        Pilot pilot = new Pilot(Dynamics.gliding(0.08, 10, 3), (a, b) -> null, EYE);
        Vec3 down = new Vec3(0, 0, 60);
        Airway way = Airway.through(List.of(new Vec3(0, 7.5, 0), down), down).withZone(12);
        pilot.fallen(4.0);
        Motion now = new Motion(new Vec3(0, 7.5, 25), new Vec3(0, -0.8, 1.5));
        Action pick = pilot.choose(now, Neck.at(0, 28), way, 25, false, new Supply(0, 0));
        assertNotNull(pick);
        assertTrue(pick.pitch() < 0, "该拉平,却挑了俯仰 " + pick.pitch());
    }
}
