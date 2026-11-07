package com.dwinovo.numen.pathing.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** 抬高估价(LSS-LRTA* 的学习一步):从边界往里的最短路;不经搜索。 */
class LearningTest {

    /** 一条线 0-1-2-3,每步价 1,0 到 2 展开过,3 是边界,各节点的估价都很低(像是死路里估价看不见墙)。 */
    @Test
    void aDeadEndLineIsRaisedToTheDistanceToTheBoundaryPlusItsEstimate() {
        boolean[] expanded = {true, true, true, false};
        double[] h = {0, 0, 0, 5};
        int[] from = {0, 1, 2};
        int[] to = {1, 2, 3};
        double[] cost = {1, 1, 1};
        double[] raised = Learning.raise(expanded, expanded, h, 4, from, to, cost, 3);
        assertArrayEquals(new double[] {8, 7, 6, 5}, raised, 1e-9);
    }

    @Test
    void theCheapestWayOutToTheBoundaryCounts() {
        // 0 能走到边界 2(估价 10,一步)或边界 3(估价 1,两步经过 1)
        boolean[] expanded = {true, true, false, false};
        double[] h = {0, 0, 10, 1};
        double[] raised = Learning.raise(expanded, expanded, h, 4, new int[] {0, 0, 1}, new int[] {2, 1, 3}, new double[] {1, 1, 1}, 3);
        assertEquals(3, raised[0], 1e-9, "经 1 走到 3:1 + 1 + 1");
        assertEquals(2, raised[1], 1e-9);
    }

    @Test
    void anEstimateIsNeverLowered() {
        boolean[] expanded = {true, false};
        double[] raised = Learning.raise(expanded, expanded, new double[] {50, 3}, 2, new int[] {0}, new int[] {1}, new double[] {1}, 1);
        assertEquals(50, raised[0], "走到边界只要 4,原来的估价 50 更高,不降");
    }

    @Test
    void withNoBoundaryEveryExpandedNodeIsUnreachable() {
        boolean[] expanded = {true, true};
        double[] raised = Learning.raise(expanded, expanded, new double[] {0, 0}, 2, new int[] {0, 1}, new int[] {1, 0}, new double[] {1, 1}, 2);
        assertTrue(Double.isInfinite(raised[0]) && Double.isInfinite(raised[1]));
    }

    /** 随机图上与逐边松弛到不动点的朴素解法比:边的落点可以是展开过的、边界,也可以是自己有往外的边的没展开节点;估价不一致。 */
    @Test
    void matchesRelaxingEveryEdgeUntilNothingChanges() {
        java.util.Random random = new java.util.Random(7);
        for (int round = 0; round < 200; round++) {
            int nodes = 2 + random.nextInt(40);
            boolean[] expanded = new boolean[nodes];
            double[] h = new double[nodes];
            for (int n = 0; n < nodes; n++) {
                expanded[n] = random.nextInt(3) > 0;
                h[n] = random.nextInt(10) == 0 ? Double.POSITIVE_INFINITY : random.nextInt(30);
            }
            int edges = random.nextInt(nodes * 3);
            int[] from = new int[edges];
            int[] to = new int[edges];
            double[] cost = new double[edges];
            for (int e = 0; e < edges; e++) {
                from[e] = random.nextInt(nodes);
                to[e] = random.nextInt(nodes);
                cost[e] = 1 + random.nextInt(5);
            }
            boolean[] stepped = Arrays.copyOf(expanded, nodes);
            for (int e = 0; e < edges; e++) {
                stepped[from[e]] = true;
            }
            double[] expected = new double[nodes];
            for (int n = 0; n < nodes; n++) {
                expected[n] = expanded[n] ? Double.POSITIVE_INFINITY : h[n];
            }
            for (boolean changed = true; changed; ) {
                changed = false;
                for (int e = 0; e < edges; e++) {
                    if (expected[to[e]] + cost[e] < expected[from[e]]) {
                        expected[from[e]] = expected[to[e]] + cost[e];
                        changed = true;
                    }
                }
            }
            for (int n = 0; n < nodes; n++) {
                if (expanded[n]) {
                    expected[n] = Math.max(expected[n], h[n]);
                }
            }
            assertArrayEquals(expected, Learning.raise(expanded, stepped, h, nodes, from, to, cost, edges), 1e-9, "第 " + round + " 轮");
        }
    }
}
