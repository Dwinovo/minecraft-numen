package com.dwinovo.numen.client.agent.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalFailurePolicyTest {

    @Test
    void exhaustedGoalTurnMarksActiveGoalFailedAndKeepsError() {
        GoalState goal = GoalState.create("goal-1", "reach the village", 1000);

        assertTrue(GoalFailurePolicy.markExhausted(goal, true, "api timeout", 3000));
        assertEquals(GoalStatus.FAILED, goal.status());
        assertEquals("api timeout", goal.lastError());
        assertEquals(2000, goal.elapsedMs());
        assertEquals(0, goal.lastStartedAtMs());
    }

    @Test
    void ordinaryChatFailureDoesNotFailAnUnrelatedActiveGoal() {
        GoalState goal = GoalState.create("goal-1", "reach the village", 1000);

        assertFalse(GoalFailurePolicy.markExhausted(goal, false, "api timeout", 3000));
        assertEquals(GoalStatus.ACTIVE, goal.status());
        assertEquals("", goal.lastError());
    }

    @Test
    void failedGoalCanResumeAndClearTheError() {
        GoalState goal = GoalState.create("goal-1", "reach the village", 1000);
        assertTrue(GoalFailurePolicy.markExhausted(goal, true, "empty response", 2000));

        assertTrue(goal.resume(4000));
        assertEquals(GoalStatus.ACTIVE, goal.status());
        assertEquals("", goal.lastError());
    }

    @Test
    void noGoalAndTerminalGoalsCannotBecomeFailed() {
        GoalState none = GoalState.none("goal-1");
        assertFalse(GoalFailurePolicy.markExhausted(none, true, "api timeout", 1000));
        assertFalse(none.markFailed("api timeout", 1000));

        GoalState completed = GoalState.create("goal-2", "done", 1000);
        assertTrue(completed.complete(2000));
        assertFalse(GoalFailurePolicy.markExhausted(completed, true, "api timeout", 3000));
        assertEquals(GoalStatus.COMPLETED, completed.status());
    }
}
