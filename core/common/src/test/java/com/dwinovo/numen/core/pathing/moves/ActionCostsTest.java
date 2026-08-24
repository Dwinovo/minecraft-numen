package com.dwinovo.numen.core.pathing.moves;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住成本常量与坠落表的关键数值:这些值是行为基准的一部分,
 * 公式被改动时必须在这里显式看到。
 */
class ActionCostsTest {

    private static final double EPS = 1e-3;

    @Test
    void fallTableHasFullRange() {
        assertEquals(4097, ActionCosts.FALL_N_BLOCKS_COST.length);
        assertEquals(0.0, ActionCosts.FALL_N_BLOCKS_COST[0]);
    }

    @Test
    void fallTableKeyValues() {
        assertEquals(5.6147, ActionCosts.FALL_N_BLOCKS_COST[1], EPS);
        assertEquals(9.4687, ActionCosts.FALL_N_BLOCKS_COST[3], EPS);
        assertEquals(17.2922, ActionCosts.FALL_N_BLOCKS_COST[10], EPS);
    }

    @Test
    void fallTableIsStrictlyIncreasing() {
        for (int i = 1; i < ActionCosts.FALL_N_BLOCKS_COST.length; i++) {
            assertTrue(ActionCosts.FALL_N_BLOCKS_COST[i] > ActionCosts.FALL_N_BLOCKS_COST[i - 1],
                    "坠落表在 n=" + i + " 处不递增");
        }
    }

    @Test
    void derivedConstants() {
        assertEquals(4.63285, ActionCosts.WALK_ONE_BLOCK_COST, EPS);
        assertEquals(3.56379, ActionCosts.SPRINT_ONE_BLOCK_COST, EPS);
        assertEquals(0.76924, ActionCosts.SPRINT_MULTIPLIER, EPS);
        assertEquals(3.16340, ActionCosts.JUMP_ONE_BLOCK_COST, EPS);
        assertEquals(3.70628, ActionCosts.WALK_OFF_BLOCK_COST, EPS);
        assertEquals(0.92657, ActionCosts.CENTER_AFTER_FALL_COST, EPS);
        assertEquals(1_000_000, ActionCosts.COST_INF);
    }
}
