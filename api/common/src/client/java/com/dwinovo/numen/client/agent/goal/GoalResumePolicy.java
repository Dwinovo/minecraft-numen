package com.dwinovo.numen.client.agent.goal;

/** Pure gate for deciding whether an active goal may enqueue its continuation. */
public final class GoalResumePolicy {

    private GoalResumePolicy() {}

    public static boolean shouldQueue(GoalState goal, boolean dead,
                                      boolean externallyDriven, boolean mcpEnabled,
                                      boolean alreadyQueued) {
        return goal != null
                && goal.isActive()
                && !dead
                && !externallyDriven
                && !mcpEnabled
                && !alreadyQueued;
    }
}
