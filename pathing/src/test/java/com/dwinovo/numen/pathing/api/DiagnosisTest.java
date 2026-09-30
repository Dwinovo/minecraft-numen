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
     * 的那一格到走出水的那一格、要憋约 286 刻(31 步水里的工夫加走出来那一步),满氧气能安全憋 240 刻。
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
        Outcome outcome = Diagnosis.of(failed.stop(), failed.breathless(), search, () -> false);
        Outcome.Breathless breathless = assertInstanceOf(Outcome.Breathless.class, outcome);
        assertEquals(new BlockPos(-1, Y, 0), breathless.from());
        assertEquals(new BlockPos(31, Y, 0), breathless.to());
        assertTrue(breathless.held() > 280 && breathless.held() < 295, "要憋的刻数:" + breathless.held());
        assertEquals(300 - (int) Breath.RESERVE, breathless.spare());
    }
}
