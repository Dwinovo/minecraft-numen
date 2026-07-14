package com.dwinovo.numen.core.pathing.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2 — the heap against a brute-force reference model over a long random
 * insert / decrease-key / removeLowest sequence: every pop must match the
 * model's argmin. Keys are random doubles, so ties have ~zero probability and
 * argmin identity is well-defined.
 */
class OpenSetTest {

    /** Reference model: plain list, min by full scan. */
    private static Node<String> modelMin(List<Node<String>> model) {
        Node<String> best = model.get(0);
        for (Node<String> n : model) {
            if (n.f < best.f) {
                best = n;
            }
        }
        return best;
    }

    @Test
    void randomOpsMatchReferenceModel() {
        Random random = new Random(424242L);
        OpenSet<String> heap = new OpenSet<>();
        List<Node<String>> model = new ArrayList<>();
        long nextPos = 0;
        int pops = 0;

        for (int op = 0; op < 20_000; op++) {
            int kind = random.nextInt(10);
            if (kind < 5 || model.isEmpty()) {
                // insert
                Node<String> node = new Node<>(nextPos++, 0.0);
                node.f = random.nextDouble() * 1000.0;
                heap.insert(node);
                model.add(node);
            } else if (kind < 8) {
                // decrease-key on a random open node
                Node<String> node = model.get(random.nextInt(model.size()));
                node.f -= random.nextDouble() * 100.0;
                heap.update(node);
            } else {
                // removeLowest, checked against model + peek
                Node<String> expected = modelMin(model);
                assertSame(expected, heap.peekLowest(), "peekLowest at op " + op);
                Node<String> popped = heap.removeLowest();
                assertSame(expected, popped, "removeLowest at op " + op);
                assertEquals(-1, popped.heapIndex, "popped node must leave the heap");
                model.remove(popped);
                pops++;
            }
            assertEquals(model.size(), heap.size(), "size drift at op " + op);
        }

        // drain fully — remaining pops must come out in exact ascending-f model order
        double lastF = Double.NEGATIVE_INFINITY;
        while (!model.isEmpty()) {
            Node<String> expected = modelMin(model);
            Node<String> popped = heap.removeLowest();
            assertSame(expected, popped);
            assertTrue(popped.f >= lastF, "pops must be non-decreasing in f");
            lastF = popped.f;
            model.remove(popped);
            pops++;
        }
        assertTrue(heap.isEmpty());
        assertTrue(pops > 1000, "the sequence must actually exercise pops");
    }

    @Test
    void decreaseKeyToMinimumPopsFirst() {
        OpenSet<String> heap = new OpenSet<>();
        Node<String> a = new Node<>(1, 0);
        Node<String> b = new Node<>(2, 0);
        Node<String> c = new Node<>(3, 0);
        a.f = 10;
        b.f = 20;
        c.f = 30;
        heap.insert(a);
        heap.insert(b);
        heap.insert(c);

        c.f = 5; // decrease below everything
        heap.update(c);

        assertSame(c, heap.removeLowest());
        assertSame(a, heap.removeLowest());
        assertSame(b, heap.removeLowest());
        assertTrue(heap.isEmpty());
    }
}
