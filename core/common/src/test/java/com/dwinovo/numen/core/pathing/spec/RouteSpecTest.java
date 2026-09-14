package com.dwinovo.numen.core.pathing.spec;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路线规格与按位置代价表的钉桩:出厂值就是"只走不改"的那一套,派生不改原件,
 * 位置表四栏各自独立、叠加相加、缺省 0。纯逻辑,不引导 MC。
 */
class RouteSpecTest {

    @Test
    void defaultsAreTheWalkOnlyRoute() {
        RouteSpec d = RouteSpec.defaults();
        assertEquals(RouteSpec.Alter.NONE, d.alter());
        assertFalse(d.alter().mayAlter());
        assertTrue(d.sprint());
        assertFalse(d.parkour());
        assertFalse(d.parkourPlace());
        assertTrue(d.parkourAscend());
        assertFalse(d.diagonalAscend());
        assertFalse(d.diagonalDescend());
        assertTrue(d.downward());
        assertFalse(d.climbVines());
        assertFalse(d.strictLiquidCheck());
        assertEquals(3, d.maxFallHeightNoWater());
        assertEquals(Integer.MAX_VALUE, d.alterBudget());
        assertEquals(20.0, d.placeCost());
        assertEquals(30.0, d.breakPenalty());
        assertEquals(2.0, d.jumpPenalty());
        assertEquals(3.0, d.wadePenalty());
        assertEquals(0.0, d.noise());
        assertTrue(d.positions().isEmpty());
    }

    @Test
    void defaultCellCostsExcludeOnlyTheDangerous() {
        RouteSpec d = RouteSpec.defaults();
        for (CellClass c : CellClass.values()) {
            boolean excluded = c == CellClass.LAVA || c == CellClass.HAZARD || c == CellClass.OBSTACLE;
            assertEquals(excluded ? RouteSpec.FORBID : 0.0, d.cellCost(c), c.name());
        }
        assertEquals(COST_INF, RouteSpec.FORBID);
    }

    @Test
    void derivationLeavesTheOriginalAlone() {
        RouteSpec d = RouteSpec.defaults();
        RouteSpec altered = d.withAlter(RouteSpec.Alter.NATURAL).withSprint(false)
                .withCellCost(CellClass.WATER, RouteSpec.FORBID).withJumpPenalty(12.0);
        assertEquals(RouteSpec.Alter.NONE, d.alter());
        assertTrue(d.sprint());
        assertEquals(0.0, d.cellCost(CellClass.WATER));
        assertEquals(2.0, d.jumpPenalty());

        assertTrue(altered.alter().mayAlter());
        assertFalse(altered.sprint());
        assertEquals(RouteSpec.FORBID, altered.cellCost(CellClass.WATER));
        assertEquals(12.0, altered.jumpPenalty());
        // 没动的旋钮原样带过去
        assertEquals(d.placeCost(), altered.placeCost());
        assertTrue(altered.parkourAscend());
        assertSame(RouteSpec.defaults(), d);
        assertNotSame(d, altered);
    }

    @Test
    void anyMeansAlteringToo() {
        assertTrue(RouteSpec.Alter.ANY.mayAlter());
        assertTrue(RouteSpec.Alter.NATURAL.mayAlter());
    }

    // ==================== 按位置 ====================

    @Test
    void positionColumnsAreIndependentAndDefaultToZero() {
        long a = BlockPos.asLong(1, 2, 3);
        long b = BlockPos.asLong(4, 5, 6);
        PositionCosts p = PositionCosts.builder().dig(a, COST_INF).place(b, 7.5).stand(a, 1.0).build();
        assertEquals(COST_INF, p.dig(a));
        assertEquals(0.0, p.place(a));
        assertEquals(1.0, p.stand(a));
        assertEquals(0.0, p.pass(a));
        assertEquals(7.5, p.place(b));
        assertEquals(0.0, p.dig(b));
        assertFalse(p.isEmpty());
        assertTrue(PositionCosts.EMPTY.isEmpty());
        assertEquals(0.0, PositionCosts.EMPTY.dig(a));
    }

    @Test
    void protectForbidsDigAndPlaceOnly() {
        LongSet cells = new LongOpenHashSet();
        long a = BlockPos.asLong(0, 64, 0);
        cells.add(a);
        PositionCosts p = PositionCosts.protect(cells);
        assertEquals(COST_INF, p.dig(a));
        assertEquals(COST_INF, p.place(a));
        assertEquals(0.0, p.stand(a));
        assertEquals(0.0, p.pass(a));
        assertSame(PositionCosts.EMPTY, PositionCosts.protect(new LongOpenHashSet()));
    }

    @Test
    void plusAddsPerCellAndKeepsEmptyOperandsCheap() {
        long a = BlockPos.asLong(0, 0, 0);
        PositionCosts x = PositionCosts.builder().place(a, 2.0).build();
        PositionCosts y = PositionCosts.builder().place(a, 3.0).dig(a, 1.0).build();
        PositionCosts sum = x.plus(y);
        assertEquals(5.0, sum.place(a));
        assertEquals(1.0, sum.dig(a));
        assertSame(x, x.plus(PositionCosts.EMPTY));
        assertSame(y, PositionCosts.EMPTY.plus(y));
        // 原件不受叠加影响
        assertEquals(2.0, x.place(a));
        assertEquals(0.0, x.dig(a));
    }

    @Test
    void specCarriesPositionsIntoDerivations() {
        long a = BlockPos.asLong(9, 9, 9);
        RouteSpec s = RouteSpec.defaults()
                .withPositions(PositionCosts.builder().pass(a, COST_INF).build())
                .withParkour(true);
        assertEquals(COST_INF, s.positions().pass(a));
        assertTrue(s.parkour());
    }
}
