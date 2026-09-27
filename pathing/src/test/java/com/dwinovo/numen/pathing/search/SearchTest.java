package com.dwinovo.numen.pathing.search;

import java.util.List;
import java.util.Optional;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threat;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.spec.RouteSpec.Alter;
import com.dwinovo.numen.pathing.world.Reach;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.pathing.Fixtures.natural;
import static com.dwinovo.numen.pathing.Fixtures.search;
import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 搜索:到达判定、停下的原因、改地形与许可、候选路线、改动预算、生物危险、目标格保护。地板在 {@code Y - 1}。 */
class SearchTest {

    private static final int Y = 64;
    private static final BlockPos START = new BlockPos(0, Y, 0);
    private static BlockState STONE;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
        STONE = Blocks.STONE.defaultBlockState();
    }

    private static CostModel defaults() {
        return Fixtures.model(RouteSpec.defaults());
    }

    private static TestWorld field() {
        return new TestWorld().floor(-4, -12, 24, 12, Y - 1);
    }

    /** 一条从 x=-1 到 x=12、宽一格(z=0)、高两格的走廊,四周是挖不动的基岩:要过去只能走走廊。 */
    private static TestWorld corridor() {
        TestWorld world = new TestWorld().fill(-2, Y - 1, -1, 13, Y + 2, 1, Blocks.BEDROCK.defaultBlockState());
        return world.fill(-1, Y, 0, 12, Y + 1, 0, Blocks.AIR.defaultBlockState());
    }

    private static boolean digs(Route route, BlockPos cell) {
        return route.edits().stream().anyMatch(e -> e instanceof Edit.Dig && e.pos().equals(cell));
    }

    // ==================== 停下的原因 ====================

    @Test
    void aClearWayArrivesExactlyAtTheGoal() {
        BlockPos goal = new BlockPos(15, Y, 3);
        SearchResult result = search(field(), defaults(), START, Goals.at(goal));
        assertTrue(result.arrived());
        assertEquals(goal, result.route().end());
    }

    @Test
    void aSealedRoomIsSearchedOutAndThatIsToldApartFromRunningOutOfBudget() {
        TestWorld room = field().fill(-2, Y, -2, 2, Y + 2, 2, STONE).fill(-1, Y, -1, 1, Y + 1, 1, Blocks.AIR.defaultBlockState());
        SearchResult sealed = search(room, defaults(), START, Goals.at(new BlockPos(10, Y, 0)));
        assertEquals(SearchResult.Stop.EXHAUSTED, sealed.stop());
        assertNull(sealed.route(), "原地打转的半截路不交");

        SearchResult far = search(field(), defaults(), START, Goals.at(new BlockPos(24, Y, 12)), 10);
        assertEquals(SearchResult.Stop.BUDGET, far.stop(), "预算用完只是没搜完,不说成没路");
    }

    @Test
    void theSearchStopsAtTheEdgeOfTheLoadedChunks() {
        TestWorld world = new TestWorld().floor(0, 0, 40, 3, Y - 1).loadedWithin(0);
        SearchResult result = search(world, defaults(), new BlockPos(1, Y, 1), Goals.at(new BlockPos(40, Y, 1)));
        assertEquals(SearchResult.Stop.UNLOADED, result.stop());
        assertNotNull(result.route(), "朝目标推进到边上的半程路线照样交出");
        assertEquals(15, result.route().end().getX(), "停在加载区块的边上");
    }

    @Test
    void aLongTunnelThatRunsOutOfBudgetStillHandsOverAPartialRouteTowardTheGoal() {
        // 整片石头里只空着身体站的那两格,空手挖:每挖一格的价钱比估价里走一格贵几十倍
        TestWorld rock = new TestWorld().fill(-4, Y - 6, -8, 40, Y + 8, 8, STONE)
                .fill(0, Y, 0, 0, Y + 1, 0, Blocks.AIR.defaultBlockState());
        SearchResult result = search(rock, Fixtures.model(natural()), START, Goals.at(new BlockPos(30, Y, 0)), 2000);
        assertEquals(SearchResult.Stop.BUDGET, result.stop());
        assertNotNull(result.route(), "挖隧道的长路搜不到头,也要交出朝目标挖过去的半程路线");
        assertTrue(result.route().end().getX() > AStar.MIN_PARTIAL, "半程路线朝目标推进:" + result.route().end());
    }

    @Test
    void aBodyThatCannotStandAtTheStartIsStranded() {
        SearchResult result = search(new TestWorld(), defaults(), START, Goals.at(new BlockPos(5, Y, 0)));
        assertEquals(SearchResult.Stop.STRANDED, result.stop());
    }

    // ==================== 改地形与许可 ====================

    @Test
    void aRouteThatMayNotAlterTerrainChangesNothing() {
        TestWorld world = field().fill(5, Y, -6, 5, Y + 1, 6, STONE);
        SearchResult result = search(world, defaults(), START, Goals.at(new BlockPos(10, Y, 0)));
        assertTrue(result.arrived(), "绕过去");
        assertTrue(result.route().edits().stream().noneMatch(Edit::alters));
    }

    @Test
    void aWallAcrossTheOnlyWayIsCrossedOnlyWhenTheSpecMayAlterTerrain() {
        TestWorld world = corridor().fill(5, Y, 0, 5, Y + 1, 0, Blocks.DIRT.defaultBlockState());
        Goal goal = Goals.at(new BlockPos(10, Y, 0));
        assertEquals(SearchResult.Stop.EXHAUSTED, search(world, defaults(), START, goal).stop());
        SearchResult dug = search(world, Fixtures.model(natural()), START, goal);
        assertTrue(dug.arrived());
        assertEquals(2, dug.route().alterations());
    }

    @Test
    void cellsTheTerrainPolicyDeniesStayOutOfTheRoute() {
        // 两条平行的走廊 z=0 与 z=4,中间隔着墙;两条都被 x=5 的墙截断,z=0 那道墙许可拒绝
        TestWorld world = new TestWorld().floor(-1, -1, 11, 5, Y - 1);
        world.fill(-1, Y, -1, 11, Y + 1, -1, STONE).fill(-1, Y, 5, 11, Y + 1, 5, STONE);
        world.fill(2, Y, 1, 8, Y + 1, 3, STONE);
        world.fill(5, Y, 0, 5, Y + 1, 0, Blocks.DIRT.defaultBlockState()).fill(5, Y, 4, 5, Y + 1, 4, Blocks.DIRT.defaultBlockState());
        world.fill(-1, Y + 2, -1, 11, Y + 2, 5, STONE);
        BlockPos denied = new BlockPos(5, Y, 0);
        TerrainPolicy policy = (change, pos, state) -> pos.getX() == 5 && pos.getZ() == 0 ? Permit.deny("玩家放的") : Permit.ALLOW;
        CostModel model = CostModel.of(natural(), Fixtures.body(), policy, Materials.NONE, Threats.NONE);
        SearchResult result = search(world, model, START, Goals.at(new BlockPos(10, Y, 0)));
        assertTrue(result.arrived());
        assertFalse(digs(result.route(), denied) || digs(result.route(), denied.above()), "许可拒绝的格不进路线");
        assertTrue(digs(result.route(), new BlockPos(5, Y, 4)), "改走另一条走廊");
    }

    @Test
    void cellsThatNeedConsentEnterTheRouteOnlyUnderAny() {
        TestWorld world = corridor().fill(5, Y, 0, 5, Y + 1, 0, Blocks.DIRT.defaultBlockState());
        TerrainPolicy policy = (change, pos, state) -> pos.getX() == 5 ? Permit.ask("要主人点头") : Permit.ALLOW;
        Goal goal = Goals.at(new BlockPos(10, Y, 0));
        CostModel naturalModel = CostModel.of(natural(), Fixtures.body(), policy, Materials.NONE, Threats.NONE);
        assertEquals(SearchResult.Stop.EXHAUSTED, search(world, naturalModel, START, goal).stop());
        CostModel anyModel = naturalModel.withSpec(RouteSpec.defaults().edit().alter(Alter.ANY).build());
        SearchResult result = search(world, anyModel, START, goal);
        assertTrue(result.arrived());
        assertTrue(result.route().edits().stream().filter(Edit::alters)
                .allMatch(e -> ((Edit.Dig) e).permit() instanceof Permit.Ask), "账单里带着要问的凭据");
    }

    @Test
    void withoutBlocksNoBridgeIsPlanned() {
        TestWorld world = new TestWorld().floor(-2, -2, 4, 2, Y - 1).floor(6, -2, 12, 2, Y - 1);
        Goal goal = Goals.at(new BlockPos(10, Y, 0));
        assertEquals(SearchResult.Stop.EXHAUSTED, search(world, Fixtures.model(natural()), START, goal).stop());
        SearchResult bridged = search(world, Fixtures.withCobble(natural()), START, goal);
        assertTrue(bridged.arrived());
        assertTrue(bridged.route().edits().stream().anyMatch(e -> e instanceof Edit.Place && e.pos().getX() == 5));
    }

    // ==================== 改动预算 ====================

    @Test
    void theAlterBudgetIsEnforcedWhileSearching() {
        // 走廊被两道各两格高的墙截断:过去要挖四格
        TestWorld world = corridor().fill(3, Y, 0, 3, Y + 1, 0, Blocks.DIRT.defaultBlockState())
                .fill(7, Y, 0, 7, Y + 1, 0, Blocks.DIRT.defaultBlockState());
        Goal goal = Goals.at(new BlockPos(10, Y, 0));
        CostModel three = Fixtures.model(natural().edit().alterBudget(3).build());
        assertEquals(SearchResult.Stop.EXHAUSTED, search(world, three, START, goal).stop(), "预算三格不够");
        SearchResult four = search(world, Fixtures.model(natural().edit().alterBudget(4).build()), START, goal);
        assertTrue(four.arrived());
        assertEquals(4, four.route().alterations());
    }

    @Test
    void aTightBudgetPrefersTheDetourOverDigging() {
        // 直走要挖穿一道土墙,绕过去不挖
        TestWorld world = field().fill(5, Y, -3, 5, Y + 1, 3, Blocks.DIRT.defaultBlockState())
                .fill(4, Y, 3, 4, Y + 1, 3, STONE).fill(4, Y, -3, 4, Y + 1, -3, STONE);
        Goal goal = Goals.at(new BlockPos(8, Y, 0));
        SearchResult none = search(world, Fixtures.model(natural().edit().alterBudget(0).build()), START, goal);
        assertTrue(none.arrived());
        assertEquals(0, none.route().alterations());
    }

    // ==================== 目标族 ====================

    @Test
    void reachingABlockEndsWithinReachAndOutsideIt() {
        BlockPos chest = new BlockPos(10, Y, 0);
        TestWorld world = field().set(chest, Blocks.CHEST.defaultBlockState());
        SearchResult result = search(world, defaults(), START, Goals.reach(chest, SURVIVAL));
        assertTrue(result.arrived());
        BlockPos end = result.route().end();
        Stance stance = result.route().endStance();
        assertTrue(Reach.reaches(SURVIVAL, Pose.STANDING, end.getX(), stance.feetY(), end.getZ(), chest));
        assertNotEquals(chest, end);
        assertTrue(end.getX() < chest.getX(), "够得着就停,不走到跟前");
    }

    @Test
    void standingOnABlockEndsOnTopOfIt() {
        BlockPos block = new BlockPos(6, Y, 0);
        TestWorld world = field().set(block, STONE);
        SearchResult result = search(world, defaults(), START, Goals.standOn(block));
        assertTrue(result.arrived());
        assertEquals(block.above(), result.route().end());
        assertEquals(block.getY(), result.route().endStance().supportY());
    }

    @Test
    void levelColumnNearAndRingEachArriveByTheirOwnRule() {
        TestWorld world = field().set(4, Y, 0, STONE).fill(5, Y, 0, 5, Y + 1, 0, STONE);
        assertEquals(Y + 2, search(world, defaults(), START, Goals.level(Y + 2)).route().end().getY());
        BlockPos column = search(field(), defaults(), START, Goals.column(7, 3)).route().end();
        assertEquals(7, column.getX());
        assertEquals(3, column.getZ());
        BlockPos center = new BlockPos(12, Y, 0);
        BlockPos near = search(field(), defaults(), START, Goals.near(center, 2)).route().end();
        assertTrue(near.distSqr(center) <= 4);
        BlockPos ring = search(field(), defaults(), START, Goals.ring(center, 3, 4)).route().end();
        double d = Math.sqrt(Math.pow(ring.getX() - center.getX(), 2) + Math.pow(ring.getZ() - center.getZ(), 2));
        assertTrue(d >= 3 && d <= 4, "停在环带上,不走到中心:" + d);
    }

    @Test
    void awayFromCreaturesEndsOutsideEveryDangerRadius() {
        List<Threat> threats = List.of(new Threat(2.5, Y, 0.5, 4), new Threat(-1.5, Y, 2.5, 3));
        SearchResult result = search(field(), defaults(), START, Goals.awayFrom(threats));
        assertTrue(result.arrived());
        BlockPos end = result.route().end();
        for (Threat t : threats) {
            double dx = end.getX() + 0.5 - t.x();
            double dz = end.getZ() + 0.5 - t.z();
            assertTrue(dx * dx + dz * dz >= t.radius() * t.radius());
        }
    }

    @Test
    void oneOfSeveralGoalsIsPickedByWalkingPlusArrivalPrice() {
        BlockPos near = new BlockPos(3, Y, 0);
        BlockPos far = new BlockPos(9, Y, 0);
        Goal cheapNear = Goals.anyOf(List.of(Goals.at(near), Goals.at(far)));
        assertEquals(near, search(field(), defaults(), START, cheapNear).route().end());
        Goal pricyNear = Goals.anyOf(List.of(Goals.priced(Goals.at(near), 500), Goals.at(far)));
        assertEquals(far, search(field(), defaults(), START, pricyNear).route().end(), "近处那个到了还要付 500 刻");
    }

    @Test
    void theGoalsOwnCellsAreNeverDugOrFilledOnTheWay() {
        // 身体在地板下一格宽的竖井里,目标是正上方地板上那一格:最近的上法是挖穿目标脚下那块再垫回去
        BlockPos goal = new BlockPos(5, Y, 0);
        TestWorld world = new TestWorld().floor(0, -2, 10, 2, Y - 1).set(5, Y - 4, 0, STONE);
        world.fill(4, Y - 3, 0, 4, Y - 2, 0, STONE).fill(6, Y - 3, 0, 6, Y - 2, 0, STONE)
                .fill(5, Y - 3, -1, 5, Y - 2, -1, STONE).fill(5, Y - 3, 1, 5, Y - 2, 1, STONE);
        world.set(5, Y - 3, 0, Blocks.AIR.defaultBlockState()).set(5, Y - 2, 0, Blocks.AIR.defaultBlockState());
        BlockPos shaft = new BlockPos(5, Y - 3, 0);
        CostModel model = Fixtures.withCobble(natural());
        SearchResult loose = search(world, model, shaft, Goals.near(goal, 0));
        assertTrue(loose.arrived() && digs(loose.route(), goal.below()), "不保护时最便宜的是挖穿目标脚下那块再垫回去");
        SearchResult guarded = search(world, model, shaft, Goals.at(goal));
        assertTrue(guarded.arrived(), "从旁边绕上去");
        assertFalse(digs(guarded.route(), goal.below()), "目标脚下那块不挖");
    }

    @Test
    void aStopStillCountsAfterTheGoalChangesOnlyIfItIsStillInsideAndNotDearer() {
        BlockPos stop = new BlockPos(5, Y, 0);
        Stance standing = new Stance(Stance.Kind.GROUND, Y, Y - 1);
        Goal before = Goals.near(new BlockPos(6, Y, 0), 2);
        assertTrue(Goal.keepsStop(before, Goals.near(new BlockPos(7, Y, 0), 2), stop, standing), "挪了一格,还在里面");
        assertFalse(Goal.keepsStop(before, Goals.near(new BlockPos(12, Y, 0), 2), stop, standing), "挪远了");
        assertFalse(Goal.keepsStop(Goals.priced(before, 0), Goals.priced(before, 50), stop, standing), "停在这儿变贵了");
    }

    // ==================== 候选路线、旧路打折、生物危险 ====================

    /** 两条平行的走廊 z=0 与 z=4,两头连通,z=0 那条短。 */
    private static TestWorld twoCorridors() {
        TestWorld world = new TestWorld().floor(-1, -1, 11, 5, Y - 1);
        world.fill(-1, Y, -1, 11, Y + 1, -1, STONE).fill(-1, Y, 5, 11, Y + 1, 5, STONE);
        return world.fill(2, Y, 1, 8, Y + 1, 3, STONE);
    }

    @Test
    void candidateRoutesAreDistinctAndOverlappingOnesAreDropped() {
        RoutePlanner.Plan plan = RoutePlanner.run(new RoutePlanner.Query(twoCorridors(), defaults(), START,
                Goals.at(new BlockPos(10, Y, 0)), Fixtures.BUDGET, 3), () -> false);
        assertNull(plan.unreached());
        assertEquals(2, plan.candidates().size(), "只有两条走廊,第三条与前两条重叠太多被丢掉");
        Route first = plan.candidates().get(0);
        Route second = plan.candidates().get(1);
        assertTrue(first.nodes().stream().anyMatch(n -> n.getZ() == 0 && n.getX() == 5));
        assertTrue(second.nodes().stream().anyMatch(n -> n.getZ() == 4 && n.getX() == 5));
        assertTrue(first.cost() < second.cost(), "候选按原价计,不带逼出备选的加价");
    }

    @Test
    void aReplanKeepsToTheOldRoadWhenAnotherIsJustAsGood() {
        // 中间一道墙,两侧 z=-2 与 z=2 各一条一样长的走廊
        TestWorld world = new TestWorld().floor(-1, -3, 11, 3, Y - 1);
        world.fill(-1, Y, -3, 11, Y + 1, -3, STONE).fill(-1, Y, 3, 11, Y + 1, 3, STONE);
        world.fill(2, Y, -1, 8, Y + 1, 1, STONE);
        CostModel model = defaults();
        Goal goal = Goals.at(new BlockPos(10, Y, 0));
        List<Route> both = RoutePlanner.run(new RoutePlanner.Query(world, model, START, goal, Fixtures.BUDGET, 2),
                () -> false).candidates();
        assertEquals(2, both.size());
        for (Route old : both) {
            int side = old.nodes().stream().filter(n -> n.getX() == 5).findFirst().orElseThrow().getZ();
            SearchResult replan = AStar.run(new Search(world, model, START, goal, Fixtures.BUDGET, Favoring.along(old)),
                    () -> false);
            assertTrue(replan.route().nodes().contains(new BlockPos(5, Y, side)), "沿旧路走,不为差价换道");
        }
    }

    @Test
    void routesDetourAroundCreatures() {
        Threat zombie = new Threat(10.5, Y, 0.5, 3);
        CostModel wary = CostModel.of(RouteSpec.defaults(), Fixtures.body(), TerrainPolicy.ALLOW_ALL, Materials.NONE,
                () -> List.of(zombie));
        Goal goal = Goals.at(new BlockPos(20, Y, 0));
        assertTrue(search(field(), defaults(), START, goal).route().nodes().stream()
                .anyMatch(n -> zombie.covers(n.getX(), n.getY(), n.getZ())), "没有生物时直穿");
        Route route = search(field(), wary, START, goal).route();
        assertTrue(route.nodes().stream().noneMatch(n -> zombie.covers(n.getX(), n.getY(), n.getZ())), "绕开它的危险半径");
    }

    // ==================== 起点 ====================

    @Test
    void aBodyOverhangingAnEdgeStartsFromTheColumnHoldingIt() {
        TestWorld world = new TestWorld().floor(1, -2, 6, 2, Y - 1);
        // 身体中心在 x=0.8,脚下那一列悬空,脚底压着东边那一列
        Optional<BlockPos> origin = Origin.of(world, SURVIVAL, 0.8, Y, 0.5);
        assertEquals(Optional.of(new BlockPos(1, Y, 0)), origin);
        assertEquals(Optional.of(new BlockPos(3, Y, 0)), Origin.of(world, SURVIVAL, 3.5, Y, 0.5));
        assertEquals(Optional.empty(), Origin.of(world, SURVIVAL, -3.5, Y, 0.5));
        assertInstanceOf(BlockPos.class, origin.orElseThrow());
    }
}
