package com.dwinovo.numen.core.pathing.goal;

import com.dwinovo.numen.core.pathing.moves.BlockReach;
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
    /** 生存模式的身体:站立眼高 1.62,交互距离 4.5。 */
    private static final BlockReach SURVIVAL = new BlockReach(1.62, 4.5);

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
        GoalCompiler.Compiled c = GoalCompiler.mineField(List.of(T, ore2), p -> 0, List.of(drop), SURVIVAL);
        assertTrue(c.sacred().isEmpty(),
                "no target cell may be sacred — a stance inside the target's own column"
                        + " would become unsatisfiable");
        assertTrue(c.goal().isAt(T.north()), "站旁边算到位");
        assertTrue(c.goal().isAt(T.below()), "站在它下面算到位");
        assertTrue(c.goal().isAt(drop), "drop vicinity member satisfies");
    }

    @Test
    void mineFieldPricesEachStanceWithItsDigCost() {
        BlockPos owners = T.east(12);   // 远到彼此的站位互不重叠
        GoalCompiler.Compiled c = GoalCompiler.mineField(List.of(T, owners), p -> p.equals(owners) ? 150 : 10,
                List.of(), SURVIVAL);
        // 到达价是停下那一格满足的成员里最便宜的那个;估价把价钱算进去(仍是下界)
        assertEquals(10, c.goal().arrivalCost(T.north()));
        assertEquals(150, c.goal().arrivalCost(owners.north()));
        assertTrue(c.goal().heuristic(owners.north()) <= 150,
                "站在贵的那块旁边时估价不高于它自己的到达价");
        // 内核目标看到的是同一份到达价
        assertEquals(150, c.engineGoal().arrivalCost(owners.north().getX(), owners.north().getY(),
                owners.north().getZ()));
        assertEquals(10, c.engineGoal().arrivalCost(T.north().getX(), T.north().getY(), T.north().getZ()));
    }

    @Test
    void mineFieldAdmitsCellsWithinReachWithFeetNotAbove() {
        GoalCompiler.Compiled c = GoalCompiler.mineField(List.of(T), p -> 0, List.of(), SURVIVAL);
        // 眼睛到它外框在 4.5 格内就算到位:树冠上的原木站在地上就挖得到
        assertTrue(c.goal().isAt(T.below(6)), "脚在下六格:眼睛离它底面 4.38 格");
        assertFalse(c.goal().isAt(T.below(7)), "再低一格,眼睛离它 5.38 格,够不着");
        assertTrue(c.goal().isAt(T.below(5).east(2)), "斜下方:水平 1.5、竖直 3.38,合起来 3.7 格");
        assertTrue(c.goal().isAt(T.north(4)), "同一层隔三格:3.55 格");
        assertFalse(c.goal().isAt(T.north(5)), "同一层隔四格:4.54 格");
        // 脚高于它的格不算站位:从上往下看要穿过自己的地板,更低的目标由路线走下去
        assertFalse(c.goal().isAt(T.above()), "踩在它头上不算");
        assertFalse(c.goal().isAt(T.above().north()), "高它一层站在旁边也不算");
    }

    @Test
    void mineFieldFollowsTheBodysReach() {
        GoalCompiler.Compiled creative = GoalCompiler.mineField(List.of(T), p -> 0, List.of(),
                new BlockReach(1.62, 5.0));
        assertTrue(creative.goal().isAt(T.north(5)), "创造模式交互距离 5:同一层隔四格够得着");
        assertFalse(creative.goal().isAt(T.below(7)), "竖直 5.38 格仍够不着");
    }
}
