package com.dwinovo.numen.pathing.drive;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 空中折线:拉直只在扫过去确实通的地方去掉中间的点;弧长认身体走到了哪,末尾之外按末段方向延伸。 */
class AirwayTest {

    private static final Airway.Sweep OPEN = (from, to) -> null;

    /** 一个格子锯齿的阶梯,没有障碍:拉成一条直线,只剩头尾两个点。 */
    @Test
    void aStaircaseInOpenAirBecomesOneStraightLine() {
        List<Vec3> steps = List.of(new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(1, 0, 1), new Vec3(2, 0, 1), new Vec3(2, 0, 2),
                new Vec3(3, 0, 2), new Vec3(3, 0, 3));
        Airway way = Airway.straighten(steps, null, OPEN);
        assertEquals(Math.sqrt(18), way.length(), 1.0E-9);
        assertEquals(new Vec3(3, 0, 3), way.end());
    }

    /** 中间有堵墙:直线被挡住的地方保留拐点,绕过去的点不被去掉。 */
    @Test
    void aLineThroughAWallKeepsTheCorner() {
        Airway.Sweep wall = (from, to) -> from.x < 2 && to.x > 2 && Math.abs(to.z - from.z) < 0.5 ? new BlockPos(2, 0, 0) : null;
        List<Vec3> around = List.of(new Vec3(0, 0, 0), new Vec3(1, 0, 3), new Vec3(3, 0, 3), new Vec3(4, 0, 0));
        Airway way = Airway.straighten(around, null, wall);
        assertNotNull(way);
        assertEquals(new Vec3(4, 0, 0), way.end());
        // 直线 (0,0,0)→(4,0,0) 被挡,要保留拐点:总长比直线长
        assertEquals(true, way.length() > 4.5);
    }

    /** 弧长:身体投在折线上,往回退不超过两格;末尾之外按末段方向延伸,所以滑过了头弧长超过总长。 */
    @Test
    void progressIsMeasuredAlongTheLineAndExtendsPastTheEnd() {
        Airway way = Airway.through(List.of(new Vec3(0, 0, 0), new Vec3(0, 0, 10)), null);
        assertEquals(4, way.project(new Vec3(0.5, 0, 4), 0), 1.0E-9);
        assertEquals(12, way.project(new Vec3(0, 0, 12), 0), 1.0E-9);
        assertEquals(10, way.length(), 1.0E-9);
        assertEquals(0.5, way.offset(new Vec3(0.5, 0, 4), 4), 1.0E-9);
    }

    /** 盘旋的线:绕着圆心转,每个点都在圆上,转几圈就是几圈的周长,从身体这一点出发。 */
    @Test
    void aCircleLoopsAroundItsCenterFromTheBody() {
        Vec3 center = new Vec3(10, 20, 0);
        Airway way = Airway.circle(new Vec3(0, 20, 0), center, 10, 20, 2);
        assertEquals(new Vec3(0, 20, 0), way.start());
        Vec3 onCircle = way.at(30);
        assertEquals(10, Math.hypot(onCircle.x - center.x, onCircle.z - center.z), 0.6);
        assertTrue(way.length() > 2 * 2 * Math.PI * 10 * 0.95, "两圈的周长:" + way.length());
    }
}
