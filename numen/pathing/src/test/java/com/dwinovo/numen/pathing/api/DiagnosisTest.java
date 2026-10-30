package com.dwinovo.numen.pathing.api;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.Breath;
import com.dwinovo.numen.pathing.search.AStar;
import com.dwinovo.numen.pathing.search.Favoring;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.SearchResult;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 没路时的诊断:憋不住气的那一段水下先报。 */
class DiagnosisTest {

    private static final int Y = 64;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
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
}
