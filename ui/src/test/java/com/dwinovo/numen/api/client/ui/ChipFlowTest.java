package com.dwinovo.numen.api.client.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 小按钮折行:按顺序装,放不下另起一行,间隔算进宽度,过宽的独占一行。 */
class ChipFlowTest {

    @Test
    void allFitOnOneRow() {
        assertEquals(List.of(List.of(0, 1, 2)), ChipFlow.rows(new int[]{10, 10, 10}, 5, 40));
    }

    @Test
    void gapCountsTowardWidth() {
        // 10+5+10 = 25 正好放下;再加一个 5+10 就是 40 > 30
        assertEquals(List.of(List.of(0, 1), List.of(2)), ChipFlow.rows(new int[]{10, 10, 10}, 5, 30));
    }

    @Test
    void exactFitStaysOnRow() {
        assertEquals(List.of(List.of(0, 1)), ChipFlow.rows(new int[]{10, 10}, 5, 25));
    }

    @Test
    void oversizedChipTakesItsOwnRow() {
        assertEquals(List.of(List.of(0), List.of(1), List.of(2)), ChipFlow.rows(new int[]{10, 50, 10}, 5, 30));
    }

    @Test
    void nothingGivesNoRows() {
        assertEquals(List.of(), ChipFlow.rows(new int[0], 5, 30));
    }
}
