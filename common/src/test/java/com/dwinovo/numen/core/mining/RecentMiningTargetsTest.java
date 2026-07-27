package com.dwinovo.numen.core.mining;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.mining.RecentMiningTargets;
import java.util.UUID;

public final class RecentMiningTargetsTest {
    @Test
    void verifiedRuntimeBehavior() {
        UUID companion = UUID.fromString("2198ab3e-61a0-42b1-b564-a9d52b35daff");
        RecentMiningTargets.clear(companion);
        RecentMiningTargets.record(companion, "minecraft:overworld", 8, 74, -3, 100L);

        require(
            RecentMiningTargets.contains(companion, "minecraft:overworld", 8, 74, -3, 101L),
            "a verified removal must be visible to the immediate fallback task"
        );
        require(
            !RecentMiningTargets.contains(companion, "minecraft:overworld", 8, 74, -3, 701L),
            "a mined coordinate must expire so normal future movement is not blocked"
        );
        RecentMiningTargets.clear(companion);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
