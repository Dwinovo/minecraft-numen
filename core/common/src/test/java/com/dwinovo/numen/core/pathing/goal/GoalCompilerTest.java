package com.dwinovo.numen.core.pathing.goal;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless mapping pins for {@link GoalCompiler}: each intent factory must
 * produce the right goal SHAPE and the right sacred set — together, from one
 * place. Uses the pure {@link GoalCompiler#block(boolean, BlockPos)} core
 * (no {@code Level}).
 */
class GoalCompilerTest {

    private static final BlockPos T = new BlockPos(7, 70, -12);

    @Test
    void walkableCellCompilesToStandOn() {
        GoalCompiler.Compiled c = GoalCompiler.block(true, T);
        assertTrue(c.goal().isAt(T), "exact membership at the cell");
        assertFalse(c.goal().isAt(T.north()), "no neighbour satisfies standOn");
        assertTrue(c.sacred().isEmpty(), "a place to stand is not a block to protect");
    }

    @Test
    void solidCellCompilesToInteract() {
        GoalCompiler.Compiled c = GoalCompiler.block(false, T);
        assertTrue(c.goal().isAt(T.north()), "touching cells satisfy");
        assertFalse(c.goal().isAt(T.above(2)), "no elevated cell satisfies — the pillaring pin");
        assertTrue(c.sacred().contains(T.asLong()), "the target itself is sacred");
        assertEquals(1, c.sacred().size());
    }

    @Test
    void interactAndStandAdjacentProtectTheirTarget() {
        assertTrue(GoalCompiler.interact(T).sacred().contains(T.asLong()));
        GoalCompiler.Compiled adj = GoalCompiler.standAdjacent(T);
        assertTrue(adj.sacred().contains(T.asLong()),
                "the placement cell may not be scaffolded into");
        assertTrue(adj.goal().isAt(T.north()));
        assertFalse(adj.goal().isAt(T), "adjacent never ends IN the target cell");
    }

    @Test
    void nearUsesTheGroundBandNotTheSphere() {
        GoalCompiler.Compiled c = GoalCompiler.near(T, 3.0);
        assertTrue(c.goal().isAt(T.north(2)));
        assertFalse(c.goal().isAt(T.above(2)),
                "vicinity intent must not admit the pillar-top cell");
        assertTrue(c.sacred().isEmpty());
    }

    @Test
    void mineFieldKeepsNothingSacredSoEveryStanceStaysReachable() {
        BlockPos ore2 = T.east(4);
        BlockPos drop = T.north(2);
        GoalCompiler.Compiled c = GoalCompiler.mineField(
                List.of(GoalCompiler.Stance.at(T, 2), GoalCompiler.Stance.at(ore2, 0)),
                List.of(drop));
        // 目标格<b>不</b>设为神圣：站位常常就落在目标自己那根柱子里（树干就是
        // “脚站在原木那一格”），禁止路过砸掉会让每个站位都不可达，搜索烧光预算
        // 后反把一块本来能挖的方块拉黑。路过砸掉不亏：那一格下一轮剪枝就出表，
        // 掉落物由掉落成员收走，进度算的是背包不是挖了几下。
        assertTrue(c.sacred().isEmpty(),
                "no target cell may be sacred — a stance inside the target's own column"
                        + " would become unsatisfiable");
        assertTrue(c.goal().isAt(T.below()), "stance band member satisfies");
        assertTrue(c.goal().isAt(ore2), "exact stance member satisfies");
        assertFalse(c.goal().isAt(ore2.below()), "maxBelow=0 stance rejects one-below");
        assertTrue(c.goal().isAt(drop), "drop vicinity member satisfies");
    }

    @Test
    void shiftedStanceHangsTheFeetBandFromTheBase() {
        // A run's top block anchors its stance one lower — the feet band hangs
        // from the base, and nothing is fenced off from the route.
        GoalCompiler.Compiled c = GoalCompiler.mineField(
                List.of(new GoalCompiler.Stance(T, T.below(), 1)), List.of());
        assertTrue(c.sacred().isEmpty(), "neither the ore nor its stance base is fenced off");
        assertTrue(c.goal().isAt(T.below()), "band top = base");
        assertTrue(c.goal().isAt(T.below(2)), "band floor = base-1");
        assertFalse(c.goal().isAt(T), "the ore cell itself is not a stance here");
    }
}
