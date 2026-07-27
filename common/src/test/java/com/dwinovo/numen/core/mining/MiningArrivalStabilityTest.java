package com.dwinovo.numen.core.mining;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.mining.MiningArrivalStability;

public final class MiningArrivalStabilityTest {
    @Test
    void verifiedRuntimeBehavior() {
        MiningArrivalStability stability = new MiningArrivalStability(2);

        require(stability.shouldWait(10L, false), "first airborne arrival should wait");
        require(stability.shouldWait(10L, false), "brief airborne arrival should keep waiting");
        require(!stability.shouldWait(10L, false), "wait must end at the configured bound");

        require(stability.shouldWait(10L, false), "a new arrival attempt gets a fresh bounded wait");
        require(!stability.shouldWait(10L, true), "grounded arrival must be evaluated immediately");
        require(stability.shouldWait(10L, false), "grounding resets the arrival wait");

        require(stability.shouldWait(20L, false), "a new target starts its own wait window");
        require(stability.shouldWait(20L, false), "new target must not inherit the previous target count");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
