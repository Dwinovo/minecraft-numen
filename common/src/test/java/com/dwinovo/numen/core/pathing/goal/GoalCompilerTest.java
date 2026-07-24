package com.dwinovo.numen.core.pathing.goal;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless mapping pins for {@link GoalCompiler}: each intent factory must
 * produce the right goal SHAPE, the right sacred set, and the right arrival
 * ingredients — together, from one place. Uses the pure
 * {@link GoalCompiler#block(boolean, BlockPos)} core (no {@code Level}).
 */
class GoalCompilerTest {

    private static final BlockPos T = new BlockPos(7, 70, -12);

    @Test
    void walkableCellCompilesToStandOn() {
        GoalCompiler.Compiled c = GoalCompiler.block(true, T);
        assertTrue(c.goal().isAt(T), "exact membership at the cell");
        assertFalse(c.goal().isAt(T.north()), "no neighbour satisfies standOn");
        assertTrue(c.sacred().isEmpty(), "a place to stand is not a block to protect");
        assertTrue(c.arrival().grounded());
        assertNull(c.arrival().focus(), "membership arrival, no reach focus");
        assertEquals(c.goal(), c.arrival().membership(),
                "arrival membership IS the search goal — one definition of there");
    }

    @Test
    void solidCellCompilesToInteract() {
        GoalCompiler.Compiled c = GoalCompiler.block(false, T);
        assertTrue(c.goal().isAt(T.north()), "touching cells satisfy");
        assertFalse(c.goal().isAt(T.above(2)), "no elevated cell satisfies — the pillaring pin");
        assertTrue(c.sacred().contains(T.asLong()), "the target itself is sacred");
        assertEquals(1, c.sacred().size());
        assertEquals(T, c.arrival().focus());
        assertEquals(ArrivalSpec.REACH * ArrivalSpec.REACH, c.arrival().reachSqr());
        assertFalse(c.arrival().lineOfSight(), "LOS is opt-in, matching existing tasks");
        assertTrue(c.arrival().grounded());
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
        assertFalse(c.arrival().grounded(), "chasing arrival has no ground term");
    }

    @Test
    void mineFieldMakesEveryOreSacredButNotDrops() {
        BlockPos ore2 = T.east(4);
        BlockPos drop = T.north(2);
        GoalCompiler.Compiled c = GoalCompiler.mineField(
                List.of(GoalCompiler.Stance.at(T, 2), GoalCompiler.Stance.at(ore2, 0)),
                List.of(drop));
        assertTrue(c.sacred().contains(T.asLong()));
        assertTrue(c.sacred().contains(ore2.asLong()));
        assertEquals(2, c.sacred().size(), "drops are items, not sacred blocks");
        assertTrue(c.goal().isAt(T.below()), "stance band member satisfies");
        assertTrue(c.goal().isAt(ore2), "exact stance member satisfies");
        assertFalse(c.goal().isAt(ore2.below()), "maxBelow=0 stance rejects one-below");
        assertTrue(c.goal().isAt(drop), "drop vicinity member satisfies");
    }

    @Test
    void shiftedStanceProtectsTheOreNotTheBase() {
        // A run's top block anchors its stance one lower — the SACRED cell must
        // still be the ore itself, while the feet band hangs from the base.
        GoalCompiler.Compiled c = GoalCompiler.mineField(
                List.of(new GoalCompiler.Stance(T, T.below(), 1)), List.of());
        assertTrue(c.sacred().contains(T.asLong()), "ore is sacred");
        assertFalse(c.sacred().contains(T.below().asLong()), "stance base is not");
        assertTrue(c.goal().isAt(T.below()), "band top = base");
        assertTrue(c.goal().isAt(T.below(2)), "band floor = base-1");
        assertFalse(c.goal().isAt(T), "the ore cell itself is not a stance here");
    }
}
