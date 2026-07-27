package com.dwinovo.numen.core.mining;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.mining.BlockTargetLifecycle;

public final class BlockTargetLifecycleTest {
    @Test
    void verifiedRuntimeBehavior() {
        require(
            BlockTargetLifecycle.isLost(true, true, true, false, false),
            "an exact block target that becomes air must stop navigation"
        );
        require(
            BlockTargetLifecycle.isLost(true, false, true, false, true),
            "a newly-created goto must not occupy a target that was just mined"
        );
        require(
            !BlockTargetLifecycle.isLost(true, false, true, false, false),
            "an ordinary exact air coordinate must remain a valid destination"
        );
        require(
            BlockTargetLifecycle.isLost(true, false, false, true, true),
            "temporary scaffolding must not revive a target that was already mined"
        );
        require(
            !BlockTargetLifecycle.isLost(true, false, false, false, true),
            "a real block newly placed at the coordinate may be approached normally"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
