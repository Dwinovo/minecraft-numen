package com.dwinovo.numen.pathing.drive;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.api.entity.Neck;
import com.dwinovo.numen.pathing.drive.Dynamics.Action;
import com.dwinovo.numen.pathing.drive.Pilot.Supply;
import com.dwinovo.numen.pathing.world.Kinematics.Motion;

import net.minecraft.world.phys.Vec3;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 滑翔的线:起飞的空柱里局部控制器贴着柱子往上爬;够得着的算法。 */
class GlideLineTest {

    private static final double GRAVITY = 0.08;
    private static final double EYE = 1.62;
    private static final int BURN = 10;

    /**
     * 起飞点的空柱:线头是竖直的一段,爬到柱顶(巡航高度)再平飞。受理时只查这根柱子空不空({@code Glide.column}),不模拟,
     * 所以控制器在空场里得真的贴着柱子爬,偏出柱子(口径两格)就撞到柱子外面的方块。
     */
    @Test
    void theControllerClimbsAlongTheColumnItWasPlannedIn() {
        double rise = 24;
        Vec3 base = new Vec3(0.5, 0, 0.5);
        Vec3 top = new Vec3(0.5, rise, 0.5);
        Airway way = Airway.through(List.of(base, top, new Vec3(0.5, rise, 120.5)), new Vec3(0.5, 0, 160.5)).withClimb(rise);
        Dynamics glide = Dynamics.gliding(GRAVITY, BURN, 3);
        Pilot pilot = new Pilot(glide, (a, b) -> null, EYE);
        Motion m = new Motion(base.add(0, 1.3, 0), Vec3.ZERO);
        Neck neck = Neck.at(0, Glide.TAKEOFF_PITCH);
        int burning = 0;
        int cooldown = 0;
        double s = 0;
        double widest = 0;
        for (int tick = 0; tick < 200 && m.pos().y < rise - 1; tick++) {
            s = way.project(m.pos(), s);
            burning = Math.max(0, burning - 1);
            cooldown = Math.max(0, cooldown - 1);
            Action pick = pilot.choose(m, neck, way, s, false, new Supply(cooldown == 0 && burning == 0 ? 100 : 0, burning));
            assertNotNull(pick, "控制器在空柱里挑不出一组");
            neck = neck.toward(pick.yaw(), pick.pitch());
            if (pick.keys().rocket() && cooldown == 0 && burning == 0) {
                burning = glide.burn();
                cooldown = Helm.COOLDOWN;
            }
            m = glide.step(m, pick.keys().facing(neck.yaw(), neck.pitch()).burning(burning > 0));
            widest = Math.max(widest, Math.hypot(m.pos().x - 0.5, m.pos().z - 0.5));
        }
        assertTrue(m.pos().y >= rise - 1, "没爬到柱顶:" + m.pos());
        assertTrue(widest <= 2.0, "爬升偏出了柱子,最远离柱心 " + widest + " 格");
    }

    /** 够得着:在落点那一圈里就行,不然按滑翔比(留余量)加上烟花换来的高度算射程。 */
    @Test
    void reachCountsTheRatioAndTheRocketsLift() {
        Vec3 from = new Vec3(0.5, 20, 0.5);
        assertTrue(Glide.reaches(from, new Vec3(0.5, 0, 100.5), 0), "20 格高、100 格远:20 × 9 × 0.8 = 144");
        assertTrue(!Glide.reaches(from, new Vec3(0.5, 0, 160.5), 0));
        assertTrue(Glide.reaches(from, new Vec3(0.5, 0, 160.5), 10), "烟花换来 10 格高度就够得着");
        assertTrue(Glide.reaches(new Vec3(0.5, 0, 0.5), new Vec3(3.5, 0, 0.5), 0), "已经在落点那一圈里");
    }
}
