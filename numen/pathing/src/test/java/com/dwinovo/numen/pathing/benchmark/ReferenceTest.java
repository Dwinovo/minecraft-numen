package com.dwinovo.numen.pathing.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 标准答案的结论:一遍精确搜索只会得出四种结论(最优、区域内无解、碰到区域边没有定论、算不动),两档节点上限只在第一档算不动时才用第二档。
 */
class ReferenceTest {

    private static final int Y = 64;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static CostModel model() {
        return Fixtures.model(RouteSpec.defaults());
    }

    private static BlockPos at(int x, int z) {
        return new BlockPos(x, Y, z);
    }

    @Test
    void aReachableGoalHasAnOptimalPrice() {
        TestWorld world = new TestWorld().floor(0, 0, 40, 6, Y - 1);
        Reference.Solved s = Reference.solve(world, model(), at(2, 3), Goals.at(at(30, 3)), 100_000);
        assertEquals(Answers.Status.OPTIMAL, s.status());
        assertTrue(s.cost() > 0);
    }

    @Test
    void aGoalSealedOffInsideTheRegionIsProvenUnsolvable() {
        TestWorld world = new TestWorld().floor(0, 0, 40, 6, Y - 1).fill(20, Y, 0, 20, Y + 2, 6, Blocks.BEDROCK.defaultBlockState());
        Reference.Solved s = Reference.solve(world, model(), at(2, 3), Goals.at(at(30, 3)), 100_000);
        assertEquals(Answers.Status.UNSOLVABLE, s.status());
    }

    /** 连通块一直通到区域边:搜完了没有目标,可边外是什么不知道——不是"无解",也不是"算不动"。 */
    @Test
    void aComponentThatReachesTheRegionEdgeHasNoVerdict() {
        TestWorld region = new TestWorld().floor(0, 0, 40, 6, Y - 1);
        TestWorld window = region.window(at(2, 3), 0);
        Reference.Solved s = Reference.solve(window, model(), at(2, 3), Goals.at(at(100, 3)), 100_000);
        assertEquals(Answers.Status.BEYOND_REGION, s.status());
    }

    @Test
    void runningOutOfNodesIsTooHardNotUnsolvable() {
        TestWorld world = new TestWorld().floor(0, 0, 100, 6, Y - 1);
        Reference.Solved s = Reference.solve(world, model(), at(2, 3), Goals.at(at(90, 3)), 20);
        assertEquals(Answers.Status.TOO_HARD, s.status());
    }

    /** 第一档撞上限的才用第二档重解;第一档解得出的不再动用第二档。 */
    @Test
    void theLargerCapIsOnlyUsedForWhatTheFirstCouldNotSolve() {
        TestWorld world = new TestWorld().floor(0, 0, 100, 6, Y - 1);
        Solver solver = new Solver(40, 100_000);
        Solver.Job near = new Solver.Job(world, model(), at(2, 3), Goals.at(at(8, 3)));
        Solver.Job far = new Solver.Job(world, model(), at(2, 3), Goals.at(at(90, 3)));
        List<Solver.Outcome> out = solver.solve(List.of(near, far), (i, o) -> { });
        assertEquals(Answers.Status.OPTIMAL, out.get(0).solved().status());
        assertEquals(1, out.get(0).tier());
        assertEquals(Answers.Status.OPTIMAL, out.get(1).solved().status());
        assertEquals(2, out.get(1).tier());
    }
}
