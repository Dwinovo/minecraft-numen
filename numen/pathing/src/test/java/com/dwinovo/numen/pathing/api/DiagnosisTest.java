package com.dwinovo.numen.pathing.api;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import java.util.List;

import com.dwinovo.numen.pathing.plan.Breath;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.Reason;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.AStar;
import com.dwinovo.numen.pathing.search.Favoring;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.SearchResult;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 没路时的诊断:憋不住气的那一段水下先报;搜完无路时依次换条件再问,说出是缺改动、缺料、缺预算、许可拒绝还是真没路。 */
class DiagnosisTest {

    private static final int Y = 64;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
        DIRT = Blocks.DIRT.defaultBlockState();
        BEDROCK = Blocks.BEDROCK.defaultBlockState();
        AIR = Blocks.AIR.defaultBlockState();
    }

    /**
     * 两间干屋之间只有一条三十格长的封顶水道(地、四壁与顶都是基岩):搜完无路,诊断说憋得住就有路,点出那一段水下从踏进水里
     * 的那一格到走出水的那一格、要憋约 320 刻(31 步水里的工夫,水里走一格 10.2 刻,加走出来那一步),满氧气能安全憋 240 刻。
     */
    @Test
    void aWayOnlyUnderWaterTooLongToHoldIsToldAsSuch() {
        TestWorld world = new TestWorld().fill(-4, Y - 1, -1, 34, Y + 2, 1, Blocks.BEDROCK.defaultBlockState());
        world.fill(-3, Y, 0, -1, Y + 1, 0, Blocks.AIR.defaultBlockState());
        world.fill(0, Y, 0, 30, Y + 1, 0, Blocks.WATER.defaultBlockState());
        world.fill(31, Y, 0, 33, Y + 1, 0, Blocks.AIR.defaultBlockState());
        Search search = new Search(world, Fixtures.model(RouteSpec.defaults()), new BlockPos(-2, Y, 0),
                Goals.at(new BlockPos(32, Y, 0)), Fixtures.BUDGET, Favoring.NONE);
        SearchResult failed = AStar.run(search, () -> false);
        assertEquals(SearchResult.Stop.EXHAUSTED, failed.stop());
        Outcome outcome = Diagnosis.of(failed.stop(), failed.breathless(), search, failed.blocking(), () -> false);
        Outcome.Breathless breathless = assertInstanceOf(Outcome.Breathless.class, outcome);
        assertEquals(new BlockPos(-1, Y, 0), breathless.from());
        assertEquals(new BlockPos(31, Y, 0), breathless.to());
        assertTrue(breathless.held() > 310 && breathless.held() < 330, "要憋的刻数:" + breathless.held());
        assertEquals(300 - (int) Breath.RESERVE, breathless.spare());
    }

    /** 一道基岩墙挡在身前,口在二十格外,只许展开两百个节点:搜不到口就用完了——诊断说"预算用完",不说无路。 */
    @Test
    void runningOutOfBudgetIsOutOfBudgetNotNoRoute() {
        TestWorld world = new TestWorld().floor(0, 0, 40, 39, Y - 1);
        world.fill(10, Y, 0, 10, Y + 2, 33, Blocks.BEDROCK.defaultBlockState());
        Search search = new Search(world, Fixtures.model(RouteSpec.defaults()), new BlockPos(8, Y, 15), Goals.at(new BlockPos(13, Y, 15)), 200,
                Favoring.NONE);
        SearchResult failed = AStar.run(search, () -> false);
        assertEquals(SearchResult.Stop.BUDGET, failed.stop());
        assertInstanceOf(Outcome.OutOfBudget.class, Diagnosis.of(failed.stop(), failed.breathless(), search, failed.blocking(), () -> false));

        // 四面与头顶都是基岩的小屋:搜完了才停,诊断说无路
        TestWorld box = new TestWorld().floor(0, 0, 20, 20, Y - 1).fill(4, Y - 1, 4, 8, Y + 3, 8, Blocks.BEDROCK.defaultBlockState())
                .fill(5, Y, 5, 7, Y + 2, 7, Blocks.AIR.defaultBlockState());
        Search sealed = new Search(box, Fixtures.model(RouteSpec.defaults()), new BlockPos(6, Y, 6), Goals.at(new BlockPos(15, Y, 15)),
                Fixtures.BUDGET, Favoring.NONE);
        SearchResult none = AStar.run(sealed, () -> false);
        assertEquals(SearchResult.Stop.EXHAUSTED, none.stop());
        assertInstanceOf(Outcome.NoRoute.class, Diagnosis.of(none.stop(), none.breathless(), sealed, none.blocking(), () -> false));
    }

    /** 去处在加载区块之外:走得到的都搜过了,路却伸出了快照、那边是什么不知道——诊断说"未加载",不说无路或预算用完。 */
    @Test
    void aRouteIntoUnloadedChunksIsUnloaded() {
        TestWorld world = new TestWorld().floor(0, 0, 40, 3, Y - 1).loadedWithin(0);
        Search search = new Search(world, Fixtures.model(RouteSpec.defaults()), new BlockPos(1, Y, 1), Goals.at(new BlockPos(40, Y, 1)), Fixtures.BUDGET,
                Favoring.NONE);
        SearchResult failed = AStar.run(search, () -> false);
        assertEquals(SearchResult.Stop.UNLOADED, failed.stop());
        assertInstanceOf(Outcome.Unloaded.class, Diagnosis.of(failed.stop(), failed.breathless(), search, failed.blocking(), () -> false));
    }

    // ==================== 搜完无路之后换条件再问 ====================

    // 方块要在引导之后才取得到,在 boot 里赋值
    private static BlockState DIRT;
    private static BlockState BEDROCK;
    private static BlockState AIR;

    /** 这份规格、这片许可、身上这些料,原版身体。 */
    private static CostModel model(RouteSpec spec, TerrainPolicy terrain, Materials materials) {
        return CostModel.of(spec, Fixtures.body(), terrain, materials, Threats.NONE);
    }

    /** 搜一次,确认没交出路,交回诊断。 */
    private static Outcome diagnose(TestWorld world, CostModel model, BlockPos start, Goal goal) {
        Search search = new Search(world, model, start, goal, Fixtures.BUDGET, Favoring.NONE);
        SearchResult failed = AStar.run(search, () -> false);
        assertNull(failed.route(), "搜到了路:" + failed.route());
        return Diagnosis.of(failed.stop(), failed.breathless(), search, failed.blocking(), () -> false);
    }

    /** 四十格见方的地面,地板在脚下一格。 */
    private static TestWorld field() {
        return new TestWorld().floor(0, 0, 39, 39, Y - 1);
    }

    /** 一间泥土小屋:四面墙与屋顶都是泥土,屋里三格见方、三格高;出去只能挖墙(屋顶压着,垫柱也翻不出去)。 */
    private static TestWorld hut() {
        return field().fill(2, Y, 2, 8, Y + 3, 8, DIRT).fill(3, Y, 3, 7, Y + 2, 7, AIR);
    }

    /** 高出地面四格的两块基岩台子,中间 x = 10..13 一道沟:沟挖不动,跳下去也摔不起,只能搭桥。台面上的身体脚在 Y + 4。 */
    private static TestWorld ditch() {
        return field().fill(0, Y, 0, 9, Y + 3, 39, BEDROCK).fill(14, Y, 0, 39, Y + 3, 39, BEDROCK);
    }

    private static final BlockPos IN_HUT = new BlockPos(5, Y, 5);
    private static final BlockPos BEYOND_HUT = new BlockPos(12, Y, 5);

    /** 泥土墙横贯场地、没有口:不许挖不许放时说"许挖许放就有路",要改几格,不是要问主人的格。 */
    @Test
    void aWallWithNoGapNeedsChangesWhenTheSpecAllowsNone() {
        TestWorld world = field().fill(8, Y, 0, 8, Y + 2, 39, DIRT);
        Outcome.NeedsChanges needs = assertInstanceOf(Outcome.NeedsChanges.class,
                diagnose(world, Fixtures.model(RouteSpec.defaults()), new BlockPos(4, Y, 5), Goals.at(new BlockPos(12, Y, 5))));
        assertFalse(needs.asks());
        assertTrue(needs.alterations() > 0, needs.toString());
    }

    /** 关在泥土小屋里,整间屋子许可都拒绝:结局是"许可拒绝",点出屋墙上的一格与许可给的理由。 */
    @Test
    void aHutTheTerrainPolicyDeniesIsDeniedNamingAWallCellAndTheReason() {
        TestWorld world = hut();
        TerrainPolicy hut = (change, pos, state, view) -> state.is(Blocks.DIRT) ? Permit.deny("owner's hut") : Permit.ALLOW;
        Outcome.Denied denied = assertInstanceOf(Outcome.Denied.class,
                diagnose(world, model(Fixtures.natural(), hut, Fixtures.NO_MATERIALS), IN_HUT, Goals.at(BEYOND_HUT)));
        assertEquals("owner's hut", denied.reason());
        assertTrue(world.getBlockState(denied.cell()).is(Blocks.DIRT), "点出的是屋墙上的一格:" + denied.cell());
    }

    /**
     * 关在泥土小屋里,屋子许可答"要问":要问的格当墙时,结局是"把要问的格算能走才有路";算能走时挖出去,挖的都是泥土,
     * 每一格带着许可给的凭据。
     */
    @Test
    void aHutThatNeedsConsentIsReachedOnlyWhenAskingCountsAndTheDugCellsCarryTheCredential() {
        TestWorld world = hut();
        TerrainPolicy hut = (change, pos, state, view) -> state.is(Blocks.DIRT) ? Permit.ask("hut:" + pos.toShortString()) : Permit.ALLOW;
        Outcome.NeedsChanges needs = assertInstanceOf(Outcome.NeedsChanges.class,
                diagnose(world, model(Fixtures.natural(), hut, Fixtures.NO_MATERIALS), IN_HUT, Goals.at(BEYOND_HUT)));
        assertTrue(needs.asks(), needs.toString());

        SearchResult asked = Fixtures.search(world, model(RouteSpec.defaults().edit().changes(true).build(), hut, Fixtures.NO_MATERIALS),
                IN_HUT, Goals.at(BEYOND_HUT));
        assertTrue(asked.arrived(), asked.stop().toString());
        List<Edit> dug = asked.route().edits().stream().filter(e -> e instanceof Edit.Dig).toList();
        assertFalse(dug.isEmpty());
        for (Edit e : dug) {
            Edit.Dig dig = (Edit.Dig) e;
            assertTrue(dig.state().is(Blocks.DIRT), "挖了泥土以外的:" + dig);
            assertEquals("hut:" + dig.pos().toShortString(), assertInstanceOf(Permit.Ask.class, dig.permit()).credential());
        }
    }

    /**
     * 没有顶的泥土围栏(墙三格高,跳不出去),泥土要问、身上没料:要问的格当墙时结局是"把要问的格算能走才有路",不说成没料。
     * 带着圆石时垫柱翻出去,墙一格不挖:料在这个场景里确实是另一条出路。
     */
    @Test
    void askingIsReportedBeforeMaterialsAndWithMaterialsTheBodyPillarsOutWithoutDigging() {
        TestWorld world = field().fill(2, Y, 2, 8, Y + 2, 8, DIRT).fill(3, Y, 3, 7, Y + 2, 7, AIR);
        TerrainPolicy pen = (change, pos, state, view) -> state.is(Blocks.DIRT) ? Permit.ask("pen") : Permit.ALLOW;
        Outcome.NeedsChanges needs = assertInstanceOf(Outcome.NeedsChanges.class,
                diagnose(world, model(Fixtures.natural(), pen, Fixtures.NO_MATERIALS), IN_HUT, Goals.at(BEYOND_HUT)));
        assertTrue(needs.asks(), needs.toString());

        SearchResult carrying = Fixtures.search(world, model(Fixtures.natural(), pen, Fixtures.COBBLE), IN_HUT, Goals.at(BEYOND_HUT));
        assertTrue(carrying.arrived(), carrying.stop().toString());
        assertTrue(carrying.route().edits().stream().noneMatch(e -> e instanceof Edit.Dig), "挖了墙:" + carrying.route().edits());
        assertTrue(carrying.route().edits().stream().anyMatch(e -> e instanceof Edit.Place), "没有垫柱");
    }

    /** 关在泥土小屋里,改动预算只有一格:结局是"改动预算不够",最便宜那条要改两格。 */
    @Test
    void aHutWithAnAlterBudgetOfOneIsOverBudgetByTheCheapestWaysCount() {
        Outcome.OverAlterBudget over = assertInstanceOf(Outcome.OverAlterBudget.class, diagnose(hut(),
                Fixtures.model(Fixtures.natural().edit().alterBudget(1).build()), IN_HUT, Goals.at(BEYOND_HUT)));
        assertEquals(2, over.needed());
    }

    /**
     * 阁楼的洞口正上方一格压着倒扣的屋顶楼梯:身子从梯子顶上直不起来,也挪不进阁楼——不许改地形时结局是要改地形。
     */
    @Test
    void aRoofStairRightOverTheHatchNeedsChanges() {
        TestWorld world = field().fill(3, Y + 4, 3, 12, Y + 4, 12, Blocks.OAK_PLANKS.defaultBlockState())
                .fill(8, Y, 9, 8, Y + 4, 9, Blocks.STONE.defaultBlockState());
        for (int y = Y; y <= Y + 4; y++) {
            world.set(8, y, 8, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
        }
        world.set(8, Y + 6, 8, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST)
                .setValue(StairBlock.HALF, Half.TOP));
        assertInstanceOf(Outcome.NeedsChanges.class,
                diagnose(world, Fixtures.model(RouteSpec.defaults()), new BlockPos(8, Y, 5), Goals.at(new BlockPos(6, Y + 5, 8))));
    }

    /** 一片横贯场地的岩浆:不许改地形时走不过去,结局是许改地形(在岩浆上搭桥)才有路。 */
    @Test
    void aLavaFieldAcrossTheWayNeedsChanges() {
        TestWorld world = field().fill(7, Y - 2, 0, 11, Y - 2, 39, Blocks.STONE.defaultBlockState())
                .fill(8, Y - 1, 0, 10, Y - 1, 39, Blocks.LAVA.defaultBlockState());
        assertInstanceOf(Outcome.NeedsChanges.class,
                diagnose(world, Fixtures.model(RouteSpec.defaults()), new BlockPos(4, Y, 5), Goals.at(new BlockPos(14, Y, 5))));
    }

    /** 去处那一格站得住,却封在基岩屋墙里的一个小格中:许改地形、有料也出不去,结局是"搜完无路"。 */
    @Test
    void aStandableGoalSealedInBedrockHasNoRoute() {
        TestWorld world = field().fill(2, Y - 1, 2, 17, Y + 3, 8, BEDROCK).fill(3, Y, 3, 11, Y + 2, 7, AIR)
                .fill(15, Y, 5, 15, Y + 1, 5, AIR);
        assertInstanceOf(Outcome.NoRoute.class,
                diagnose(world, Fixtures.model(RouteSpec.defaults()), new BlockPos(4, Y, 5), Goals.at(new BlockPos(15, Y, 5))));
    }

    /** 沟只能搭桥过去:身上没料时结局是"没有料";料只在背包深处、料端口不认(答空)时也是"没有料"。 */
    @Test
    void aDitchWithoutMaterialsIsNoMaterialsEvenWithCobbleTheMaterialsPortDoesNotOffer() {
        BlockPos start = new BlockPos(6, Y + 4, 5);
        Goal across = Goals.at(new BlockPos(17, Y + 4, 5));
        assertInstanceOf(Outcome.NoMaterials.class, diagnose(ditch(), Fixtures.model(Fixtures.natural()), start, across));
        CostModel deepOnly = CostModel.of(Fixtures.natural(), Fixtures.carrying(20, new ItemStack(Items.COBBLESTONE, 16)),
                Fixtures.ALLOW_ALL, Fixtures.NO_MATERIALS, Threats.NONE);
        assertInstanceOf(Outcome.NoMaterials.class, diagnose(ditch(), deepOnly, start, across));
    }

    /** 不许改地形时,身上有料没料,结局都是"许改地形才有路",不提料。 */
    @Test
    void aDitchUnderASpecThatAllowsNoChangesNeedsChangesWithOrWithoutMaterials() {
        BlockPos start = new BlockPos(6, Y + 4, 5);
        Goal across = Goals.at(new BlockPos(17, Y + 4, 5));
        assertInstanceOf(Outcome.NeedsChanges.class, diagnose(ditch(), Fixtures.withCobble(RouteSpec.defaults()), start, across));
        assertInstanceOf(Outcome.NeedsChanges.class, diagnose(ditch(), Fixtures.model(RouteSpec.defaults()), start, across));
    }

    /** 一条封死的廊子:{@code x0..x1} 长、{@code z = 4..8} 宽五格、高三格,地、两侧墙与顶都是基岩——挖不动,也没处可垫。 */
    private static TestWorld sealedCorridor(int x0, int x1) {
        return field().fill(x0, Y - 1, 3, x1, Y - 1, 9, BEDROCK).fill(x0, Y, 3, x1, Y + 3, 3, BEDROCK)
                .fill(x0, Y, 9, x1, Y + 3, 9, BEDROCK).fill(x0, Y + 3, 4, x1, Y + 3, 8, BEDROCK)
                .fill(x0 - 1, Y - 1, 3, x0 - 1, Y + 3, 9, BEDROCK).fill(x1 + 1, Y - 1, 3, x1 + 1, Y + 3, 9, BEDROCK);
    }

    private static RouteSpec changes() {
        return RouteSpec.defaults().edit().changes(true).build();
    }

    /** 三格厚的石墙隔断封死的廊子,墙所在的 x 范围整片是许可不让挖放的禁区:绕不过去,结局是"许可拒绝",点出墙上的一格。 */
    @Test
    void aForbiddenZoneAcrossASealedCorridorIsDeniedAtAWallCell() {
        TestWorld world = sealedCorridor(1, 25).fill(10, Y, 4, 12, Y + 2, 8, Blocks.STONE.defaultBlockState());
        TerrainPolicy zone = (change, pos, state, view) -> pos.getX() >= 8 && pos.getX() <= 14 ? Permit.deny("forbidden zone") : Permit.ALLOW;
        Outcome.Denied denied = assertInstanceOf(Outcome.Denied.class,
                diagnose(world, model(changes(), zone, Fixtures.NO_MATERIALS), new BlockPos(2, Y, 6), Goals.at(new BlockPos(24, Y, 6))));
        assertTrue(denied.cell().getX() >= 10 && denied.cell().getX() <= 12, "该点出墙上的一格:" + denied.cell());
        assertEquals("forbidden zone", denied.reason());
    }

    /** 封死的廊子里横着两格厚的黑曜石墙,空手:结局是"没有路",原因是挖不动。 */
    @Test
    void obsidianWithoutAPickaxeInASealedCorridorHasNoRouteBecauseItIsTooHard() {
        TestWorld world = sealedCorridor(1, 25).fill(10, Y, 4, 11, Y + 2, 8, Blocks.OBSIDIAN.defaultBlockState());
        Outcome.NoRoute none = assertInstanceOf(Outcome.NoRoute.class,
                diagnose(world, Fixtures.model(changes()), new BlockPos(2, Y, 6), Goals.at(new BlockPos(24, Y, 6))));
        assertNotNull(none.cause(), "没说挡在哪");
        assertEquals(Reason.TOO_HARD, none.cause().reason());
    }

    /**
     * 规格许落二十格,满血的身体站在九格高的石柱顶上,柱脚四周一圈朝上的滴水石锥尖:落上去按原版掉 15 点,摔不起,不许改地形
     * 就没有路,结局是要改地形;同样高的柱子四周是石头地时直接跳下去(掉血)。
     */
    @Test
    void aPillarRingedWithUpwardDripstoneNeedsChangesWhereStoneGroundIsJumpedTo() {
        RouteSpec spec = RouteSpec.defaults().edit().maxFallDamage(17).build();
        TestWorld spikes = field().fill(3, Y, 2, 7, Y, 6, Blocks.POINTED_DRIPSTONE.defaultBlockState())
                .fill(4, Y, 3, 6, Y + 8, 5, Blocks.STONE.defaultBlockState());
        assertInstanceOf(Outcome.NeedsChanges.class,
                diagnose(spikes, Fixtures.model(spec), new BlockPos(5, Y + 9, 4), Goals.at(new BlockPos(10, Y, 4))));
        TestWorld stone = field().fill(4, Y, 3, 6, Y + 8, 5, Blocks.STONE.defaultBlockState());
        SearchResult jumped = Fixtures.search(stone, Fixtures.model(spec), new BlockPos(5, Y + 9, 4), Goals.at(new BlockPos(10, Y, 4)));
        assertTrue(jumped.arrived(), jumped.stop().toString());
        assertTrue(jumped.route().legs().stream().anyMatch(l -> l.maneuver().fallDamage() > 0), "落在石头上摔得起,该跳下去");
    }

    /** 生存模式面对十二格宽、没有地板的峡谷:不会飞,不许改地形时结局是要改地形(搭桥),路里没有飞的步子可言。 */
    @Test
    void aSurvivalBodyFacingACanyonNeedsChanges() {
        TestWorld world = field().fill(10, Y - 1, 0, 21, Y - 1, 39, AIR);
        assertInstanceOf(Outcome.NeedsChanges.class,
                diagnose(world, Fixtures.model(RouteSpec.defaults()), new BlockPos(5, Y, 20), Goals.at(new BlockPos(28, Y, 20))));
    }
}
