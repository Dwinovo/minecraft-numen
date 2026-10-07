package com.dwinovo.numen.pathing.search;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.ActionCosts;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threat;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Reach;
import com.dwinovo.numen.pathing.world.Sight;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertTrue(ring.estimate(1, 64, 0) > ring.estimate(2, 64, 0), "太近:往外更便宜");
        assertTrue(ring.estimate(9, 64, 0) > ring.estimate(8, 64, 0), "太远:往里更便宜");
        assertEquals(0, ring.estimate(4, 64, 0));
    }

    /** 离一列的距离只量水平;离一格的距离三个方向都量。 */
    @Test
    void theDistanceIsMeasuredOverTheCoordinatesTheCenterGives() {
        Goal ring = Goals.within(COLUMN, 3, 5);
        assertTrue(ring.contains(4, 90, 0, null));
        assertEquals(ring.estimate(4, 64, 0), ring.estimate(4, 90, 0));
        Goal sphere = Goals.within(Goals.at(CENTER), 3, 5);
        assertTrue(sphere.contains(4, 64, 0, null));
        assertFalse(sphere.contains(4, 90, 0, null));
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
        assertEquals(0, away.estimate(300, 64, 0));
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

    // ==================== 挖 ====================

    /**
     * 挖的估价只量"还差多远才够得着":站在够得着的带里(站得住就算到了)估价为零;从带外朝目标走,每近一格估价都严格变小,
     * 搜索因此一路朝它引。
     */
    @Test
    void diggingABlockEstimatesZeroWithinReachAndLessTheCloserFromOutside() {
        BlockPos target = new BlockPos(10, 64, 0);
        Goal reach = Goals.dig(target, SURVIVAL);
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

    /**
     * 挖几格里的任意一格:够得着其中一格就算到;停下的价钱里每少够着一格加一小份,全少了也不到多走一格——同样划算的站位里,
     * 一次够得着的越多越便宜。一格都够不着的站位不在目标里。
     */
    @Test
    void diggingSeveralCellsPrefersTheStandThatReachesMoreOfThem() {
        BlockPos a = new BlockPos(10, 64, 0);
        BlockPos b = new BlockPos(10, 64, 1);
        BlockPos far = new BlockPos(30, 64, 0);
        Goal dig = Goals.dig(free(a, b, far), SURVIVAL, Goals.Clearing.ANY);
        Stance standing = new Stance(Stance.Kind.GROUND, 64, 63);
        TestWorld world = new TestWorld().ground(63, Blocks.STONE.defaultBlockState())
                .set(a, Blocks.STONE.defaultBlockState()).set(b, Blocks.STONE.defaultBlockState())
                .set(far, Blocks.STONE.defaultBlockState());
        assertTrue(dig.contains(8, 64, 0, standing), "够得着 a 就算到");
        assertFalse(dig.contains(0, 64, 0, standing), "一格都够不着的不在目标里");
        double both = dig.arrival(world, 8, 64, 0, standing);
        double one = dig.arrival(world, 6, 64, -3, standing);
        assertTrue(Goals.dig(a, SURVIVAL).contains(6, 64, -3, standing) && !Goals.dig(b, SURVIVAL)
                .contains(6, 64, -3, standing), "这一处只够得着 a");
        assertTrue(both < one, "够得着两格的停下更便宜:" + both + " / " + one);
        assertTrue(one - both < ActionCosts.WALK_ONE_BLOCK, "差的不到多走一格:" + (one - both));
        assertThrows(IllegalArgumentException.class, () -> Goals.dig(List.of(), SURVIVAL, Goals.Clearing.ANY));
    }

    /**
     * 挖几格里的任意一格,各格挖起来的价钱不同:两处站位各只够得着一格、走到那儿一样贵,停下的价钱差的正是两格挖起来的价钱差——
     * 贵的那格(要问主人的)只在便宜的远出它那份价钱时才去。
     */
    @Test
    void diggingSeveralCellsCountsWhatEachCellCostsToDig() {
        BlockPos cheap = new BlockPos(10, 64, 0);
        BlockPos dear = new BlockPos(-10, 64, 0);
        Goal dig = Goals.dig(List.of(new Goals.DigTarget(cheap, 1), new Goals.DigTarget(dear, 50)), SURVIVAL,
                Goals.Clearing.ANY);
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
     * 挖的估价按分轴的价钱:要挖的那一格在正下方二十格,正上方往下每一格按落的价;横着走开,每一格涨一格疾跑的价——
     * 不因为离得深,横着走就几乎不涨。
     */
    @Test
    void diggingABlockFarBelowPricesEachAxisOnItsOwn() {
        BlockPos target = new BlockPos(0, 44, 0);
        Goal dig = Goals.dig(target, SURVIVAL);
        double eye = SURVIVAL.eyeHeight(Pose.STANDING);
        double above = 64 + eye - (target.getY() + 1);
        assertEquals((above - SURVIVAL.blockReach()) * ActionCosts.ESTIMATE_DOWN, dig.estimate(0, 64, 0), 1e-9);
        for (int x = 6; x < 12; x++) {
            assertEquals(ActionCosts.ESTIMATE_PER_BLOCK, dig.estimate(x + 1, 64, 0) - dig.estimate(x, 64, 0), 1e-9,
                    "x=" + x);
        }
    }

    /** 挖的终点:脚在一格里任何高度时够得着它的节点,全都在里面;离得够不着的不在。 */
    @Test
    void diggingEndCellsHoldEveryNodeThatReachesIt() {
        BlockPos target = new BlockPos(3, 70, -2);
        Goal dig = Goals.dig(target, SURVIVAL);
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

    /**
     * 挖一格时,站位与它之间每隔着一格硬遮挡就加一份到达价;软遮挡(高草)不加。
     */
    @Test
    void diggingChargesForEveryHardBlockerInTheWay() {
        BlockPos target = new BlockPos(4, 64, 0);
        Goal dig = Goals.dig(target, SURVIVAL);
        Stance standing = new Stance(Stance.Kind.GROUND, 64, 63);
        com.dwinovo.numen.pathing.TestWorld open = new TestWorld().floor(-4, -4, 8, 4, 63)
                .set(target, Blocks.IRON_ORE.defaultBlockState());
        assertEquals(0, dig.arrival(open, 1, 64, 0, standing));
        open.fill(3, 63, -4, 3, 70, 4, Blocks.STONE.defaultBlockState());
        assertEquals(com.dwinovo.numen.pathing.plan.ActionCosts.SIGHT_BLOCKER, dig.arrival(open, 1, 64, 0, standing),
                1e-9, "隔着一堵一格厚的墙");
        open.fill(2, 63, -4, 2, 70, 4, Blocks.STONE.defaultBlockState());
        assertEquals(2 * com.dwinovo.numen.pathing.plan.ActionCosts.SIGHT_BLOCKER, dig.arrival(open, 1, 64, 0, standing),
                1e-9, "两堵");
    }

    /**
     * 挖的一方清不掉的格挡在看得见它的每一面前面,停在那儿也办不成:到达价无穷。换成清得掉的格,照旧按硬遮挡定价;
     * 不说谁来挖的目标,挡着的格一律算清得掉。
     */
    @Test
    void diggingCannotStopWhereEveryFaceIsBehindWhatTheDiggerMayNotClear() {
        BlockPos target = new BlockPos(4, 64, 0);
        Goals.Clearing sparesStone = (view, pos) -> !view.getBlockState(pos).is(Blocks.STONE);
        Goal dig = Goals.dig(target, SURVIVAL, sparesStone);
        Stance standing = new Stance(Stance.Kind.GROUND, 64, 63);
        TestWorld world = new TestWorld().floor(-4, -4, 8, 4, 63)
                .set(target, Blocks.IRON_ORE.defaultBlockState())
                .fill(3, 63, -4, 3, 70, 4, Blocks.STONE.defaultBlockState());
        assertTrue(Double.isInfinite(dig.arrival(world, 1, 64, 0, standing)), "看得见的每一面都隔着清不掉的石头");
        assertEquals(ActionCosts.SIGHT_BLOCKER, Goals.dig(target, SURVIVAL).arrival(world, 1, 64, 0, standing), 1e-9,
                "没说谁来挖:照旧按一格硬遮挡定价");
        world.fill(3, 63, -4, 3, 70, 4, Blocks.DIRT.defaultBlockState());
        assertEquals(ActionCosts.SIGHT_BLOCKER, dig.arrival(world, 1, 64, 0, standing), 1e-9, "隔着清得掉的泥土");
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
        Goal ring = Goals.within(COLUMN, 3, 5);
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

    // ==================== 用 ====================

    /** 平地上一个熔炉:四个侧面与顶面敞开,底面贴着地板;站位都看得见它、都不占着它,离它都在交互距离内。 */
    @Test
    void usingABlockListsStandsThatSeeAnOpenFace() {
        BlockPos furnace = new BlockPos(0, 64, 0);
        TestWorld world = new TestWorld().floor(-8, -8, 8, 8, 63).set(furnace, Blocks.FURNACE.defaultBlockState());
        Goals.Use use = Goals.use(world, SURVIVAL, furnace);
        assertFalse(use.sealed());
        assertFalse(use.open().contains(Direction.DOWN));
        assertEquals(5, use.open().size());
        assertFalse(use.stands().isEmpty());
        Stance standing = new Stance(Stance.Kind.GROUND, 64, 63);
        assertTrue(use.contains(1, 64, 0, standing), "紧挨着东面");
        assertTrue(use.contains(0, 65, 0, new Stance(Stance.Kind.GROUND, 65, 64)), "站在它顶上,往下看得见顶面");
        assertFalse(use.contains(7, 64, 0, standing), "七格外够不着");
        for (long cell : use.stands().keySet()) {
            BlockPos at = BlockPos.of(cell);
            Stance there = Stance.at(world, SURVIVAL, at);
            Vec3 eye = Reach.eye(SURVIVAL, Pose.STANDING, at.getX(), there.feetY(), at.getZ());
            for (Direction face : use.stands().get(cell)) {
                assertNotNull(Sight.use(world, eye, SURVIVAL.blockReach(), furnace, face), at + " 看 " + face);
            }
        }
    }

    /** 隔着一堵墙、几何上够得着的地方不是站位:只在敞开的那一面前面。 */
    @Test
    void aStandBehindAWallIsNoStand() {
        BlockPos chest = new BlockPos(0, 64, 0);
        TestWorld world = new TestWorld().floor(-8, -8, 8, 8, 63).set(chest, Blocks.CHEST.defaultBlockState())
                .set(0, 64, -1, Blocks.STONE.defaultBlockState()).set(0, 64, 1, Blocks.STONE.defaultBlockState())
                .set(1, 64, 0, Blocks.STONE.defaultBlockState()).set(0, 65, 0, Blocks.GLASS.defaultBlockState())
                .fill(2, 64, -3, 2, 67, 3, Blocks.STONE.defaultBlockState());
        Goals.Use use = Goals.use(world, SURVIVAL, chest);
        assertEquals(List.of(Direction.WEST), use.open());
        assertTrue(Goals.dig(chest, SURVIVAL).contains(3, 64, 0, new Stance(Stance.Kind.GROUND, 64, 63)),
                "墙外那一格几何上够得着");
        assertFalse(use.contains(3, 64, 0, new Stance(Stance.Kind.GROUND, 64, 63)), "却看不见,不是站位");
        for (long cell : use.stands().keySet()) {
            assertTrue(BlockPos.of(cell).getX() < 0, "站位都在西面:" + BlockPos.of(cell));
            assertEquals(Set.of(Direction.WEST), use.stands().get(cell));
        }
    }

    /** 四面封死:没有一面敞开,也就没有站位。 */
    @Test
    void aBlockWalledInOnEverySideIsSealed() {
        BlockPos furnace = new BlockPos(0, 64, 0);
        TestWorld world = new TestWorld().fill(-1, 63, -1, 1, 65, 1, Blocks.STONE.defaultBlockState())
                .set(furnace, Blocks.FURNACE.defaultBlockState());
        Goals.Use use = Goals.use(world, SURVIVAL, furnace);
        assertTrue(use.sealed());
        assertTrue(use.stands().isEmpty());
    }

    /** 敞开的面前隔着高草:高草是软遮挡,那一面照样敞开,站位照样列出——用之前先清掉。 */
    @Test
    void softBlockersDoNotCloseAFace() {
        BlockPos chest = new BlockPos(0, 64, 0);
        TestWorld world = new TestWorld().floor(-8, -8, 8, 8, 63).set(chest, Blocks.CHEST.defaultBlockState())
                .set(0, 64, -1, Blocks.STONE.defaultBlockState()).set(0, 64, 1, Blocks.STONE.defaultBlockState())
                .set(1, 64, 0, Blocks.STONE.defaultBlockState()).set(0, 65, 0, Blocks.GLASS.defaultBlockState())
                .set(-1, 64, 0, Blocks.SHORT_GRASS.defaultBlockState());
        Goals.Use use = Goals.use(world, SURVIVAL, chest);
        assertEquals(List.of(Direction.WEST), use.open());
        assertFalse(use.stands().isEmpty());
    }

    @Test
    void usingAirIsRefused() {
        TestWorld world = new TestWorld().floor(-2, -2, 2, 2, 63);
        assertThrows(IllegalArgumentException.class, () -> Goals.use(world, SURVIVAL, new BlockPos(0, 64, 0)));
    }
}
