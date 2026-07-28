package com.dwinovo.numen.core.scaffold;

/** Requires task, survival, and navigation lanes to be idle before cleanup can start. */
public final class ScaffoldCleanupGate {
    private ScaffoldCleanupGate() {
    }

    public static boolean chainBlocksCleanup(
        boolean chainRunning,
        boolean speakingLookChain
    ) {
        return chainRunning && !speakingLookChain;
    }

    public static boolean canRun(
        boolean chainRunning,
        boolean queuePending,
        boolean asyncTaskActive,
        boolean agentTurnActive,
        boolean currentPathActive,
        boolean nextPathActive,
        boolean pathSearchActive
    ) {
        return canContinueRetreat(
            chainRunning,
            queuePending,
            asyncTaskActive,
            agentTurnActive
        )
            && !currentPathActive
            && !nextPathActive
            && !pathSearchActive;
    }

    public static boolean canContinueRetreat(
        boolean chainRunning,
        boolean queuePending,
        boolean asyncTaskActive,
        boolean agentTurnActive
    ) {
        return !chainRunning && !queuePending && !asyncTaskActive && !agentTurnActive;
    }
}
