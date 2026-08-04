package com.dwinovo.numen.client.agent.goal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalStateTest {

    @Test
    void createStartsActiveAndTracksWallTime() {
        GoalState goal = GoalState.create("goal-1", "build a house", 1000);

        assertTrue(goal.isActive());
        assertEquals(0, goal.effectiveElapsedMs(1000));
        assertEquals(2000, goal.effectiveElapsedMs(3000));
    }

    @Test
    void pauseResumeCompleteAccumulatesElapsedTime() {
        GoalState goal = GoalState.create("goal-1", "mine iron", 1000);
        assertTrue(goal.pause(3000));
        assertEquals(GoalStatus.PAUSED, goal.status());
        assertEquals(2000, goal.elapsedMs());
        assertEquals(2000, goal.effectiveElapsedMs(5000));

        assertTrue(goal.resume(5000));
        assertEquals(GoalStatus.ACTIVE, goal.status());
        assertTrue(goal.complete(7000));
        assertEquals(GoalStatus.COMPLETED, goal.status());
        assertEquals(4000, goal.elapsedMs());
        assertEquals(4000, goal.effectiveElapsedMs(10_000));
        assertEquals(7000, goal.completedAtMs());
    }

    @Test
    void cancelAndCompleteAreTerminal() {
        GoalState goal = GoalState.create("goal-1", "do the thing", 1000);
        assertTrue(goal.cancel(2000));
        assertEquals(GoalStatus.CANCELLED, goal.status());
        assertFalse(goal.pause(3000));
        assertFalse(goal.resume(4000));
        assertFalse(goal.complete(5000));
        assertTrue(goal.isTerminal());
    }

    @Test
    void failedGoalCanBeResumedWithoutLosingHistory() {
        GoalState goal = GoalState.create("goal-1", "reach the village", 1000);
        assertTrue(goal.markFailed("api timeout", 2000));
        assertEquals(GoalStatus.FAILED, goal.status());
        assertEquals("api timeout", goal.lastError());

        assertTrue(goal.resume(3000));
        assertEquals(GoalStatus.ACTIVE, goal.status());
        assertEquals("", goal.lastError());
        assertTrue(goal.recordCommand("/goal resume", 3000));
        assertEquals(1, goal.history().size());
    }

    @Test
    void blockedGoalStopsElapsedTimeAndCanResume() {
        GoalState goal = GoalState.create("goal-1", "reach the village", 1000);

        assertTrue(goal.block("need a bridge", 3000));
        assertEquals(GoalStatus.BLOCKED, goal.status());
        assertEquals(2000, goal.elapsedMs());
        assertEquals("need a bridge", goal.lastError());
        assertEquals(2000, goal.effectiveElapsedMs(9000));

        assertTrue(goal.resume(9000));
        assertEquals(GoalStatus.ACTIVE, goal.status());
        assertEquals("", goal.lastError());
    }

    @Test
    void todoAndCommandHistorySurviveJsonRoundTrip() {
        GoalState goal = GoalState.create("goal-1", "build a farm", 1000);
        assertTrue(goal.setTodos(List.of(
                GoalTodo.of("clear land", "in_progress", 1000),
                GoalTodo.of("plant seeds", "pending", 1000)), 1000));
        assertTrue(goal.recordCommand("/goal add build a farm", "created", 1000));
        assertTrue(goal.setCurrentTask("clear land", 1200));

        GoalState restored = GoalState.fromJson(goal.toJson(), "goal-1");

        assertEquals("goal-1", restored.id());
        assertEquals("build a farm", restored.title());
        assertEquals(GoalStatus.ACTIVE, restored.status());
        assertEquals(2, restored.todos().size());
        assertEquals("clear land", restored.todos().get(0).content());
        assertEquals("in_progress", restored.todos().get(0).status());
        assertEquals(1, restored.history().size());
        assertEquals("/goal add build a farm", restored.history().get(0).command());
        assertEquals("clear land", restored.currentTask());
    }

    @Test
    void malformedJsonThrowsForCallersToHandle() {
        assertThrows(RuntimeException.class, () -> GoalState.fromJson("not json", "goal-1"));
    }
}
