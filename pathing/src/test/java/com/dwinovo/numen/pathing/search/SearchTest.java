package com.dwinovo.numen.pathing.search;

import java.util.List;
import java.util.Optional;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.api.PlanQuery;
import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threat;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.spec.RouteSpec.Alter;
import com.dwinovo.numen.pathing.world.Reach;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        // 区块最东一列是 x = 15;走进它要看边外 x = 16 那一格伤不伤身(挨着走要加价),那一格不知道,这一步就不走
        assertEquals(14, result.route().end().getX(), "停在加载区块的边上");
    }

    /**
     * 一间封死的小屋贴着加载区块的边(原点所在区块 x 到 15 为止,屋子的东墙在 x = 13):屋里走遍了也没有路。跑酷那一步
     * 伸得到边外的列,可它没开、或者紧挨着的那一列是墙根本不是空隙——这一步与边外是什么无关,结论是搜完无路,不是"未加载"。
     */
    @Test
    void aStepThatDoesNotHoldRegardlessOfTheUnloadedColumnsDoesNotMakeItUnloaded() {
        TestWorld room = new TestWorld().floor(0, 0, 15, 12, Y - 1)
                .fill(9, Y, 4, 13, Y + 2, 8, STONE).fill(10, Y, 5, 12, Y + 1, 7, Blocks.AIR.defaultBlockState())
                .loadedWithin(0);
        BlockPos start = new BlockPos(11, Y, 6);
        Goal away = Goals.at(new BlockPos(1, Y, 6));
        assertEquals(SearchResult.Stop.EXHAUSTED, search(room, defaults(), start, away).stop(), "跑酷没开");
        CostModel parkour = Fixtures.model(RouteSpec.defaults().edit().parkour(true).build());
        assertEquals(SearchResult.Stop.EXHAUSTED, search(room, parkour, start, away).stop(), "紧挨着的一列是墙,不是空隙");
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

    /** 两块台子之间隔着三十格、下面空着的空隙,身上有圆石:过去只能一路搭桥,搜索在空中四面铺开。 */
    private static TestWorld chasm() {
        return new TestWorld().floor(-4, -4, 2, 4, Y - 1).floor(33, -4, 40, 4, Y - 1);
    }

    private static final BlockPos ACROSS = new BlockPos(36, Y, 0);

    /** 跑一次搜索,顺便数它展开了几个节点(每展开一个问一次叫没叫停)。 */
    private static SearchResult counted(Search search, int[] expanded) {
        return AStar.run(search, () -> {
            expanded[0]++;
            return false;
        });
    }

    /**
     * 许放块时要搭一长段桥:展开到先交半程的节点数时,只要有了离起点够远的一段就交出它,不等搜到底——交出的这一段朝目标推进,
     * 停因是"没搜完"。
     */
    @Test
    void aSearchHandsOverAPartialRouteOnceItReachesTheHandOverCount() {
        CostModel model = Fixtures.withCobble(natural());
        Search search = new Search(chasm(), model, START, Goals.at(ACROSS), 50_000, Favoring.NONE).handingOverAt(2000);
        int[] expanded = {0};
        SearchResult result = counted(search, expanded);
        assertEquals(SearchResult.Stop.BUDGET, result.stop(), "先交出的半程是没搜完,不是到了");
        assertNotNull(result.route());
        assertTrue(expanded[0] <= 2001, "展开到先交半程的节点数就交:展开了 " + expanded[0]);
        BlockPos end = result.route().end();
        assertTrue(end.distSqr(START) > AStar.MIN_PARTIAL * AStar.MIN_PARTIAL, "交出的一段离起点够远:" + end);
        assertTrue(end.distSqr(ACROSS) < START.distSqr(ACROSS), "交出的一段朝目标推进:" + end);
    }

    /** 在先交半程的节点数之内就到了的,路线与搜到底一模一样。 */
    @Test
    void aSearchThatArrivesBeforeTheHandOverCountKeepsItsRoute() {
        TestWorld world = field().fill(6, Y, -6, 6, Y + 1, 6, STONE);
        Goal goal = Goals.at(new BlockPos(15, Y, 3));
        Search full = new Search(world, Fixtures.withCobble(natural()), START, goal, Fixtures.BUDGET, Favoring.NONE);
        int[] expanded = {0};
        SearchResult whole = counted(full, expanded);
        assertTrue(whole.arrived());
        SearchResult early = AStar.run(full.handingOverAt(expanded[0]), () -> false);
        assertTrue(early.arrived());
        assertEquals(legs(whole.route()), legs(early.route()));
    }

    private static List<String> legs(Route route) {
        return route.legs().stream().map(leg -> leg.maneuver().kind() + " " + leg.maneuver().from() + "->"
                + leg.maneuver().to() + " " + leg.cost() + " " + leg.maneuver().edits()).toList();
    }

    /**
     * 接着一段搭到半空的路线往下搜:起点是那段路线的终点,脚下那块桥还没放、快照里是空的。只给起点,身体在那里待不住;
     * 给出走到那里的那一步,就照它的落点与它垫下的块接着搜下去。
     */
    @Test
    void aSearchContinuingARouteStartsFromTheLastStepsLandingAndEdits() {
        TestWorld world = chasm();
        CostModel model = Fixtures.withCobble(natural());
        Goal goal = Goals.at(ACROSS);
        SearchResult first = AStar.run(new Search(world, model, START, goal, 50_000, Favoring.NONE).handingOverAt(2000),
                () -> false);
        Route route = first.route();
        assertNotNull(route);
        BlockPos end = route.end();
        assertTrue(end.getX() > 2, "第一段搭到了空隙上方:" + end);
        Search fromEnd = new Search(world, model, end, goal, 50_000, Favoring.NONE).handingOverAt(2000);
        assertEquals(SearchResult.Stop.STRANDED, AStar.run(fromEnd, () -> false).stop(), "快照里桥还没搭,起点待不住");
        SearchResult next = AStar.run(fromEnd.after(route.legs().get(route.legs().size() - 1).maneuver()), () -> false);
        assertNotNull(next.route(), "照最后一步的落点与它垫的块接着搜:" + next.stop());
        assertEquals(end, next.route().start());
        assertTrue(next.route().end().distSqr(ACROSS) < end.distSqr(ACROSS), "接着朝目标推进");
    }

    @Test
    void aBodyThatCannotStandAtTheStartIsStranded() {
        SearchResult result = search(new TestWorld(), defaults(), START, Goals.at(new BlockPos(5, Y, 0)));
        assertEquals(SearchResult.Stop.STRANDED, result.stop());
    }

    /**
     * 两条走廊 z=0 与 z=4 两头连通,z=0 那条短,可规格给它中段每一格加了价:搜出来的是绕远的 z=4,价钱与只许走 z=4 时
     * 搜出的一样(最便宜),比只许走 z=0 时便宜——按价钱挑路,不按远近。
     */
    @Test
    void theCheapestOfTwoWaysIsTakenEvenWhenItIsTheLongerOne() {
        PositionCosts.Builder dear = PositionCosts.builder();
        for (int x = 3; x <= 7; x++) {
            dear.add(Use.PASS, new BlockPos(x, Y, 0).asLong(), 20);
        }
        RouteSpec spec = RouteSpec.defaults().edit().positions(dear.build()).build();
        Goal goal = Goals.at(new BlockPos(10, Y, 0));
        Route best = search(twoCorridors(), Fixtures.model(spec), START, goal).route();
        Route onlyLong = search(twoCorridors(), Fixtures.model(spec.edit().positions(spec.positions()
                .plus(PositionCosts.builder().forbid(Use.PASS, new BlockPos(5, Y, 0).asLong()).build())).build()),
                START, goal).route();
        Route onlyShort = search(twoCorridors(), Fixtures.model(spec.edit().positions(spec.positions()
                .plus(PositionCosts.builder().forbid(Use.PASS, new BlockPos(5, Y, 4).asLong()).build())).build()),
                START, goal).route();
        assertTrue(best.nodes().contains(new BlockPos(5, Y, 4)), "走绕远的那条");
        assertEquals(onlyLong.cost(), best.cost(), 0.1);
        assertTrue(best.cost() < onlyShort.cost(), "比加了价的近路便宜");
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
        TerrainPolicy policy = (change, pos, state, view) -> pos.getX() == 5 && pos.getZ() == 0 ? Permit.deny("玩家放的") : Permit.ALLOW;
        CostModel model = CostModel.of(natural(), Fixtures.body(), policy, Materials.NONE, Threats.NONE);
        SearchResult result = search(world, model, START, Goals.at(new BlockPos(10, Y, 0)));
        assertTrue(result.arrived());
        assertFalse(digs(result.route(), denied) || digs(result.route(), denied.above()), "许可拒绝的格不进路线");
        assertTrue(digs(result.route(), new BlockPos(5, Y, 4)), "改走另一条走廊");
    }

    @Test
    void cellsThatNeedConsentEnterTheRouteOnlyUnderAny() {
        TestWorld world = corridor().fill(5, Y, 0, 5, Y + 1, 0, Blocks.DIRT.defaultBlockState());
        TerrainPolicy policy = (change, pos, state, view) -> pos.getX() == 5 ? Permit.ask("要主人点头") : Permit.ALLOW;
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

    /** 站在离目标三到四格的环带上,同时离环带东边一只怪五格开外:停的地方两条都成立,环带靠怪那一侧不算。 */
    @Test
    void severalGoalsAtOnceEndWhereEveryOneHolds() {
        BlockPos center = new BlockPos(12, Y, 0);
        Threat creature = new Threat(16.5, Y, 0.5, 5);
        SearchResult result = search(field(), defaults(), START,
                Goals.allOf(List.of(Goals.ring(center, 3, 4), Goals.awayFrom(List.of(creature)))));
        assertTrue(result.arrived());
        BlockPos end = result.route().end();
        double ring = Math.sqrt(Math.pow(end.getX() - center.getX(), 2) + Math.pow(end.getZ() - center.getZ(), 2));
        assertTrue(ring >= 3 && ring <= 4, "停在环带上:" + ring);
        double dx = end.getX() + 0.5 - creature.x();
        double dz = end.getZ() + 0.5 - creature.z();
        assertTrue(dx * dx + dz * dz >= 25, "离那只怪五格开外:" + end);
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

    /** 同一个目标去掉目标格保护:到达与估价照旧,不护着任何格。用来看不保护时最便宜的路会不会动那几格。 */
    private static Goal unguarded(Goal goal) {
        return new Goal() {
            @Override
            public boolean contains(int x, int y, int z, Stance stance) {
                return goal.contains(x, y, z, stance);
            }

            @Override
            public double estimate(int x, int y, int z) {
                return goal.estimate(x, y, z);
            }
        };
    }

    private static boolean fills(Route route, BlockPos cell) {
        return route.edits().stream().anyMatch(e -> (e instanceof Edit.Place || e instanceof Edit.Catch) && e.pos().equals(cell));
    }

    /**
     * 身体站在一口一格宽、两格深的石坑底,要够的正是脚下这一格:不保护时最便宜的是原地垫一块——把那一格填上、站到它上面去
     * 够它;贴脸的目标不往要够的那一格里放东西,改从坑壁出去。
     */
    @Test
    void reachingABlockNeverFillsItOnTheWay() {
        TestWorld pit = field().fill(-1, Y, -1, 1, Y + 1, 1, STONE).fill(0, Y, 0, 0, Y + 1, 0, Blocks.AIR.defaultBlockState());
        Goal reach = Goals.reach(START, SURVIVAL);
        CostModel model = Fixtures.withCobble(natural());
        SearchResult loose = search(pit, model, START, unguarded(reach));
        assertTrue(loose.arrived() && fills(loose.route(), START), "不保护时最便宜的是把要够的那一格垫上");
        SearchResult guarded = search(pit, model, START, reach);
        assertTrue(guarded.arrived(), "从坑壁出去");
        assertFalse(fills(guarded.route(), START), "要够的那一格不放东西");
    }

    /**
     * 身体站在十二格高的崖边,带着一桶水和一叠圆石,去处是崖脚紧挨着的那一格:最便宜的是直接落进去、把水倒在那一格里接住自己。
     * 站到某一格的目标不许往自己要站的两格里放方块(放下就留在那儿,把要站的格埋了);倒下的水在同一步里就收回,不受这条保护——
     * 保护与不保护一样,都倒水接在去处那一格里,路上都不拿方块垫那两格。
     */
    @Test
    void standingOnACellNeverFillsItsOwnTwoCellsOnTheWay() {
        TestWorld cliff = new TestWorld().floor(-4, -4, 0, 4, Y - 1).floor(1, -4, 6, 4, Y - 13);
        BlockPos foot = new BlockPos(1, Y - 12, 0);
        BodySnapshot bucket = Fixtures.carrying(0, new ItemStack(Items.WATER_BUCKET));
        CostModel model = CostModel.of(natural(), bucket, TerrainPolicy.ALLOW_ALL, Fixtures.COBBLE, Threats.NONE);
        SearchResult loose = search(cliff, model, START, unguarded(Goals.at(foot)));
        assertTrue(loose.arrived() && catches(loose.route(), foot), "最便宜的是把水倒在要站的那一格里");
        SearchResult guarded = search(cliff, model, START, Goals.at(foot));
        assertTrue(guarded.arrived());
        assertEquals(foot, guarded.route().end());
        assertTrue(catches(guarded.route(), foot), "倒下的水当场收回,不受目标格保护:照样接在要站的那一格里");
        assertFalse(places(guarded.route(), foot) || places(guarded.route(), foot.above()), "要站的两格不放方块");
    }

    private static boolean catches(Route route, BlockPos cell) {
        return route.edits().stream().anyMatch(e -> e instanceof Edit.Catch && e.pos().equals(cell));
    }

    private static boolean places(Route route, BlockPos cell) {
        return route.edits().stream().anyMatch(e -> e instanceof Edit.Place && e.pos().equals(cell));
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

    /**
     * 要三条候选,预算只够搜出最便宜的那一条:第二次搜索(已有候选经过的格加了价,要绕开它得多展开)预算用完,规划收工——
     * 先找到的那一条照样交出,并说出收工的那次搜索为什么停。
     */
    @Test
    void aCandidateSearchThatStopsShortKeepsTheCandidatesFoundAndSaysWhy() {
        TestWorld world = field();
        CostModel model = defaults();
        Goal goal = Goals.at(new BlockPos(12, Y, 0));
        int enough = 1;
        while (!search(world, model, START, goal, enough).arrived()) {
            enough++;
        }
        Route cheapest = search(world, model, START, goal, enough).route();
        RoutePlanner.Plan plan = RoutePlanner.run(new RoutePlanner.Query(world, model, START, goal, enough, 3), () -> false);
        assertEquals(SearchResult.Stop.BUDGET, plan.unreached(), "第二次搜索预算用完");
        assertEquals(1, plan.candidates().size(), "先找到的那一条留着");
        assertEquals(cheapest.nodes(), plan.candidates().get(0).nodes());
    }

    /** 候选条数只能是 1 到上限:要 0 条或超过上限,查询本身就不成立——搜索这一层的查询与门面的查询都一样。 */
    @Test
    void askingForNoCandidatesOrMoreThanTheLimitIsRefused() {
        Goal goal = Goals.at(new BlockPos(5, Y, 0));
        for (int wanted : new int[] {0, RoutePlanner.MAX_CANDIDATES + 1}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new RoutePlanner.Query(field(), defaults(), START, goal, Fixtures.BUDGET, wanted), "要 " + wanted + " 条");
            assertThrows(IllegalArgumentException.class, () -> PlanQuery.of(goal, RouteSpec.defaults(), wanted),
                    "要 " + wanted + " 条");
        }
        new RoutePlanner.Query(field(), defaults(), START, goal, Fixtures.BUDGET, RoutePlanner.MAX_CANDIDATES);
        PlanQuery.of(goal, RouteSpec.defaults(), PlanQuery.MAX_CANDIDATES);
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
