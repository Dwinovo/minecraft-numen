package com.dwinovo.numen.bench.report;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsTest {

    private static final double EPS = 1e-4;

    @Test
    void passHatKIsTheUnbiasedAllKSucceedEstimate() {
        assertEquals(1.0, Stats.passHatK(3, 3, 3), EPS);
        assertEquals(0.0, Stats.passHatK(3, 2, 3), EPS, "2/3 的场景 pass^3 是 0");
        assertEquals(4.0 / 10.0, Stats.passHatK(5, 4, 3), EPS, "C(4,3)/C(5,3)");
        assertEquals(21.0 / 45.0, Stats.passHatK(10, 7, 2), EPS, "C(7,2)/C(10,2)");
        assertEquals(0.7, Stats.passHatK(10, 7, 1), EPS, "pass^1 就是成功率");
        assertEquals(1.0, Stats.passHatK(4, 0, 0), EPS);
        assertTrue(Double.isNaN(Stats.passHatK(2, 2, 3)), "k 比 n 大没有定义");
        assertThrows(IllegalArgumentException.class, () -> Stats.passHatK(3, 4, 1));
    }

    @Test
    void wilsonStaysInsideTheUnitIntervalAtTheEdges() {
        Stats.Interval none = Stats.wilson(0, 3, 1.96);
        assertEquals(0.0, none.low(), EPS);
        assertEquals(0.5615, none.high(), EPS);
        Stats.Interval all = Stats.wilson(3, 3, 1.96);
        assertEquals(0.4385, all.low(), EPS);
        assertEquals(1.0, all.high(), EPS);
        Stats.Interval half = Stats.wilson(5, 10, 1.96);
        assertEquals(0.5, half.estimate(), EPS);
        assertEquals(0.2366, half.low(), EPS);
        assertEquals(0.7634, half.high(), EPS);
        Stats.Interval empty = Stats.wilson(0, 0, 1.96);
        assertEquals(0.0, empty.low(), EPS);
        assertEquals(1.0, empty.high(), EPS);
    }

    @Test
    void medianOfOddAndEvenSamples() {
        assertEquals(3.0, Stats.median(List.of(5, 1, 3)), EPS);
        assertEquals(2.5, Stats.median(List.of(4, 1, 3, 2)), EPS);
        assertTrue(Double.isNaN(Stats.median(List.of())));
    }

    @Test
    void pairedBootstrapOfAConstantShiftIsThatShift() {
        double[] before = {0.0, 1.0 / 3, 2.0 / 3};
        double[] after = {1.0 / 3, 2.0 / 3, 1.0};
        Stats.Interval d = Stats.pairedBootstrap(before, after, 2000, 0.95, 7L);
        assertEquals(1.0 / 3, d.estimate(), EPS);
        assertEquals(1.0 / 3, d.low(), EPS);
        assertEquals(1.0 / 3, d.high(), EPS);
    }

    @Test
    void pairedBootstrapIsDeterministicAndBracketsTheMean() {
        double[] before = {0, 0, 1, 1, 0.5, 0};
        double[] after = {1, 0, 1, 0.5, 1, 1};
        Stats.Interval one = Stats.pairedBootstrap(before, after, 5000, 0.95, 42L);
        Stats.Interval two = Stats.pairedBootstrap(before, after, 5000, 0.95, 42L);
        assertEquals(one, two, "同样的输入与种子给同样的区间");
        assertEquals((1 + 0 + 0 - 0.5 + 0.5 + 1) / 6.0, one.estimate(), EPS);
        assertTrue(one.low() <= one.estimate() && one.estimate() <= one.high());
        assertTrue(one.low() >= -0.5 && one.high() <= 1.0, "均值的区间不会越出单个差值的范围");
    }
}
