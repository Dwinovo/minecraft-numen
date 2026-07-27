package com.dwinovo.numen.core.mining;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.mining.ActiveMiningTargets;
import java.util.List;
import java.util.UUID;

public final class ActiveMiningTargetsTest {
    @Test
    void verifiedRuntimeBehavior() {
        UUID companion = UUID.fromString("6b0afdd0-5956-4e2e-af07-91a5eb75b482");
        ActiveMiningTargets.clear(companion);
        ActiveMiningTargets.begin(
            companion,
            List.of("minecraft:mangrove_log", "minecraft:oak_log")
        );

        require(
            ActiveMiningTargets.ids(companion).contains("minecraft:mangrove_log"),
            "the mining target type must remain visible for the full task lifetime"
        );
        ActiveMiningTargets.clear(companion);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
