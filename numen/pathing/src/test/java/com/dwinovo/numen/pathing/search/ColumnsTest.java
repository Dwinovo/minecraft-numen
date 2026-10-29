package com.dwinovo.numen.pathing.search;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Kinematics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 估价的水平项(按列的放宽图上从目标反着做的最短路)是怎么来的:陆地疾跑,水列游或涉水,从岸上走进水里第一步仍是疾跑,
 * 搭桥更便宜时取搭桥,许跑酷时都是疾跑,目标在快照之外时从快照边界列出发。下界由 {@link EstimateConsistencyTest} 逐种走法核对。
 */
class ColumnsTest {

    private static final int Y = 64;
    private static final BlockPos START = new BlockPos(0, Y, 0);
    private static final Kinematics.Pace PACE = Kinematics.pace(SURVIVAL);
    private static final double SPRINT = PACE.sprint();
    private static BlockState STONE;
    private static BlockState WATER;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
        STONE = Blocks.STONE.defaultBlockState();
        WATER = Blocks.WATER.defaultBlockState();
    }

    /** 西岸 x ≤ 1,湖 x = 2..12(十一列),东岸 x ≥ 13;岸与水面一样高。南北各三十格,绕过去比游过去远。 */
    private static TestWorld lake() {
        return new TestWorld().floor(-6, -30, 20, 30, Y - 4).fill(-6, Y - 3, -30, 1, Y - 1, 30, STONE).fill(13, Y - 3, -30, 20, Y - 1, 30, STONE)
                .fill(2, Y - 3, -30, 12, Y - 1, 30, WATER);
    }

    private static double floor(TestWorld world, CostModel model, BlockPos goal) {
        return Columns.of(world, model, Goals.at(goal), START, PACE).floor(START.getX(), START.getY(), START.getZ());
    }

    @Test
    void landIsPricedAtTheSprintPerColumn() {
        TestWorld field = new TestWorld().floor(-4, -12, 24, 12, Y - 1);
        assertEquals(10 * SPRINT, floor(field, Fixtures.model(RouteSpec.defaults()), new BlockPos(10, Y, 0)), 1e-9);
        assertEquals(10 * Math.sqrt(2) * SPRINT, floor(field, Fixtures.model(RouteSpec.defaults()), new BlockPos(10, Y, 10)), 1e-9);
    }

    @Test
    void aLakeIsPricedAtTheSwimmingPriceExceptTheFirstStepIn() {
        CostModel model = Fixtures.model(RouteSpec.defaults());
        double water = model.waterStep() + model.spec().wadePenalty();
        // 西岸两列、东岸两列(含终点那一列的下一列)与进出水各一步按疾跑,湖里十一列之间十步按游水价
        assertEquals(4 * SPRINT + 10 * water, floor(lake(), model, new BlockPos(14, Y, 0)), 1e-9);
    }

    @Test
    void parkourPricesEveryColumnAtTheSprintBecauseOneJumpCanClearWater() {
        CostModel leaping = Fixtures.model(RouteSpec.defaults().edit().parkour(true).build());
        assertEquals(14 * SPRINT, floor(lake(), leaping, new BlockPos(14, Y, 0)), 1e-9);
    }

    @Test
    void aCheaperBridgeThanSwimmingCapsTheWaterColumns() {
        CostModel bridging = Fixtures.withCobble(RouteSpec.defaults().edit().place(true).consent(false).placeCost(0).build());
        assertEquals(14 * SPRINT, floor(lake(), bridging, new BlockPos(14, Y, 0)), 1e-9);
    }

    @Test
    void aGoalOutsideTheSnapshotSeedsFromTheBoundaryColumnsWithTheirStraightLineBound() {
        TestWorld field = new TestWorld().floor(-4, -12, 90, 12, Y - 1).loadedWithin(1);
        assertEquals(60 * SPRINT, floor(field, Fixtures.model(RouteSpec.defaults()), new BlockPos(60, Y, 0)), 1e-9);
    }
}
