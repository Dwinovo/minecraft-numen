package com.dwinovo.numen.core.scaffold;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.scaffold.TemporaryScaffoldLedger;
import java.util.UUID;

public final class TemporaryScaffoldLedgerTest {
    @Test
    void verifiedRuntimeBehavior() {
        UUID companion = UUID.fromString("14abef10-dfa9-4e53-8cec-fb5527551962");
        TemporaryScaffoldLedger.clear(companion);

        boolean tracked = TemporaryScaffoldLedger.recordPlacement(
            companion,
            "minecraft:overworld",
            12,
            70,
            -4,
            "minecraft:dirt",
            "minecraft:air",
            false,
            100L
        );
        require(tracked, "ordinary path scaffolding must be tracked");
        require(TemporaryScaffoldLedger.entries(companion).size() == 1, "tracked placement missing");
        require(
            TemporaryScaffoldLedger.contains(companion, "minecraft:overworld", 12, 70, -4),
            "a tracked coordinate must be identifiable as temporary scaffolding"
        );
        require(
            !TemporaryScaffoldLedger.contains(companion, "minecraft:overworld", 13, 70, -4),
            "an unrelated coordinate must not be identified as temporary scaffolding"
        );
        TemporaryScaffoldLedger.markReason(
            companion,
            TemporaryScaffoldLedger.entries(companion).getFirst(),
            "required_by_active_path"
        );
        require(
            TemporaryScaffoldLedger.reports(companion).getFirst().reason()
                .equals("required_by_active_path"),
            "each unrecovered coordinate must carry its own reason"
        );
        TemporaryScaffoldLedger.markExplicitBuildTarget(
            companion,
            "minecraft:overworld",
            12,
            70,
            -4
        );
        require(
            TemporaryScaffoldLedger.entries(companion).isEmpty(),
            "a temporary coordinate later adopted by a build must never be reclaimed"
        );

        TemporaryScaffoldLedger.clear(companion);
        boolean buildTracked = TemporaryScaffoldLedger.recordPlacement(
            companion,
            "minecraft:overworld",
            12,
            70,
            -4,
            "minecraft:oak_planks",
            "minecraft:air",
            true,
            101L
        );
        require(!buildTracked, "explicit build target must not enter the temporary ledger");
        require(
            TemporaryScaffoldLedger.entries(companion).isEmpty(),
            "explicit build target could later be dismantled as temporary scaffolding"
        );

        TemporaryScaffoldLedger.recordPlacement(
            companion, "minecraft:overworld", 4, 70, 8,
            "minecraft:dirt", "minecraft:air", false, 102L
        );
        TemporaryScaffoldLedger.recordPlacement(
            companion, "minecraft:overworld", 4, 72, 8,
            "minecraft:dirt", "minecraft:air", false, 103L
        );
        TemporaryScaffoldLedger.recordPlacement(
            companion, "minecraft:overworld", 4, 71, 8,
            "minecraft:dirt", "minecraft:air", false, 104L
        );
        TemporaryScaffoldLedger.recordPlacement(
            companion, "minecraft:overworld", 5, 69, 8,
            "minecraft:cobblestone", "minecraft:air", false, 105L
        );
        var topmost = TemporaryScaffoldLedger.topmostEntries(companion);
        require(topmost.size() == 2, "only one block per temporary column may be reclaimed at a time");
        require(
            topmost.stream().anyMatch(entry -> entry.x() == 4 && entry.y() == 72 && entry.z() == 8),
            "the highest block in a temporary column must be reclaimed first"
        );
        require(
            topmost.stream().anyMatch(entry -> entry.x() == 5 && entry.y() == 69 && entry.z() == 8),
            "independent temporary columns must remain eligible"
        );
        TemporaryScaffoldLedger.clear(companion);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
