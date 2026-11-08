package com.dwinovo.numen.pathing.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 报表的统计:Wilson 区间、分位数。 */
class StatsTest {

    @Test
    void wilsonIntervalMatchesTheKnownValues() {
        // 8/10:Wilson 95% 区间 [0.4902, 0.9433]
        double[] w = Stats.wilson(8, 10);
        assertEquals(0.4902, w[0], 1e-3);
        assertEquals(0.9433, w[1], 1e-3);
    }

    @Test
    void wilsonIntervalStaysInsideZeroAndOneAndKnowsNothingWithoutData() {
        double[] all = Stats.wilson(40, 40);
        assertEquals(1.0, all[1], 1e-12);
        assertTrue(all[0] > 0.9 && all[0] < 1.0, "全成功时下限也不到 1:" + all[0]);
        double[] none = Stats.wilson(0, 40);
        assertEquals(0.0, none[0], 1e-12);
        assertEquals(0.0, Stats.wilson(0, 0)[0]);
        assertEquals(1.0, Stats.wilson(0, 0)[1]);
    }

    @Test
    void percentilesAreNearestRank() {
        List<Double> values = List.of(5.0, 1.0, 4.0, 2.0, 3.0, 10.0, 9.0, 8.0, 7.0, 6.0);
        assertEquals(5.0, Stats.percentile(values, 0.5));
        assertEquals(9.0, Stats.percentile(values, 0.9));
        assertEquals(10.0, Stats.percentile(values, 0.99));
        assertEquals(10.0, Stats.percentile(values, 1.0));
        assertEquals(1.0, Stats.percentile(values, 0.0));
        assertTrue(Double.isNaN(Stats.percentile(List.of(), 0.5)));
    }
}
