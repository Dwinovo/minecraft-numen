package com.dwinovo.numen.pathing.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.drive.Halt;
import com.dwinovo.numen.pathing.drive.Journal;
import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.AStar;
import com.dwinovo.numen.pathing.search.Favoring;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.WorldSnapshot;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 整趟的逻辑在不带身体的预演里:预算用完不说成无路;长途分段接上、段与段之间不等;挖隧道的长路、一路搭桥的长路走完;
 * 搜索的结论晚到多少刻都不改结果;走不过去的一堵墙试的次数有界。用的是真实的 {@code drive.Trip},世界是 {@link TestWorld}。
 */
class RehearsalTest {

    private static final int Y = 64;
    private static final RouteSpec NATURAL = RouteSpec.defaults().edit().changes(true).consent(false).build();
    private static final Materials COBBLE = () -> Optional.of(Blocks.COBBLESTONE);

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static BlockPos at(int x, int y, int z) {
        return new BlockPos(x, Y - 1 + y, z);
    }

    private static Rehearsal.Scene scene(TestWorld world, RouteSpec spec, BodySnapshot body, Materials materials, BlockPos start, Goal goal, int budget) {
        CostModel model = CostModel.of(spec, body, Fixtures.ALLOW_ALL, materials, Threats.NONE);
        return new Rehearsal.Scene(from -> world.window(from, WorldSnapshot.SEARCH_RADIUS), from -> false, model, spec, goal, start, budget);
    }

    private static Rehearsal.Scene scene(TestWorld world, RouteSpec spec, BlockPos start, Goal goal) {
        return scene(world, spec, Fixtures.body(), Fixtures.NO_MATERIALS, start, goal, 0);
    }

    // ==================== 预算与长途 ====================

    /** 一道基岩墙挡在身前,口在二十格外:这一趟只许展开两百个节点,搜不到口就用完了——收场是"预算用完",不是无路;出厂预算搜得出绕过去的路。 */
    @Test
    void runningOutOfBudgetIsNotNoRoute() {
        TestWorld world = new TestWorld().floor(0, 0, 40, 39, Y - 1);
        world.fill(10, Y, 0, 10, Y + 2, 33, Blocks.BEDROCK.defaultBlockState());
        Goal goal = Goals.at(at(13, 1, 15));
        Rehearsal.Result plenty = new Rehearsal(scene(world, RouteSpec.defaults(), at(8, 1, 15), goal), "plenty").run();
        assertTrue(plenty.arrived(), "出厂预算应当搜得出绕过去的路:" + plenty.halt());
        assertTrue(plenty.edits().isEmpty());
        Rehearsal.Result tight = new Rehearsal(scene(world, RouteSpec.defaults(), Fixtures.body(), Fixtures.NO_MATERIALS, at(8, 1, 15), goal, 200), "tight").run();
        Halt.Searched halt = assertInstanceOf(Halt.Searched.class, tight.halt());
        assertEquals(com.dwinovo.numen.pathing.search.SearchResult.Stop.BUDGET, halt.stop());
        assertTrue(tight.edits().isEmpty());
    }

    /**
     * 世界在身前就到头了(边之外永远没有加载的区块),去处在边外:这一趟没到,又停在"没加载"、窗口伸出了边——是要区域之外的地形。
     * 同样的失败,窗口没伸出过边就是放弃;标准答案说区域内能到、或证明区域内无解的,也仍是放弃。
     */
    @Test
    void aTripThatRunsIntoTheEdgeOfTheWorldNeedsTerrainBeyondIt() {
        TestWorld world = new TestWorld().floor(0, 0, 40, 40, Y - 1);
        Goal goal = Goals.at(at(120, 1, 20));
        CostModel model = CostModel.of(RouteSpec.defaults(), Fixtures.body(), Fixtures.ALLOW_ALL, Fixtures.NO_MATERIALS, Threats.NONE);
        Rehearsal.Result edge = new Rehearsal(new Rehearsal.Scene(from -> world.window(from, 1), from -> true, model, RouteSpec.defaults(), goal, at(2, 1, 20), 0),
                "edge").run();
        assertTrue(edge.edge(), String.valueOf(edge.halt()));
        assertEquals(Row.Outcome.BEYOND, edge.outcome(null));
        assertEquals(Row.Outcome.BEYOND, edge.outcome(Answers.Status.UNKNOWN));
        assertEquals(Row.Outcome.GAVE_UP, edge.outcome(Answers.Status.OPTIMAL), "答案说区域内能到:是规划没做成");
        assertEquals(Row.Outcome.GAVE_UP, edge.outcome(Answers.Status.BEST_KNOWN));
        assertEquals(Row.Outcome.GAVE_UP, edge.outcome(Answers.Status.UNSOLVABLE), "答案说区域内无解:本该证出无解");
        Rehearsal.Result inside = new Rehearsal(new Rehearsal.Scene(from -> world.window(from, 1), from -> false, model, RouteSpec.defaults(), goal, at(2, 1, 20), 0),
                "inside").run();
        assertEquals(Row.Outcome.GAVE_UP, inside.outcome(null), "窗口没伸出过边:没加载是快照半径的边,不是区域的边");
    }

    /** 两百格长的平地:一段一段搜、一段一段接上,从起步到终点,除了第一次从脚下搜,身体一刻也没站着等搜索。 */
    @Test
    void joinsLongSegmentsWithoutStopping() {
        TestWorld world = new TestWorld().floor(0, 0, 220, 24, Y - 1);
        Rehearsal.Result r = new Rehearsal(scene(world, RouteSpec.defaults(), at(2, 1, 12), Goals.at(at(210, 1, 12))), "long").run();
        assertTrue(r.arrived(), String.valueOf(r.halt()));
        assertTrue(r.searches() >= 3, "该分了几段:" + r.searches());
        assertEquals(r.firstMove(), r.waited(), 1e-9, "段与段之间站着等了搜索");
    }

    /**
     * 身前是九十五格厚的整片石头(顶上盖着一层基岩),去处在石头里八十九格深,手上一把钻石镐,许挖许放:朝去处直挖的那一遍一次规划
     * 就搜得到头;照这条路挖过去,到去处。
     */
    @Test
    void tunnelsALongWayThroughSolidRock() {
        TestWorld world = new TestWorld().floor(0, 0, 110, 23, Y - 1);
        world.fill(6, Y, 0, 100, Y + 13, 23, Blocks.STONE.defaultBlockState());
        world.fill(6, Y + 14, 0, 100, Y + 14, 23, Blocks.BEDROCK.defaultBlockState());
        BodySnapshot body = Fixtures.carrying(0, new ItemStack(Items.DIAMOND_PICKAXE));
        Goal goal = Goals.at(at(94, 1, 12));
        Rehearsal.Scene scene = scene(world, NATURAL, body, Fixtures.NO_MATERIALS, at(2, 1, 12), goal, 0);
        Search plan = new Search(world, scene.model(), scene.start(), goal, NavRequest.DEFAULT_BUDGET, Favoring.NONE);
        assertTrue(AStar.run(plan, () -> false).arrived(), "朝去处直挖,一次规划应当搜得到头");
        Rehearsal.Result r = new Rehearsal(scene, "tunnel").run();
        assertTrue(r.arrived(), String.valueOf(r.halt()));
        assertTrue(r.dug() >= 2 * 88, "没挖出隧道:" + r.dug() + " 格");
    }

    /**
     * 两块基岩台子之间一百五十格宽的空隙,身上有圆石,许挖许放:过去只能一路搭桥,对岸远在一次搜索之外。起步那段按表只想几刻——两秒(四十刻)
     * 以内就动起来,不等整条路搜完。(搭完整座桥、到对岸要真身体照搭,在 {@code UpDownGameTests} 里;预演这块平整的合成地面上,加权搜索
     * 要把整个平台铺开才找得到桥头,一趟的总预算先用完。)
     */
    @Test
    void startsMovingBeforeTheWholeBridgeIsFound() {
        TestWorld world = new TestWorld().ground(Y - 1, Blocks.STONE.defaultBlockState());
        world.fill(0, Y, 0, 9, Y + 3, 23, Blocks.BEDROCK.defaultBlockState());
        world.fill(160, Y, 0, 180, Y + 3, 23, Blocks.BEDROCK.defaultBlockState());
        Rehearsal.Result r = new Rehearsal(scene(world, NATURAL, Fixtures.body(), COBBLE, at(6, 5, 12), Goals.at(at(164, 5, 12)), 0), "bridge").run();
        assertTrue(r.steps() > 0, "没有交出能先走的一段:" + r.halt());
        assertTrue(r.firstMove() <= 40, "两秒了还站着等搜索:" + r.firstMove() + " 刻");
    }

    // ==================== 结论晚到 ====================

    /** 一个按指定刻数"想"的表:搜索从不因时间到而停,每次搜索的结论都晚到 {@code delay} 刻。 */
    private static Metered late(int delay) {
        return new Metered() {
            @Override
            public Timer start(long limit) {
                return (expanded, checked) -> false;
            }

            @Override
            public long nanos() {
                return delay * 50_000_000L;
            }
        };
    }

    private static List<BlockPos> cells(List<Edit> edits, Class<?>... kinds) {
        return edits.stream().filter(e -> List.of(kinds).stream().anyMatch(k -> k.isInstance(e))).map(Edit::pos).toList();
    }

    /** 同一道题,搜索的结论晚到 0、1、40、400 刻:走出来的路、挖的与放的格一格不差,都与不走的规划一样。 */
    private static void sameWhenTheConclusionIsLate(Rehearsal.Scene scene) {
        Search search = new Search(scene.snapshots().apply(scene.start()), scene.model(), scene.start(), scene.goal(), Fixtures.BUDGET, Favoring.NONE);
        Route planned = AStar.run(search, () -> false).route();
        assertNotNull(planned, "没规划出路");
        for (int delay : new int[] {0, 1, 40, 400}) {
            Rehearsal.Result r = new Rehearsal(scene, "late", late(delay), step -> false).run();
            assertTrue(r.arrived(), "晚到 " + delay + " 刻:" + r.halt());
            assertEquals(delay, r.firstMove(), 1e-9);
            assertEquals(cells(planned.edits(), Edit.Dig.class), cells(r.edits(), Edit.Dig.class), "晚到 " + delay + " 刻挖的格");
            assertEquals(cells(planned.edits(), Edit.Place.class, Edit.Catch.class), cells(r.edits(), Edit.Place.class, Edit.Catch.class),
                    "晚到 " + delay + " 刻放的格");
        }
    }

    /** 三格厚、三格高的泥土墙横贯场地,手上铁锹,许挖许放:挖的正是规划要挖的那几格。 */
    @Test
    void digsThePlannedCellsWhateverTheDelay() {
        TestWorld world = new TestWorld().floor(0, 0, 39, 39, Y - 1);
        world.fill(10, Y, 0, 12, Y + 2, 39, Blocks.DIRT.defaultBlockState());
        BodySnapshot body = Fixtures.carrying(0, new ItemStack(Items.IRON_SHOVEL));
        sameWhenTheConclusionIsLate(scene(world, NATURAL, body, Fixtures.NO_MATERIALS, at(5, 1, 20), Goals.at(at(15, 1, 20)), 0));
    }

    /** 两块基岩台子之间一道四格宽的沟,身上有圆石:搭桥过去,放的正是规划要放的那几格。 */
    @Test
    void bridgesThePlannedCellsWhateverTheDelay() {
        TestWorld world = new TestWorld().floor(0, 0, 39, 39, Y - 1);
        world.fill(0, Y, 0, 9, Y + 3, 39, Blocks.BEDROCK.defaultBlockState());
        world.fill(14, Y, 0, 39, Y + 3, 39, Blocks.BEDROCK.defaultBlockState());
        sameWhenTheConclusionIsLate(scene(world, NATURAL, Fixtures.body(), COBBLE, at(6, 5, 20), Goals.at(at(17, 5, 20)), 0));
    }

    /** 中间一堵墙,绕过去;一格不改。 */
    @Test
    void walksAroundTheSameWallWhateverTheDelay() {
        TestWorld world = new TestWorld().floor(0, 0, 39, 39, Y - 1);
        world.fill(8, Y, 2, 8, Y + 1, 10, Blocks.STONE.defaultBlockState());
        Rehearsal.Scene scene = scene(world, RouteSpec.defaults(), at(4, 1, 6), Goals.at(at(12, 1, 6)));
        sameWhenTheConclusionIsLate(scene);
        assertTrue(new Rehearsal(scene, "wall").run().edits().isEmpty());
    }

    // ==================== 走不过去的墙 ====================

    /**
     * 一道横贯场地的墙(十二格,墙两头接着石墙),规划看不见它挡路、虚拟身体走到墙上的任何一格都做不成那一步:每试一处,
     * 那一步记为不通、换一处再试;试完(或总预算先用完)就收场。试的次数有界(每个不同的步只试一次),收场不是"到了"。真身体顶着潜影贝时
     * 实际试几次,是 {@code TerminationGameTests} 里同一堵墙的用例。
     */
    @Test
    void aWallNobodyCanCrossIsTriedABoundedNumberOfTimes() {
        TestWorld world = new TestWorld().floor(0, 0, 39, 13, Y - 1);
        world.fill(0, Y, 0, 39, Y + 2, 0, Blocks.STONE.defaultBlockState());
        world.fill(0, Y, 13, 39, Y + 2, 13, Blocks.STONE.defaultBlockState());
        Rehearsal.Scene scene = scene(world, RouteSpec.defaults(), at(3, 1, 6), Goals.at(at(20, 1, 6)));
        Rehearsal.Result r = new Rehearsal(scene, "wall", new Rehearsal.Steady(), step -> step.to().getX() == 10).run();
        assertTrue(!r.arrived());
        assertTrue(r.halt() instanceof Halt.Blocked || r.halt() instanceof Halt.Searched, String.valueOf(r.halt()));
        int stuck = r.replans(Journal.Replan.TIMEOUT);
        // 每次卡住的是一个不同的(走法、起点、落点):进墙上一格的有一步平走与两步斜走,试过的不再试,所以次数不超过这个数
        assertTrue(stuck >= 1 && stuck <= 3 * 12, "卡住的次数 " + stuck);
    }
}
