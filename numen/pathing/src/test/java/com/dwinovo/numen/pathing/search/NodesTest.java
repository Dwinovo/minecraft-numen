package com.dwinovo.numen.pathing.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 节点表(位置、已改格数、憋气档三样确定一个节点)与堆(按总价取整、再按估价排,支持降键、出过堆的节点可以重新放回)。 */
class NodesTest {

    private record Key(long pos, int used, int band) {}

    @Test
    void aNodeIsFoundByItsPositionUsedAndBandAndSurvivesGrowth() {
        Nodes nodes = new Nodes();
        Map<Key, Integer> expected = new HashMap<>();
        Random random = new Random(1);
        for (int i = 0; i < 20_000; i++) {
            Key key = new Key(BlockPos.asLong(random.nextInt(40) - 20, 60 + random.nextInt(10), random.nextInt(40) - 20),
                    random.nextInt(3), random.nextInt(4));
            int found = nodes.find(key.pos(), key.used(), key.band());
            Integer known = expected.get(key);
            if (known == null) {
                assertEquals(-1, found);
                expected.put(key, nodes.add(key.pos(), key.used(), key.band(), i));
            } else {
                assertEquals(known, found);
            }
        }
        assertEquals(expected.size(), nodes.size);
        expected.forEach((key, id) -> {
            assertEquals(id, nodes.find(key.pos(), key.used(), key.band()));
            assertEquals(key.pos(), nodes.pos[id]);
            assertEquals(key.used(), nodes.used[id]);
            assertEquals(key.band(), nodes.band[id]);
            assertEquals(Double.POSITIVE_INFINITY, nodes.g[id]);
            assertEquals(-1, nodes.parent[id]);
            assertEquals(-1, nodes.heapIndex[id]);
        });
    }

    @Test
    void theHeapPopsTheSmallestRoundedTotalThenTheSmallestEstimate() {
        Random random = new Random(2);
        Nodes nodes = new Nodes();
        OpenSet open = new OpenSet(nodes);
        List<Integer> inHeap = new ArrayList<>();
        // 每个节点此刻的总价:堆不存它,入堆与改键时传进去
        double[] total = new double[50_000];
        int ids = 0;
        for (int step = 0; step < 50_000; step++) {
            int action = random.nextInt(10);
            if (action < 5 || inHeap.isEmpty()) {
                int id = nodes.add(BlockPos.asLong(ids, 64, 0), 0, 0, random.nextInt(8));
                ids++;
                total[id] = random.nextInt(200) * 0.5 + random.nextInt(3) * 1e-9;
                open.push(id, total[id]);
                inHeap.add(id);
            } else if (action < 7) {
                // 降键:总价变低(或不变)
                int id = inHeap.get(random.nextInt(inHeap.size()));
                total[id] -= random.nextInt(4) * 0.5;
                open.pushOrUpdate(id, total[id]);
            } else if (action < 8) {
                // 出过堆的节点重新放回
                int id = random.nextInt(ids);
                if (!inHeap.contains(id)) {
                    total[id] = random.nextInt(200) * 0.5;
                    open.pushOrUpdate(id, total[id]);
                    inHeap.add(id);
                }
            } else {
                int popped = open.pop();
                long key = nodes.key[popped];
                double h = nodes.h[popped];
                for (int other : inHeap) {
                    assertTrue(nodes.key[other] > key || (nodes.key[other] == key && nodes.h[other] >= h),
                            "出堆的不是最小的:" + popped + " 比 " + other + " 大");
                }
                assertEquals(-1, nodes.heapIndex[popped]);
                inHeap.remove((Integer) popped);
            }
        }
        while (!open.isEmpty()) {
            int popped = open.pop();
            inHeap.remove((Integer) popped);
        }
        assertTrue(inHeap.isEmpty());
    }
}
