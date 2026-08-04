package com.dwinovo.numen.client.agent.goal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalTodoHarvestTest {

    @Test
    void parsesSuccessfulTodoSnapshot() {
        Optional<List<GoalTodo>> parsed = GoalTodoHarvest.parse(
                "{\"success\":true,\"todos\":["
                        + "{\"content\":\"first\",\"status\":\"in_progress\"},"
                        + "{\"content\":\"second\",\"status\":\"pending\"}"
                        + "]}",
                1000L);

        assertTrue(parsed.isPresent());
        assertEquals(2, parsed.get().size());
        assertEquals("first", parsed.get().get(0).content());
        assertEquals("in_progress", parsed.get().get(0).status());
        assertEquals(1000L, parsed.get().get(0).createdAtMs());
    }

    @Test
    void emptySuccessfulSnapshotIsValid() {
        Optional<List<GoalTodo>> parsed = GoalTodoHarvest.parse(
                "{\"success\":true,\"todos\":[]}", 1000L);

        assertTrue(parsed.isPresent());
        assertTrue(parsed.get().isEmpty());
    }

    @Test
    void failedSnapshotIsIgnored() {
        Optional<List<GoalTodo>> parsed = GoalTodoHarvest.parse(
                "{\"success\":false,\"todos\":[{\"content\":\"x\",\"status\":\"pending\"}]}",
                1000L);

        assertTrue(parsed.isEmpty());
    }

    @Test
    void malformedOrMissingTodosAreIgnored() {
        assertTrue(GoalTodoHarvest.parse("not json", 1000L).isEmpty());
        assertTrue(GoalTodoHarvest.parse("{\"success\":true}", 1000L).isEmpty());
        assertTrue(GoalTodoHarvest.parse(null, 1000L).isEmpty());
    }
}
