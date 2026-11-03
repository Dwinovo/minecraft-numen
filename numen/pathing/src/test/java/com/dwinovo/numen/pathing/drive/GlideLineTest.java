package com.dwinovo.numen.pathing.drive;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.api.entity.Neck;
import com.dwinovo.numen.pathing.drive.Dynamics.Action;
import com.dwinovo.numen.pathing.drive.Pilot.Supply;
import com.dwinovo.numen.pathing.world.Kinematics.Motion;

import net.minecraft.world.phys.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 滑翔的线:起飞的空柱里局部控制器贴着柱子往上爬;往降落点去的进近线。 */
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

    /**
     * 往降落点去的线:富余的高度够长就先平飞、到按滑翔比下滑的那一点再下滑;高了就平飞、俯冲掉高度、降落点前拉平再滑下去;
     * 高得连俯冲也放不下,才绕着降落点盘旋着沉(也是俯冲那一路有遮挡时的替补)。
     */
    @Test
    void theApproachGlidesFromWhereTheRatioReachesTheTouchdown() {
        Vec3 touchdown = new Vec3(0.5, 0, 200.5);
        List<List<Vec3>> far = Glide.descents(new Vec3(0.5, 10, 0.5), touchdown);
        assertEquals(1, far.size());
        assertEquals(3, far.get(0).size());
        assertEquals(200.5 - 10 * Glide.GLIDE_RATIO, far.get(0).get(1).z, 1e-9);
        assertEquals(10, far.get(0).get(1).y, 1e-9);
        // 高了一些:平飞到俯冲的起点,按俯冲的下滑比掉到拉平点,再按滑翔比滑到降落点;盘旋排在后面
        Vec3 cruise = new Vec3(0.5, 16, 0.5);
        Vec3 near = new Vec3(0.5, 0, 110.5);
        List<List<Vec3>> dive = Glide.descents(cruise, near);
        assertEquals(2, dive.size());
        List<Vec3> line = dive.get(0);
        assertEquals(4, line.size());
        assertEquals(16, line.get(1).y, 1e-9);
        assertEquals(line.get(1).y - line.get(2).y, (line.get(2).z - line.get(1).z) / 5.0, 1e-9);
        assertEquals((line.get(3).z - line.get(2).z) / Glide.GLIDE_RATIO, line.get(2).y - line.get(3).y, 1e-9);
        assertTrue(line.get(1).z > cruise.z && line.get(1).z < line.get(2).z, "先平飞一段再俯冲");
        // 水平距离放不下平飞的一段:从起点直接俯冲,下滑比夹在最陡与俯冲之间
        List<List<Vec3>> steep = Glide.descents(new Vec3(0.5, 24, 0.5), new Vec3(0.5, 0, 126.5));
        List<Vec3> direct = steep.get(0);
        assertEquals(3, direct.size());
        double ratio = (direct.get(1).z - direct.get(0).z) / (direct.get(0).y - direct.get(1).y);
        assertTrue(ratio >= Glide.STEEPEST_RATIO && ratio <= 5.0, "直接俯冲的下滑比 " + ratio);
        // 高得下不去:不朝降落点硬俯冲,绕着它盘旋着往下沉,最后朝降落点下滑
        Vec3 high = new Vec3(0.5, 40, 150.5);
        List<List<Vec3>> options = Glide.descents(high, touchdown);
        assertEquals(1, options.size());
        List<Vec3> spiral = options.get(0);
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
