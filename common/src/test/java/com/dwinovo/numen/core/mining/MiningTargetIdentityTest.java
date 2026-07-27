package com.dwinovo.numen.core.mining;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.mining.MiningTargetIdentity;
import com.dwinovo.numen.core.scaffold.TemporaryScaffoldLedger;
import java.util.UUID;

public final class MiningTargetIdentityTest {
    @Test
    void verifiedRuntimeBehavior() {
        UUID companion = UUID.fromString("375391ac-86ea-4e2e-a660-9230f417ab15");
        String dimension = "minecraft:overworld";
        TemporaryScaffoldLedger.clear(companion);

        require(
            MiningTargetIdentity.isEligible(companion, dimension, 4, 72, 9, true),
            "an unchanged requested target must remain eligible"
        );
        require(
            !MiningTargetIdentity.isEligible(companion, dimension, 4, 72, 9, false),
            "a coordinate whose block type changed must not remain eligible"
        );

        TemporaryScaffoldLedger.recordPlacement(
            companion,
            dimension,
            4,
            72,
            9,
            "minecraft:mangrove_log",
            "minecraft:air",
            false,
            200L
        );
        require(
            !MiningTargetIdentity.isEligible(companion, dimension, 4, 72, 9, true),
            "same-type path scaffolding at an old target coordinate must never be mined"
        );
        require(
            MiningTargetIdentity.isEligible(companion, dimension, 5, 72, 9, true),
            "temporary status must be coordinate-specific"
        );

        TemporaryScaffoldLedger.clear(companion);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
