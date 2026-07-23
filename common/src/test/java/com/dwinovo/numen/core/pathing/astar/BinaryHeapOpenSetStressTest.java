package com.dwinovo.numen.core.pathing.astar;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.core.pathing.astar.AstarTestSupport.NEVER_GOAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 二叉堆压力测试:插入、decrease-key 与弹出序保序。 */
class BinaryHeapOpenSetStressTest {

    private static PathNode node(int i, double combinedCost) {
        PathNode n = new PathNode(i, 0, 0, NEVER_GOAL);
        n.combinedCost = combinedCost;
        return n;
    }

    @Test
    void drainsInNonDecreasingOrder() {
        Random random = new Random(42);
        BinaryHeapOpenSet heap = new BinaryHeapOpenSet();
        int count = 5000;
        for (int i = 0; i < count; i++) {
            heap.insert(node(i, random.nextDouble() * 1000));
        }
        assertEquals(count, heap.size());
        double last = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            PathNode popped = heap.removeLowest();
            assertTrue(popped.combinedCost >= last,
                    "第 " + i + " 次弹出逆序:" + popped.combinedCost + " < " + last);
            assertEquals(-1, popped.heapPosition, "弹出节点应标记为不在堆内");
            last = popped.combinedCost;
        }
        assertTrue(heap.isEmpty());
    }

    @Test
    void decreaseKeyMovesNodeForward() {
        Random random = new Random(7);
        BinaryHeapOpenSet heap = new BinaryHeapOpenSet();
        List<PathNode> nodes = new ArrayList<>();
        int count = 2000;
        for (int i = 0; i < count; i++) {
            PathNode n = node(i, 500 + random.nextDouble() * 1000);
            nodes.add(n);
            heap.insert(n);
        }
        // 随机对一半节点做 decrease-key
        for (int i = 0; i < count / 2; i++) {
            PathNode n = nodes.get(random.nextInt(count));
            n.combinedCost = random.nextDouble() * n.combinedCost;
            heap.update(n);
        }
        double last = Double.NEGATIVE_INFINITY;
        while (!heap.isEmpty()) {
            PathNode popped = heap.removeLowest();
            assertTrue(popped.combinedCost >= last);
            last = popped.combinedCost;
        }
    }

    @Test
    void decreaseKeyToMinimumSurfacesImmediately() {
        BinaryHeapOpenSet heap = new BinaryHeapOpenSet();
        List<PathNode> nodes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            PathNode n = node(i, 100 + i);
            nodes.add(n);
            heap.insert(n);
        }
        PathNode target = nodes.get(99);
        target.combinedCost = 1;
        heap.update(target);
        assertSame(target, heap.removeLowest());
    }

    @Test
    void interleavedInsertAndRemove() {
        Random random = new Random(123);
        BinaryHeapOpenSet heap = new BinaryHeapOpenSet();
        int alive = 0;
        double lastPopped = Double.NEGATIVE_INFINITY;
        for (int round = 0; round < 10000; round++) {
            if (alive == 0 || random.nextBoolean()) {
                heap.insert(node(round, random.nextDouble() * 1000));
                alive++;
                // 插入可能补进比历史弹出值更小的节点,弹出序基准重置
                lastPopped = Double.NEGATIVE_INFINITY;
            } else {
                PathNode popped = heap.removeLowest();
                assertTrue(popped.combinedCost >= lastPopped);
                lastPopped = popped.combinedCost;
                alive--;
            }
        }
        assertEquals(alive, heap.size());
    }
}
