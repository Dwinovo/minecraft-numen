package com.dwinovo.numen.pathing.search;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threat;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;

import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目标的估价往哪边引、到达的边界在哪:贴脸在够得着的带里估价为零、从外面越近越便宜;靠近保留小数半径;环形站位太近往外、
 * 太远往里;离生物越近越贵、几只相加、半径越大越贵、每只在自己的半径上一样贵;几个同时成立取最大的估价。
 * 到达本身由 {@code SearchTest} 在搜索里验。
 */
class GoalsTest {

    private static final BlockPos CENTER = new BlockPos(0, 64, 0);

    @Test
    void aRingLeadsOutwardWhenTooCloseAndInwardWhenTooFar() {
        Goal ring = Goals.ring(CENTER, 3, 5);
        assertTrue(ring.estimate(1, 64, 0) > ring.estimate(2, 64, 0), "太近:往外更便宜");
        assertTrue(ring.estimate(9, 64, 0) > ring.estimate(8, 64, 0), "太远:往里更便宜");
        assertEquals(0, ring.estimate(4, 64, 0));
    }

    @Test
    void aRingIsHorizontalOnly() {
        Goal ring = Goals.ring(CENTER, 3, 5);
        assertTrue(ring.contains(4, 90, 0, null));
        assertEquals(ring.estimate(4, 64, 0), ring.estimate(4, 90, 0));
    }

    @Test
    void aRingWhoseInnerEdgeIsOutsideItsOuterEdgeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Goals.ring(CENTER, 5, 3));
    }

    @Test
    void closerToACreatureIsAlwaysDearer() {
        Goal away = Goals.awayFrom(List.of(new Threat(0.5, 64, 0.5, 3)));
        double previous = Double.POSITIVE_INFINITY;
        for (int x = 1; x <= 10; x++) {
            double here = away.estimate(x, 64, 0);
            assertTrue(here < previous, "x=" + x);
            previous = here;
        }
    }

    @Test
    void theDangerOfSeveralCreaturesAddsUp() {
        Threat left = new Threat(-3.5, 64, 0.5, 3);
        Threat right = new Threat(4.5, 64, 0.5, 3);
        double one = Goals.awayFrom(List.of(left)).estimate(0, 64, 0);
        double both = Goals.awayFrom(List.of(left, right)).estimate(0, 64, 0);
        assertTrue(both > one, "两只一左一右,直穿哪一只都不便宜");
    }

    @Test
    void aWiderDangerRadiusIsDearerAtTheSameDistance() {
        double small = Goals.awayFrom(List.of(new Threat(0.5, 64, 0.5, 2))).estimate(5, 64, 0);
        double wide = Goals.awayFrom(List.of(new Threat(0.5, 64, 0.5, 6))).estimate(5, 64, 0);
        assertTrue(wide > small);
    }

    @Test
    void getAwayMeansOutsideEveryRadiusAndHeightIsNoSafety() {
        Goal away = Goals.awayFrom(List.of(new Threat(0.5, 64, 0.5, 3), new Threat(10.5, 64, 0.5, 5)));
        assertFalse(away.contains(2, 64, 0, null), "在第一只的半径里");
        assertFalse(away.contains(7, 64, 0, null), "出了第一只的,还在第二只的半径里");
        assertTrue(away.contains(4, 64, -6, null));
        assertFalse(away.contains(1, 90, 0, null), "站高了也不算安全");
    }

    @Test
    void getAwayNeedsSomethingToGetAwayFromAndRadiiAreNotNegative() {
        assertThrows(IllegalArgumentException.class, () -> Goals.awayFrom(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Threat(0, 64, 0, -1));
    }

    // ==================== 贴脸 ====================

    /**
     * 贴脸的估价只量"还差多远才够得着":站在够得着的带里(站得住就算到了)估价为零;从带外朝目标走,每近一格估价都严格变小,
     * 搜索因此一路朝它引。
     */
    @Test
    void reachingABlockEstimatesZeroWithinReachAndLessTheCloserFromOutside() {
        BlockPos target = new BlockPos(10, 64, 0);
        Goal reach = Goals.reach(target, SURVIVAL);
        Stance standing = new Stance(Stance.Kind.GROUND, 64, 63);
        for (int x = 6; x <= 8; x++) {
            assertTrue(reach.contains(x, 64, 0, standing), "x=" + x + " 够得着");
            assertEquals(0, reach.estimate(x, 64, 0), "x=" + x + " 在够得着的带里");
        }
        double previous = Double.POSITIVE_INFINITY;
        for (int x = 0; x <= 5; x++) {
            assertFalse(reach.contains(x, 64, 0, standing), "x=" + x + " 够不着");
            double here = reach.estimate(x, 64, 0);
            assertTrue(here > 0 && here < previous, "x=" + x + ":" + here);
            previous = here;
        }
    }

    // ==================== 靠近 ====================

    /**
     * 靠近的半径带小数时照原样用,不取整:离中心 √6≈2.45 格的一格在 2.5 格以内,√8≈2.83 格的不在——半径取整成 2 时前者会被
     * 挡在外面,取整成 3 时后者会被放进来。距离照目标自己的量法,两格坐标差的平方和。
     */
    @Test
    void nearKeepsAFractionalRadius() {
        BlockPos center = new BlockPos(0, 64, 0);
        Goal near = Goals.near(center, 2.5);
        BlockPos inside = new BlockPos(2, 65, 1);
        BlockPos outside = new BlockPos(2, 66, 0);
        assertEquals(6, center.distSqr(inside));
        assertEquals(8, center.distSqr(outside));
        assertTrue(near.contains(inside.getX(), inside.getY(), inside.getZ(), null), "√6 ≈ 2.45 在 2.5 格以内");
        assertFalse(near.contains(outside.getX(), outside.getY(), outside.getZ(), null), "√8 ≈ 2.83 在 2.5 格以外");
    }

    // ==================== 远离生物 ====================

    /**
     * 每只生物的势只看"离它的距离是它危险半径的几倍":危险半径不同的几只,贴在各自半径上时估价一样,都等于走进它半径里一格
     * 的加价(成本模型按生物危险给的那一份);离开到两倍半径时也一样。站在半径上就算离开了。
     */
    @Test
    void everyCreatureWeighsTheSameAtTheEdgeOfItsOwnRadius() {
        Threat probe = new Threat(0.5, 64, 0.5, 2);
        CostModel model = CostModel.of(RouteSpec.defaults(), Fixtures.body(), TerrainPolicy.ALLOW_ALL, Materials.NONE,
                () -> List.of(probe));
        double oneCellInside = model.extra(Use.PASS, new BlockPos(1, 64, 0).asLong());
        assertTrue(oneCellInside > 0);
        for (int radius : new int[] {2, 3, 6}) {
            Goal away = Goals.awayFrom(List.of(new Threat(0.5, 64, 0.5, radius)));
            assertTrue(away.contains(radius, 64, 0, null), "半径 " + radius + ":站在半径上就算离开了");
            assertEquals(oneCellInside, away.estimate(radius, 64, 0), 1e-9, "半径 " + radius + " 的边上");
            assertEquals(oneCellInside / 4, away.estimate(2 * radius, 64, 0), 1e-9, "半径 " + radius + " 的两倍处");
        }
    }

    // ==================== 几个同时成立 ====================

    /**
     * 几个目标同时成立时估价取各自估价里最大的:离环带远时是环带那一份说了算,贴着生物时是远离那一份说了算,都取得到。
     */
    @Test
    void severalGoalsAtOnceEstimateTheLargestOfTheirEstimates() {
        Goal ring = Goals.ring(CENTER, 3, 5);
        Goal away = Goals.awayFrom(List.of(new Threat(20.5, 64, 0.5, 4)));
        Goal both = Goals.allOf(List.of(ring, away));
        boolean ringLeads = false;
        boolean awayLeads = false;
        for (int x = -6; x <= 24; x++) {
            double r = ring.estimate(x, 64, 0);
            double a = away.estimate(x, 64, 0);
            assertEquals(Math.max(r, a), both.estimate(x, 64, 0), 1e-9, "x=" + x);
            ringLeads |= r > a;
            awayLeads |= a > r;
        }
        assertTrue(ringLeads && awayLeads, "两个成员各有说了算的地方");
    }
}
