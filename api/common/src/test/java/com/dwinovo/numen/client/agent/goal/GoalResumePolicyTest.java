package com.dwinovo.numen.client.agent.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalResumePolicyTest {

    @Test
    void activeGoalQueuesWhenAllControlGatesAreOpen() {
        GoalState goal = GoalState.create("goal-1", "build a house", 1000);

        assertTrue(GoalResumePolicy.shouldQueue(goal, false, false, false, false));
    }

    @Test
    void resumeIsBlockedByDeadExternalMcpOrDuplicateState() {
        GoalState goal = GoalState.create("goal-1", "build a house", 1000);

        assertFalse(GoalResumePolicy.shouldQueue(goal, true, false, false, false));
        assertFalse(GoalResumePolicy.shouldQueue(goal, false, true, false, false));
        assertFalse(GoalResumePolicy.shouldQueue(goal, false, false, true, false));
        assertFalse(GoalResumePolicy.shouldQueue(goal, false, false, false, true));
    }

    @Test
    void pausedFailedTerminalAndMissingGoalsNeverResume() {
        GoalState paused = GoalState.create("goal-1", "build a house", 1000);
        paused.pause(2000);
        assertFalse(GoalResumePolicy.shouldQueue(paused, false, false, false, false));

        GoalState failed = GoalState.create("goal-2", "build a farm", 1000);
        failed.markFailed("timeout", 2000);
        assertFalse(GoalResumePolicy.shouldQueue(failed, false, false, false, false));

        GoalState completed = GoalState.create("goal-3", "done", 1000);
        completed.complete(2000);
        assertFalse(GoalResumePolicy.shouldQueue(completed, false, false, false, false));
        assertFalse(GoalResumePolicy.shouldQueue(null, false, false, false, false));
    }
}
