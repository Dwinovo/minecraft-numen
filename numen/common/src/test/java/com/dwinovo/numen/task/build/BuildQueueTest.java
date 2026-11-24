package com.dwinovo.numen.task.build;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildQueueTest {

    /** 一格:所在的层与它离她的远近。 */
    private record Cell(String name, int layer, int distance) {}

    private static final Comparator<Cell> ORDER = Comparator.comparingInt(Cell::layer).thenComparingInt(Cell::distance);

    private static List<String> drain(BuildQueue<Cell> queue) {
        List<String> out = new ArrayList<>();
        for (Cell cell = queue.next(); cell != null; cell = queue.next()) {
            out.add(cell.name());
        }
        return out;
    }

    @Test
    void lowerLayersComeFirstAndNearerCellsOfALayerBeforeFartherOnes() {
        BuildQueue<Cell> queue = new BuildQueue<>(List.of(
                new Cell("roof-near", 5, 1), new Cell("floor-far", 0, 9), new Cell("wall-far", 1, 8),
                new Cell("floor-near", 0, 2), new Cell("wall-near", 1, 3)), ORDER);

        assertEquals(List.of("floor-near", "floor-far", "wall-near", "wall-far", "roof-near"), drain(queue));
        assertTrue(queue.isEmpty());
        assertNull(queue.next());
    }

    @Test
    void aCellThatCannotStayGoesToTheEndOfTheQueue() {
        Cell torch = new Cell("torch", 0, 1);
        BuildQueue<Cell> queue = new BuildQueue<>(List.of(torch, new Cell("wall", 0, 2), new Cell("floor", 0, 3)), ORDER);

        assertEquals(torch, queue.next());
        assertTrue(queue.later(torch));

        assertEquals(List.of("wall", "floor", "torch"), drain(queue));
    }

    @Test
    void aCellIsRetriedOnlyOnce() {
        Cell torch = new Cell("torch", 0, 1);
        BuildQueue<Cell> queue = new BuildQueue<>(List.of(torch, new Cell("wall", 0, 2)), ORDER);

        assertEquals(torch, queue.next());
        assertTrue(queue.later(torch));
        assertEquals("wall", queue.next().name());
        assertEquals(torch, queue.next());
        assertFalse(queue.later(torch));
        assertTrue(queue.isEmpty());
    }

    @Test
    void theRetryMarkBelongsToTheCellNotToTheQueuePlace() {
        Cell a = new Cell("a", 0, 1);
        Cell b = new Cell("b", 0, 2);
        BuildQueue<Cell> queue = new BuildQueue<>(List.of(a, b), ORDER);

        assertEquals(a, queue.next());
        assertTrue(queue.later(a));
        assertEquals(b, queue.next());
        assertTrue(queue.later(b));
        assertEquals(a, queue.next());
        assertFalse(queue.later(a));
        assertEquals(b, queue.next());
        assertFalse(queue.later(b));
        assertTrue(queue.isEmpty());
    }
}
