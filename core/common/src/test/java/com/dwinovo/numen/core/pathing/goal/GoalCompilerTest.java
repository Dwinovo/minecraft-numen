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
        GoalCompiler.Compiled c = GoalCompiler.mineField(List.of(T, ore2), List.of(drop));
        assertTrue(c.sacred().isEmpty(),
                "no target cell may be sacred — a stance inside the target's own column"
                        + " would become unsatisfiable");
        assertTrue(c.goal().isAt(T.north()), "站旁边算到位");
        assertTrue(c.goal().isAt(T.below()), "站在它下面算到位");
        assertTrue(c.goal().isAt(drop), "drop vicinity member satisfies");
    }

    @Test
    void mineFieldOnlyAdmitsCellsWhoseBodyTouchesTheOre() {
        GoalCompiler.Compiled c = GoalCompiler.mineField(List.of(T), List.of());
        assertTrue(c.goal().isAt(T.below(2)), "脚在下两格:矿贴着头顶");
        assertFalse(c.goal().isAt(T.below(3)), "再低一格就够不着了");
        assertFalse(c.goal().isAt(T.north(2)), "隔一格就不算贴着");
        // 踩在它头上不算站位:那一格是她自己的地板,挖掘层永远不碰。收进来就是
        // "导航说到位了、挖掘说这格不能挖"的死循环,实测能一直转下去。
        assertFalse(c.goal().isAt(T.above()), "踩在它头上不算 —— 脚下那格是自己的地板");
    }
}
