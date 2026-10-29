package com.dwinovo.numen.pathing.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.Reason;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 一次搜索的路线与没到目标时:加权 A* 交出的路、每一步在前面步子做完的世界里成立、没到目标时的学习与边界上的推进、这一趟记忆里
 * 走不通的步、收场说缺什么(被拒的候选)。地板在 {@code Y - 1}。
 */
class PartialSearchTest {

    private static final int Y = 64;
    private static final BlockPos START = new BlockPos(0, Y, 0);
    private static BlockState stone;
    private static BlockState air;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
        stone = Blocks.STONE.defaultBlockState();
        air = Blocks.AIR.defaultBlockState();
    }

    private static TestWorld field() {
        return new TestWorld().floor(-4, -12, 24, 12, Y - 1);
    }

    /** 一整面墙把场地隔成两半(从地板到四格高、整条 z 方向),去处在墙那边。 */
    private static TestWorld walled(BlockState wall) {
        return field().fill(6, Y, -12, 6, Y + 3, 12, wall);
    }

    private static final BlockPos BEYOND = new BlockPos(12, Y, 0);

    // ==================== 加权 A* ====================

    /**
     * 一堵墙横在去处前面,只在远处留了个缺口:空手挖两格墙比绕一段缺口贵得多。朝真终点的加权搜索一次搜到底,交出绕缺口过去的路,不挖。
     */
    @Test
    void theSearchGoesAroundAWallThroughAFarGapRatherThanDiggingIt() {
        TestWorld wall = field().fill(6, Y, -12, 6, Y + 3, 12, stone).fill(6, Y, 9, 6, Y + 3, 9, air);
        SearchResult result = Fixtures.search(wall, Fixtures.model(Fixtures.natural()), START, Goals.at(BEYOND), 3000);
        assertTrue(result.arrived(), result.stop().toString());
        assertEquals(0, result.route().alterations(), "绕缺口走,不挖");
    }

    /**
     * 一段推进八格疾跑的估价:搜到一个估价比起点低这么多的节点就交出到那里的整截,目标还在前面;同一个世界只认目标时一次走到底。
     */
    @Test
    void aSegmentEndsAtTheFirstNodeThatIsASegmentCloserToTheGoal() {
        CostModel model = Fixtures.model(Fixtures.natural());
        double sprint = com.dwinovo.numen.pathing.world.Kinematics.pace(model.body().stats()).sprint();
        Goal goal = Goals.at(new BlockPos(20, Y, 0));
        Search whole = new Search(field(), model, START, goal, Fixtures.BUDGET, Favoring.NONE);
        SearchResult segment = AStar.run(whole.advancing(8 * sprint), () -> false);
        assertEquals(SearchResult.Stop.SEGMENT, segment.stop());
        assertTrue(segment.reached());
        BlockPos end = segment.route().end();
        assertTrue(end.getX() >= 8 && end.getX() < 20, "推进了一段,没到目标:" + end);
        SearchResult arrived = AStar.run(whole, () -> false);
        assertTrue(arrived.arrived(), arrived.stop().toString());
    }

    /**
     * 这一趟的记忆抬高了起点的估价(前一次搜索在这里没搜出路):一段量的是离目标近了多少,不把抬高的增量算进去,
     * 不然旁边没探过的节点一个个都算"近了一段",一段段往外走。
     */
    @Test
    void aSegmentIsMeasuredTowardTheGoalNotAgainstWhatTheTripLearned() {
        CostModel model = Fixtures.model(Fixtures.natural());
        double sprint = com.dwinovo.numen.pathing.world.Kinematics.pace(model.body().stats()).sprint();
        TripMemory memory = new TripMemory();
        memory.raise(START.asLong(), 100 * sprint);
        Search search = new Search(field(), model, START, Goals.at(new BlockPos(20, Y, 0)), Fixtures.BUDGET, Favoring.NONE)
                .remembering(memory.frozen()).advancing(8 * sprint);
        SearchResult segment = AStar.run(search, () -> false);
        assertEquals(SearchResult.Stop.SEGMENT, segment.stop());
        assertTrue(segment.route().end().getX() >= 8, "朝目标推进了一段:" + segment.route().end());
    }

    /** 四壁是基岩、里面是实心石头的竖井(边长 {@code size}),空手;起点是井底角上挖好的两格空间。 */
    private static TestWorld shaft(int size, int height) {
        return new TestWorld().fill(-3, Y - 3, -3, size + 2, Y + height + 3, size + 2, Blocks.BEDROCK.defaultBlockState())
                .fill(0, Y - 1, 0, size - 1, Y + height + 1, size - 1, stone).fill(0, Y, 0, 0, Y + 1, 0, air);
    }

    /**
     * 空手在三乘三的石柱里一级一级往上凿(身边没有垫路料,只能绕着柱子盘旋):下一级的脚下正是前几步刚挖开的格。交出的路线里每一步
     * 的前提,在"前面所有的步子都做完了改动"的世界里都成立——只当上一步的改动做过了,盘旋回来的那一级会当脚下还是实心的,执行时脚下空了、
     * 又没有料可垫。两乘两的柱子盘旋不起来,没有路。
     */
    @Test
    void everyStepOfARouteHoldsInTheWorldMadeByAllTheEarlierSteps() {
        CostModel model = Fixtures.model(Fixtures.natural());
        TestWorld column = shaft(3, 7);
        SearchResult result = Fixtures.search(column, model, START, Goals.at(new BlockPos(2, Y + 7, 2)));
        assertTrue(result.arrived(), result.stop().toString());
        java.util.List<com.dwinovo.numen.pathing.plan.Edit> done = new java.util.ArrayList<>();
        for (Route.Leg leg : result.route().legs()) {
            com.dwinovo.numen.pathing.plan.Maneuver m = leg.maneuver();
            com.dwinovo.numen.pathing.plan.WorldView world = com.dwinovo.numen.pathing.plan.EditedView.after(column, done);
            com.dwinovo.numen.pathing.plan.Premise premise = com.dwinovo.numen.pathing.plan.Moves.of(m.kind())
                    .premise(model, world, m.from(), m.start(), m.heading());
            assertTrue(premise instanceof com.dwinovo.numen.pathing.plan.Premise.Holds,
                    m.kind() + " " + m.from() + "→" + m.to() + " 在前面步子做完改动的世界里不成立:" + premise);
            done.addAll(m.edits());
        }
        assertEquals(SearchResult.Stop.EXHAUSTED,
                Fixtures.search(shaft(2, 7), model, START, Goals.at(new BlockPos(1, Y + 7, 1))).stop());
    }

    // ==================== 没到目标:学习、推进 ====================

    /**
     * 去处在一间朝西开口的 U 形屋子后面:朝它直走的路都进了屋子深处的死角。预算只够把屋子里翻一遍,没搜到:展开过的格子的估价
     * 抬高了(死角里的比外面的抬得多),下一次带着这份记忆再搜,不会再先去翻死角。
     */
    @Test
    void aFailedSearchRaisesTheEstimatesOfTheCellsItExpandedAndTheNextSearchAvoidsThem() {
        TestWorld trap = field().fill(4, Y, -4, 12, Y + 2, -4, stone).fill(4, Y, 4, 12, Y + 2, 4, stone)
                .fill(12, Y, -4, 12, Y + 2, 4, stone);
        Goal goal = Goals.at(new BlockPos(20, Y, 0));
        CostModel model = Fixtures.model(RouteSpec.defaults());
        Search first = new Search(trap, model, new BlockPos(6, Y, 0), goal, 60, Favoring.NONE);
        SearchResult failed = AStar.run(first, () -> false);
        assertEquals(SearchResult.Stop.BUDGET, failed.stop());
        assertFalse(failed.raised().isEmpty(), "展开过的格子的估价抬高了");
        for (double more : failed.raised().values()) {
            assertTrue(more > 0 && Double.isFinite(more));
        }
        TripMemory memory = new TripMemory();
        memory.raise(failed.raised());
        Estimate before = Estimate.of(trap, model, goal, new BlockPos(6, Y, 0), 60, TripMemory.Frozen.NONE, 1);
        Estimate after = Estimate.of(trap, model, goal, new BlockPos(6, Y, 0), 60, memory.frozen(), 1);
        BlockPos deepest = new BlockPos(11, Y, 0);
        assertTrue(after.at(deepest.getX(), deepest.getY(), deepest.getZ()) > before.at(deepest.getX(), deepest.getY(), deepest.getZ()),
                "死角深处的估价比原来远");
        SearchResult second = AStar.run(first.remembering(memory.frozen()), () -> false);
        assertNotNull(second.route(), "带着记忆再搜,交得出朝外走的路");
        for (BlockPos node : second.route().nodes()) {
            assertFalse(node.getX() > 6 && node.getX() < 12 && Math.abs(node.getZ()) < 4, "不再往死角深处走:" + node);
        }
    }

    /**
     * 往石头里挖下去的竖井里,一个节点往外的步子全是没取出的未核候选(再往下挖、往旁边挖,取出来才精确判;井里没有往回走的路):
     * 它们与没展开的节点一样是出路,不是死路——抬高之后每一格的估价都有限,起点(它身后没有改动,与重搜同一个世界)的估价仍是下界,
     * 不超过从起点重搜走到目标的真实代价。
     */
    @Test
    void raisedEstimatesStayFiniteAndTheStartsStaysBelowTheRealCostWhenTheExitsAreUnverifiedCandidates() {
        TestWorld ground = new TestWorld().ground(Y - 1, stone).loadedWithin(6);
        CostModel model = CostModel.of(Fixtures.natural(), Fixtures.carrying(0, new ItemStack(Items.IRON_PICKAXE)),
                Fixtures.ALLOW_ALL, Fixtures.NO_MATERIALS, Threats.NONE);
        Goal goal = Goals.dig(new BlockPos(20, Y - 20, 5), Vanilla.SURVIVAL, Goals.Clearing.ANY);
        SearchResult failed = AStar.run(new Search(ground, model, START, goal, 3000, Favoring.NONE), () -> false);
        assertEquals(SearchResult.Stop.BUDGET, failed.stop());
        for (it.unimi.dsi.fastutil.longs.Long2DoubleMap.Entry e : failed.raised().long2DoubleEntrySet()) {
            assertTrue(Double.isFinite(e.getDoubleValue()), "抬成了无穷:" + BlockPos.of(e.getLongKey()));
        }
        Estimate estimate = Estimate.of(ground, model, goal, START, Fixtures.BUDGET, TripMemory.Frozen.NONE, 1);
        SearchResult real = Fixtures.search(ground, model, START, goal, Fixtures.BUDGET);
        assertTrue(real.arrived(), real.stop().toString());
        double raised = estimate.at(START) + failed.raised().get(START.asLong());
        assertTrue(raised <= real.route().cost() + 1e-6, "起点抬到 " + raised + ",重搜走到目标只要 " + real.route().cost());
    }

    /** 能走到的地方都搜过了、没有目标:展开过的格子估价无穷大,再带着这份记忆搜一个节点也不展开。 */
    @Test
    void aSearchedOutRegionIsRememberedAsUnreachable() {
        TestWorld room = field().fill(-2, Y, -2, 2, Y + 2, 2, stone).fill(-1, Y, -1, 1, Y + 1, 1, air);
        Search search = new Search(room, Fixtures.model(RouteSpec.defaults()), START, Goals.at(BEYOND), Fixtures.BUDGET, Favoring.NONE);
        SearchResult sealed = AStar.run(search, () -> false);
        assertEquals(SearchResult.Stop.EXHAUSTED, sealed.stop());
        assertTrue(sealed.raised().containsKey(START.asLong()));
        assertTrue(Double.isInfinite(sealed.raised().get(START.asLong())));
        TripMemory memory = new TripMemory();
        memory.raise(sealed.raised());
        SearchResult again = AStar.run(search.remembering(memory.frozen()), () -> false);
        assertEquals(SearchResult.Stop.EXHAUSTED, again.stop());
        assertEquals(0, again.expanded());
    }

    // ==================== 这一趟里走不通的步 ====================

    @Test
    void aStepRememberedAsBlockedIsNeverTakenAgain() {
        TestWorld world = field();
        CostModel model = Fixtures.model(RouteSpec.defaults());
        Goal goal = Goals.at(new BlockPos(10, Y, 0));
        Route route = Fixtures.search(world, model, START, goal).route();
        Route.Leg third = route.legs().get(2);
        TripMemory memory = new TripMemory();
        memory.block(third.maneuver().kind(), third.maneuver().from(), third.maneuver().to());
        Search search = new Search(world, model, START, goal, Fixtures.BUDGET, Favoring.NONE).remembering(memory.frozen());
        SearchResult detour = AStar.run(search, () -> false);
        assertTrue(detour.arrived());
        for (Route.Leg leg : detour.route().legs()) {
            assertFalse(leg.maneuver().kind() == third.maneuver().kind() && leg.maneuver().from().equals(third.maneuver().from())
                    && leg.maneuver().to().equals(third.maneuver().to()), "没绕开记为不通的那一步");
        }
    }

    // ==================== 收场说缺什么 ====================

    private static SearchResult.Blocking blocked(TestWorld world, CostModel model) {
        SearchResult result = Fixtures.search(world, model, START, Goals.at(BEYOND));
        assertFalse(result.arrived(), "这面墙过不去:" + result.stop());
        assertNotNull(result.blocking(), "要说为什么过不去");
        return result.blocking();
    }

    @Test
    void aWallThatMayNotBeDugSaysDiggingIsOff() {
        SearchResult.Blocking why = blocked(walled(stone), Fixtures.model(RouteSpec.defaults()));
        assertEquals(Reason.NO_DIGGING, why.fails().reason());
        assertEquals(6, why.fails().cell().getX(), "卡在墙上");
    }

    @Test
    void bedrockSaysItCannotBeBroken() {
        assertEquals(Reason.UNBREAKABLE, blocked(walled(Blocks.BEDROCK.defaultBlockState()), Fixtures.model(Fixtures.natural())).fails().reason());
    }

    @Test
    void obsidianWithBareHandsSaysItIsTooHardAndADiamondPickaxeDigsIt() {
        TestWorld wall = walled(Blocks.OBSIDIAN.defaultBlockState());
        assertEquals(Reason.TOO_HARD, blocked(wall, Fixtures.model(Fixtures.natural())).fails().reason());
        CostModel pickaxe = CostModel.of(Fixtures.natural(), Fixtures.carrying(0, new ItemStack(Items.DIAMOND_PICKAXE)),
                Fixtures.ALLOW_ALL, Fixtures.NO_MATERIALS, Threats.NONE);
        assertTrue(Fixtures.search(wall, pickaxe, START, Goals.at(BEYOND)).arrived());
    }

    @Test
    void aZoneThePermissionsRefuseSaysPermissionDeniesIt() {
        CostModel model = CostModel.of(Fixtures.natural(), Fixtures.body(), (change, pos, state, view) ->
                pos.getX() == 6 ? Permit.deny("zone") : Permit.ALLOW, Fixtures.NO_MATERIALS, Threats.NONE);
        assertEquals(Reason.DENIED, blocked(walled(stone), model).fails().reason());
    }

    @Test
    void aGapWithNoBlocksToBridgeItSaysThereAreNoMaterials() {
        TestWorld gap = new TestWorld().floor(-4, -4, 5, 4, Y - 1).floor(14, -4, 24, 4, Y - 1);
        SearchResult.Blocking why = blocked(gap, Fixtures.model(Fixtures.natural()));
        assertEquals(Reason.NO_MATERIALS, why.fails().reason());
        assertNull(Optional.ofNullable(why.fails().detail()).orElse(null));
    }
}
