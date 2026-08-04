package com.dwinovo.numen.client.agent.goal;

/** Applies the production rule for turning an exhausted LLM goal turn into FAILED. */
public final class GoalFailurePolicy {

    private GoalFailurePolicy() {}

    /**
     * Mark only a goal-backed turn as failed. Ordinary chat/API failures must
     * remain recoverable without changing an unrelated active goal.
     */
    public static boolean markExhausted(GoalState goal, boolean goalTurn,
                                        String error, long nowMs) {
        if (!goalTurn || goal == null || !goal.isActive()) return false;
        return goal.markFailed(error, nowMs);
    }
}
