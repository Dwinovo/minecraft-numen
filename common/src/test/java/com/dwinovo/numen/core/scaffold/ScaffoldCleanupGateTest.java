package com.dwinovo.numen.core.scaffold;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.scaffold.ScaffoldCleanupGate;

public final class ScaffoldCleanupGateTest {
    @Test
    void verifiedRuntimeBehavior() {
        require(
            !ScaffoldCleanupGate.chainBlocksCleanup(true, true),
            "a stale speaking-look chain must not block safe idle cleanup forever"
        );
        require(
            ScaffoldCleanupGate.chainBlocksCleanup(true, false),
            "task and survival chains must still preempt scaffold cleanup"
        );
        require(
            ScaffoldCleanupGate.canRun(false, false, false, false, false, false),
            "cleanup should run only when every task and path lane is idle"
        );
        require(
            !ScaffoldCleanupGate.canRun(false, false, true, false, false, false),
            "an active asynchronous task must block cleanup even if the brain running field is empty"
        );
        require(
            ScaffoldCleanupGate.canContinueRetreat(false, false, false),
            "cleanup-owned retreat navigation should continue while task lanes stay idle"
        );
        require(
            !ScaffoldCleanupGate.canContinueRetreat(true, false, false),
            "a new task chain must preempt cleanup-owned retreat navigation"
        );
        require(
            !ScaffoldCleanupGate.canContinueRetreat(false, true, false),
            "a newly queued task must preempt cleanup-owned retreat navigation"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
