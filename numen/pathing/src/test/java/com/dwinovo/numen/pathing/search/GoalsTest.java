package com.dwinovo.numen.pathing.search;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.world.Kinematics;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.Threat;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import static com.dwinovo.numen.pathing.Fixtures.PACE;
import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 六种到达各自的判定与估价:位置坐标给几个比几个、身体怎么待着都算;距离范围只按一种量法,太近往外、太远往里、保留小数半径;
 * 站上去只认托着脚的那一块;用一格方块只认候选站位——敞开的面前、看得见、不占着它;挖在够得着的带里估价为零、从外面越近
 * 越便宜、按分轴的价钱估、终点涵盖每一个够得着的节点;离生物越近越贵、几只相加、半径越大越贵、每只在自己的半径上一样贵;几个同时成立取最大的估价。
 * 搜索里走到哪儿由 {@code SearchTest} 验。
 */
class GoalsTest {

    private static final BlockPos CENTER = new BlockPos(0, 64, 0);

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static final Goals.Position COLUMN = Goals.column(0, 0);

    // ==================== 位置 ====================

    /** 坐标给几个比几个:一格三个都比,一列只比 x、z,一个高度只比 y。 */
    @Test
    void aPositionComparesOnlyTheCoordinatesGiven() {
        assertTrue(Goals.at(CENTER).contains(0, 64, 0, null));
        assertFalse(Goals.at(CENTER).contains(0, 65, 0, null));
        assertTrue(COLUMN.contains(0, 90, 0, null));
        assertFalse(COLUMN.contains(1, 64, 0, null));
        assertTrue(Goals.level(64).contains(30, 64, -7, null));
        assertFalse(Goals.level(64).contains(0, 63, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new Goals.Position(1, null, null), "x 与 z 要一起给");
    }

    /** 到了时脚所在节点的高度范围:某一格、某一高度是那一层;只给列、离某处多远、远离这些没有范围;几个取其一合起来。 */
    @Test
    void goalsSayWhichLevelsTheyCanBeReachedAt() {
        assertEquals(new Goal.Heights(64, 64), Goals.at(CENTER).heights());
        assertEquals(new Goal.Heights(70, 70), Goals.level(70).heights());
        assertNull(COLUMN.heights());
        assertNull(Goals.within(COLUMN, 3, 5).heights());
        assertNull(Goals.awayFrom(List.of(new Threat(0.5, 64, 0.5, 3))).heights());
        assertEquals(new Goal.Heights(60, 70), Goals.anyOf(List.of(Goals.at(new BlockPos(0, 60, 0)), Goals.level(70))).heights());
        assertNull(Goals.anyOf(List.of(Goals.at(CENTER), COLUMN)).heights(), "有一个没有范围,合起来也没有");
        assertEquals(new Goal.Heights(64, 64), Goals.allOf(List.of(COLUMN, Goals.at(CENTER))).heights(), "每一个都要到:取有范围的那个");
    }

    /** 某一格:站着、挂在梯子上、浮在水里,脚在那一格就算到了。 */
    @Test
    void aCellCountsHowEverTheBodyIsHeldThere() {
        Goal cell = Goals.at(CENTER);
        for (Stance.Kind kind : Stance.Kind.values()) {
            Stance stance = new Stance(kind, 64, kind == Stance.Kind.GROUND ? 63 : Integer.MIN_VALUE);
            assertTrue(cell.contains(0, 64, 0, stance), kind.name());
        }
    }

    // ==================== 距离范围 ====================

    @Test
    void aRangeLeadsOutwardWhenTooCloseAndInwardWhenTooFar() {
        Goal ring = Goals.within(COLUMN, 3, 5);
        assertTrue(ring.estimate(1, 64, 0, PACE) > ring.estimate(2, 64, 0, PACE), "太近:往外更便宜");
        assertTrue(ring.estimate(9, 64, 0, PACE) > ring.estimate(8, 64, 0, PACE), "太远:往里更便宜");
        assertEquals(0, ring.estimate(4, 64, 0, PACE));
    }

    /** 离一列的距离只量水平;离一格的距离三个方向都量。 */
    @Test
    void theDistanceIsMeasuredOverTheCoordinatesTheCenterGives() {
        Goal ring = Goals.within(COLUMN, 3, 5);
        assertTrue(ring.contains(4, 90, 0, null));
        assertEquals(ring.estimate(4, 64, 0, PACE), ring.estimate(4, 90, 0, PACE));
        Goal sphere = Goals.within(Goals.at(CENTER), 3, 5);
        assertTrue(sphere.contains(4, 64, 0, null));
        assertFalse(sphere.contains(4, 90, 0, null));
    }

    /** 离一格的距离范围:终点格就是范围里的每一格,交出的高度范围盖得住它们;没有边或枚举不动的没有。 */
    @Test
    void aRangeAroundACellHandsOutItsEndCellsAndHeights() {
        Goal ring = Goals.within(Goals.at(CENTER), 1, 2.5);
        var ends = ring.endCells();
        int count = 0;
        for (int x = -4; x <= 4; x++) {
            for (int y = 60; y <= 68; y++) {
                for (int z = -4; z <= 4; z++) {
                    boolean in = ring.contains(x, y, z, null);
                    assertEquals(in, ends.contains(BlockPos.asLong(x, y, z)), x + "," + y + "," + z);
                    count += in ? 1 : 0;
                }
            }
        }
        assertEquals(count, ends.size());
        Goal.Heights heights = ring.heights();
        for (long cell : ends) {
            assertTrue(heights.lo() <= BlockPos.getY(cell) && BlockPos.getY(cell) <= heights.hi());
        }
        assertNull(Goals.within(Goals.at(CENTER), 0, Double.POSITIVE_INFINITY).endCells());
        assertNull(Goals.within(Goals.at(CENTER), 0, Double.POSITIVE_INFINITY).heights());
        assertNull(Goals.within(Goals.at(CENTER), 0, 200).endCells());
        assertNull(Goals.within(COLUMN, 0, 3).endCells());
        assertEquals(new Goal.Heights(61, 67), Goals.within(Goals.level(64), 0, 3).heights());
    }

    @Test
    void aRangeWhoseMinimumExceedsItsMaximumIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Goals.within(COLUMN, 5, 3));
        assertThrows(IllegalArgumentException.class, () -> Goals.within(COLUMN, -1, 3));
    }

    /** 没有上限的范围:离开最小距离以内哪儿都行。 */
    @Test
    void anOpenRangeOnlyAsksToLeaveTheInnerDistance() {
        Goal away = Goals.within(COLUMN, 1, Double.POSITIVE_INFINITY);
        assertFalse(away.contains(0, 64, 0, null));
        assertTrue(away.contains(1, 64, 0, null));
        assertTrue(away.contains(300, 64, 0, null));
        assertEquals(0, away.estimate(300, 64, 0, PACE));
    }

    @Test
    void closerToACreatureIsAlwaysDearer() {
        Goal away = Goals.awayFrom(List.of(new Threat(0.5, 64, 0.5, 3)));
        double previous = Double.POSITIVE_INFINITY;
        for (int x = 1; x <= 10; x++) {
            double here = away.estimate(x, 64, 0, PACE);
            assertTrue(here < previous, "x=" + x);
            previous = here;
        }
    }

    @Test
    void theDangerOfSeveralCreaturesAddsUp() {
        Threat left = new Threat(-3.5, 64, 0.5, 3);
        Threat right = new Threat(4.5, 64, 0.5, 3);
        double one = Goals.awayFrom(List.of(left)).estimate(0, 64, 0, PACE);
        double both = Goals.awayFrom(List.of(left, right)).estimate(0, 64, 0, PACE);
        assertTrue(both > one, "两只一左一右,直穿哪一只都不便宜");
    }

    @Test
    void aWiderDangerRadiusIsDearerAtTheSameDistance() {
        double small = Goals.awayFrom(List.of(new Threat(0.5, 64, 0.5, 2))).estimate(5, 64, 0, PACE);
        double wide = Goals.awayFrom(List.of(new Threat(0.5, 64, 0.5, 6))).estimate(5, 64, 0, PACE);
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

    // ==================== 够着一格 ====================

    /**
     * 挖的估价只量"还差多远才够得着":站在够得着的带里(站得住就算到了)估价为零;从带外朝目标走,每近一格估价都严格变小,
     * 搜索因此一路朝它引。
     */
    @Test
    void diggingABlockEstimatesZeroWithinReachAndLessTheCloserFromOutside() {
        BlockPos target = new BlockPos(10, 64, 0);
        Goal reach = Goals.reach(target, SURVIVAL);
        Stance standing = new Stance(Stance.Kind.GROUND, 64, 63);
        for (int x = 6; x <= 8; x++) {
            assertTrue(reach.contains(x, 64, 0, standing), "x=" + x + " 够得着");
            assertEquals(0, reach.estimate(x, 64, 0, PACE), "x=" + x + " 在够得着的带里");
        }
        double previous = Double.POSITIVE_INFINITY;
        for (int x = 0; x <= 5; x++) {
            assertFalse(reach.contains(x, 64, 0, standing), "x=" + x + " 够不着");
            double here = reach.estimate(x, 64, 0, PACE);
            // 底价只量水平:离够得着的带只差眼睛高出那一点竖直距离的那一格,水平上已经够了,底价是零
            assertTrue((x < 5 ? here > 0 : here >= 0) && here < previous, "x=" + x + ":" + here);
            previous = here;
        }
    }

    /** 攀着的站位够得着也不算工位:导航收场松开键之后没人按着潜行,身子顺着梯子滑下去;站在地上同一处才算。 */
    @Test
    void aClimbingStanceIsNotAWorkingPosition() {
        Goal reach = Goals.reach(new BlockPos(10, 64, 0), SURVIVAL);
        assertTrue(reach.contains(7, 64, 0, new Stance(Stance.Kind.GROUND, 64, 63)), "站在地上够得着");
        assertFalse(reach.contains(7, 64, 0, new Stance(Stance.Kind.CLIMBING, 64, Integer.MIN_VALUE)), "攀着不算");
    }

    /**
     * 够着一格只看距离:隔着一堵墙、够得着的地方照样算到,到达价不因隔着什么而涨。路上不挖要够的那一格、不往里放东西。
     */
    @Test
    void reachingABlockDoesNotAskWhatStandsInBetween() {
        BlockPos chest = new BlockPos(0, 64, 0);
        TestWorld world = new TestWorld().floor(-8, -8, 8, 8, 63).set(chest, Blocks.CHEST.defaultBlockState())
                .fill(2, 64, -3, 2, 67, 3, Blocks.STONE.defaultBlockState());
        Goal reach = Goals.reach(chest, SURVIVAL);
        Stance standing = new Stance(Stance.Kind.GROUND, 64, 63);
        assertTrue(reach.contains(3, 64, 0, standing), "墙外那一格够得着");
        assertEquals(0, reach.arrival(world, 3, 64, 0, standing));
        assertFalse(reach.contains(7, 64, 0, standing), "七格外够不着");
        assertFalse(reach.contains(0, 64, 0, standing), "身体占着它的那一格不算");
        com.dwinovo.numen.pathing.spec.PositionCosts keep = reach.protection();
        assertTrue(keep.forbids(com.dwinovo.numen.pathing.spec.PositionCosts.Use.DIG, chest.asLong()));
        assertTrue(keep.forbids(com.dwinovo.numen.pathing.spec.PositionCosts.Use.PLACE, chest.asLong()));
    }

    /**
     * 挖几格里的任意一格:够得着其中一格就算到;停下的价钱里每少够着一格加一小份,全少了也不到多走一格——同样划算的站位里,
     * 一次够得着的越多越便宜。一格都够不着的站位不在目标里。
     */
    @Test
    void diggingSeveralCellsPrefersTheStandThatReachesMoreOfThem() {
        BlockPos a = new BlockPos(10, 64, 0);
        BlockPos b = new BlockPos(10, 64, 1);
        BlockPos far = new BlockPos(30, 64, 0);
        Goal dig = Goals.dig(free(a, b, far), SURVIVAL);
        Stance standing = new Stance(Stance.Kind.GROUND, 64, 63);
        TestWorld world = new TestWorld().ground(63, Blocks.STONE.defaultBlockState())
                .set(a, Blocks.STONE.defaultBlockState()).set(b, Blocks.STONE.defaultBlockState())
                .set(far, Blocks.STONE.defaultBlockState());
        assertTrue(dig.contains(8, 64, 0, standing), "够得着 a 就算到");
        assertFalse(dig.contains(0, 64, 0, standing), "一格都够不着的不在目标里");
        double both = dig.arrival(world, 8, 64, 0, standing);
        double one = dig.arrival(world, 6, 64, -3, standing);
        assertTrue(Goals.reach(a, SURVIVAL).contains(6, 64, -3, standing) && !Goals.reach(b, SURVIVAL)
                .contains(6, 64, -3, standing), "这一处只够得着 a");
        assertTrue(both < one, "够得着两格的停下更便宜:" + both + " / " + one);
        assertTrue(one - both < Kinematics.walkTicksPerBlock(SURVIVAL), "差的不到多走一格:" + (one - both));
        assertThrows(IllegalArgumentException.class, () -> Goals.dig(List.of(), SURVIVAL));
    }

    /**
     * 挖几格里的任意一格,各格挖起来的价钱不同:两处站位各只够得着一格、走到那儿一样贵,停下的价钱差的正是两格挖起来的价钱差——
     * 贵的那格(要问主人的)只在便宜的远出它那份价钱时才去。
     */
    @Test
    void diggingSeveralCellsCountsWhatEachCellCostsToDig() {
        BlockPos cheap = new BlockPos(10, 64, 0);
        BlockPos dear = new BlockPos(-10, 64, 0);
        Goal dig = Goals.dig(List.of(new Goals.DigTarget(cheap, 1), new Goals.DigTarget(dear, 50)), SURVIVAL);
        Stance standing = new Stance(Stance.Kind.GROUND, 64, 63);
        TestWorld world = new TestWorld().ground(63, Blocks.STONE.defaultBlockState())
                .set(cheap, Blocks.STONE.defaultBlockState()).set(dear, Blocks.STONE.defaultBlockState());
        double nearCheap = dig.arrival(world, 8, 64, 0, standing);
        double nearDear = dig.arrival(world, -8, 64, 0, standing);
        assertEquals(49, nearDear - nearCheap, 1e-9, "两处只差两格挖起来的价钱");
    }

    /** 挖起来不另收钱的几格。 */
    private static List<Goals.DigTarget> free(BlockPos... cells) {
        return java.util.Arrays.stream(cells).map(c -> new Goals.DigTarget(c, 0)).toList();
    }

    /**
     * 挖的水平底价只量水平:要挖的那一格在正下方二十格,正上方水平上已经够得着,不付;横着走开,超出交互距离的每一格涨一格疾跑的价。
     * 竖直方向的二十层不在这里,由目标的高度范围算({@link Goal#heights()})。
     */
    @Test
    void diggingABlockFarBelowIsPricedOnlyHorizontallyHere() {
        BlockPos target = new BlockPos(0, 44, 0);
        Goal dig = Goals.reach(target, SURVIVAL);
        assertEquals(0, dig.estimate(0, 64, 0, PACE), 1e-9);
        for (int x = 10; x < 16; x++) {
            assertEquals(PACE.sprint(), dig.estimate(x + 1, 64, 0, PACE) - dig.estimate(x, 64, 0, PACE), 1e-9,
                    "x=" + x);
        }
        Goal.Heights heights = dig.heights();
        assertTrue(heights.lo() < target.getY() && heights.hi() > target.getY(), "脚在目标上下几层都够得着它");
    }

    /** 挖的终点:脚在一格里任何高度时够得着它的节点,全都在里面;离得够不着的不在。 */
    @Test
    void diggingEndCellsHoldEveryNodeThatReachesIt() {
        BlockPos target = new BlockPos(3, 70, -2);
        Goal dig = Goals.reach(target, SURVIVAL);
        var ends = dig.endCells();
        int span = 10;
        for (int x = -span; x <= span; x++) {
            for (int y = -span; y <= span; y++) {
                for (int z = -span; z <= span; z++) {
                    BlockPos node = target.offset(x, y, z);
                    boolean reaches = false;
                    for (double lift : new double[] {0, 0.5, 0.9375}) {
                        Stance stance = new Stance(Stance.Kind.GROUND, node.getY() + lift, node.getY() - 1);
                        reaches |= dig.contains(node.getX(), node.getY(), node.getZ(), stance);
                    }
                    if (reaches) {
                        assertTrue(ends.contains(node.asLong()), "够得着却不在终点里:" + node);
                    }
                    if (Math.abs(x) > 5 || y < -8 || y > 4) {
                        assertFalse(ends.contains(node.asLong()), "够不着却在终点里:" + node);
                    }
                }
            }
        }
    }

    /** 够着一格、挖几格里的一格交出的高度范围,就是它们终点格里最低与最高的一层。 */
    @Test
    void theHeightsOfADigAreTheLowestAndHighestLevelOfItsEndCells() {
        List<Goal> goals = List.of(Goals.reach(new BlockPos(3, 70, -2), SURVIVAL),
                Goals.dig(List.of(new Goals.DigTarget(new BlockPos(0, 40, 0), 1), new Goals.DigTarget(new BlockPos(6, 47, 3), 2)),
                        SURVIVAL));
        for (Goal goal : goals) {
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            for (long cell : goal.endCells()) {
                lo = Math.min(lo, BlockPos.getY(cell));
                hi = Math.max(hi, BlockPos.getY(cell));
            }
            assertEquals(new Goal.Heights(lo, hi), goal.heights(), goal.toString());
        }
    }

    // ==================== 距离范围的半径 ====================

    /**
     * 距离范围的半径带小数时照原样用,不取整:离中心 √6≈2.45 格的一格在 2.5 格以内,√8≈2.83 格的不在——半径取整成 2 时前者会被
     * 挡在外面,取整成 3 时后者会被放进来。距离只有一种量法,两格坐标差的平方和。
     */
    @Test
    void aRangeKeepsAFractionalRadius() {
        BlockPos center = new BlockPos(0, 64, 0);
        Goal near = Goals.within(Goals.at(center), 0, 2.5);
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
        CostModel model = CostModel.of(RouteSpec.defaults(), Fixtures.body(), Fixtures.ALLOW_ALL, Fixtures.NO_MATERIALS,
                () -> List.of(probe));
        double oneCellInside = model.extra(Use.PASS, new BlockPos(1, 64, 0).asLong());
        assertTrue(oneCellInside > 0);
        for (int radius : new int[] {2, 3, 6}) {
            Goal away = Goals.awayFrom(List.of(new Threat(0.5, 64, 0.5, radius)));
            assertTrue(away.contains(radius, 64, 0, null), "半径 " + radius + ":站在半径上就算离开了");
            assertEquals(oneCellInside, away.estimate(radius, 64, 0, PACE), 1e-9, "半径 " + radius + " 的边上");
            assertEquals(oneCellInside / 4, away.estimate(2 * radius, 64, 0, PACE), 1e-9, "半径 " + radius + " 的两倍处");
        }
    }

    // ==================== 几个同时成立 ====================

    /**
     * 几个目标同时成立时估价取各自估价里最大的:离环带远时是环带那一份说了算,贴着生物时是远离那一份说了算,都取得到。
     */
    @Test
    void severalGoalsAtOnceEstimateTheLargestOfTheirEstimates() {
        Goal ring = Goals.within(COLUMN, 3, 5);
        Goal away = Goals.awayFrom(List.of(new Threat(20.5, 64, 0.5, 4)));
        Goal both = Goals.allOf(List.of(ring, away));
        boolean ringLeads = false;
        boolean awayLeads = false;
        for (int x = -6; x <= 24; x++) {
            double r = ring.estimate(x, 64, 0, PACE);
            double a = away.estimate(x, 64, 0, PACE);
            assertEquals(Math.max(r, a), both.estimate(x, 64, 0, PACE), 1e-9, "x=" + x);
            ringLeads |= r > a;
            awayLeads |= a > r;
        }
        assertTrue(ringLeads && awayLeads, "两个成员各有说了算的地方");
    }
    // ==================== 站上一块时脚在哪一格 ====================

    /** 此刻站不站得上一块:整块站在上面一格,下半砖站在它自己那一格;空气、头顶压着东西的站不上。 */
    @Test
    void standingOnFindsTheNodeAboveAFullBlockOrInsideASlab() {
        TestWorld world = new TestWorld().floor(-4, -4, 8, 4, 63)
                .set(1, 64, 0, Blocks.STONE.defaultBlockState())
                .set(2, 64, 0, Blocks.STONE_SLAB.defaultBlockState())
                .set(3, 64, 0, Blocks.STONE.defaultBlockState()).set(3, 66, 0, Blocks.STONE.defaultBlockState());
        assertEquals(new BlockPos(1, 65, 0), Goals.standingOn(world, SURVIVAL, new BlockPos(1, 64, 0)));
        assertEquals(new BlockPos(2, 64, 0), Goals.standingOn(world, SURVIVAL, new BlockPos(2, 64, 0)));
        assertNull(Goals.standingOn(world, SURVIVAL, new BlockPos(1, 66, 0)), "空气上站不上");
        assertNull(Goals.standingOn(world, SURVIVAL, new BlockPos(3, 64, 0)), "头顶一格就压着石头");
    }
}
