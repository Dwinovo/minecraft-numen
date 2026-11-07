package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.api.entity.Neck;
import com.dwinovo.numen.pathing.drive.Dynamics.Action;
import com.dwinovo.numen.pathing.drive.Pilot.Supply;
import com.dwinovo.numen.pathing.world.Kinematics;
import com.dwinovo.numen.pathing.world.Kinematics.Motion;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 创造飞行到点悬停:从相邻一格(带一点余速、脸朝四个方向)出发,控制器按到达判据({@link AirControl#settled})在限定刻数内停得住。 */
class HoverTest {

    private static final double EYE = 1.62;
    private static final int LIMIT = 40;

    private static int ticksToSettle(Vec3 start, Vec3 v0, BlockPos node, float yaw0) {
        Pilot pilot = new Pilot(Dynamics.flying(0.05F), (from, to) -> null, EYE);
        Vec3 target = new Vec3(node.getX() + 0.5, node.getY() + AirControl.HOVER, node.getZ() + 0.5);
        Airway way = Airway.through(List.of(start, target), null);
        Motion m = new Motion(start, v0);
        Neck neck = Neck.at(yaw0, 0);
        double s = 0;
        for (int t = 0; t < 4 * LIMIT; t++) {
            if (AirControl.settled(m.pos(), m.velocity(), node)) {
                return t;
            }
            s = way.project(m.pos(), s);
            Action pick = pilot.choose(m, neck, way, s, false, Supply.NONE);
            neck = neck.toward(pick.yaw(), pick.pitch());
            m = Kinematics.fly(m, pick.keys().facing(neck.yaw(), neck.pitch()), 0.05F);
        }
        return -1;
    }

    @Test
    void anAdjacentCellIsSettledInQuickly() {
        BlockPos node = new BlockPos(0, 0, 0);
        List<String> slow = new ArrayList<>();
        int worst = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    for (double speed : new double[] {0, 0.04}) {
                        for (float yaw : new float[] {0, 90, 180, 270}) {
                            Vec3 start = new Vec3(dx + 0.5, dy + AirControl.HOVER, dz + 0.5);
                            Vec3 v0 = new Vec3(-dx * speed, 0, -dz * speed);
                            int t = ticksToSettle(start, v0, node, yaw);
                            worst = Math.max(worst, t);
                            if (t < 0 || t > LIMIT) {
                                slow.add("d=(" + dx + "," + dy + "," + dz + ") v=" + speed + " yaw=" + yaw + " -> " + t);
                            }
                        }
                    }
                }
            }
        }
        assertTrue(slow.isEmpty(), slow.size() + " 种出发状态超过 " + LIMIT + " 刻没停住(最慢 " + worst + "):" + slow);
    }

    /** 同一格里不论脚在哪个高度(含刚好在判据边上的),都停得进去。 */
    @Test
    void anyHeightInTheCellIsSettledIn() {
        BlockPos node = new BlockPos(0, 0, 0);
        List<String> slow = new ArrayList<>();
        for (int k = -20; k <= 40; k++) {
            Vec3 start = new Vec3(0.5, k * 0.025, 0.5);
            int t = ticksToSettle(start, Vec3.ZERO, node, 0);
            if (t < 0 || t > LIMIT) {
                slow.add("y=" + k * 0.025 + " -> " + t);
            }
        }
        assertTrue(slow.isEmpty(), "停不进去的高度:" + slow);
    }
}
