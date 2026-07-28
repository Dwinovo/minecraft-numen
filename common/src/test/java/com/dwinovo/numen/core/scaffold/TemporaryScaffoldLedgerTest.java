package com.dwinovo.numen.core.scaffold;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.scaffold.TemporaryScaffoldLedger;
import java.util.UUID;

public final class TemporaryScaffoldLedgerTest {
    @Test
    void verifiedRuntimeBehavior() {
        UUID companion = UUID.fromString("14abef10-dfa9-4e53-8cec-fb5527551962");
        TemporaryScaffoldTracker.clear(companion);

        boolean tracked = place(companion, 12, 70, -4, 100L);
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

        TemporaryScaffoldTracker.clear(companion);
        TemporaryScaffoldTracker.expectNavigationPlacement(
            companion,
            "minecraft:overworld",
            12,
            70,
            -4,
            NavigationPlacementRole.PILLAR,
            101L
        );
        boolean buildTracked = TemporaryScaffoldTracker.recordObservedPlacement(
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

        place(companion, 4, 70, 8, 102L);
        place(companion, 4, 72, 8, 103L);
        place(companion, 4, 71, 8, 104L);
        place(companion, 5, 69, 8, 105L);
        var topmost = TemporaryScaffoldLedger.topmostReclaimableEntries(companion);
        require(topmost.size() == 2, "only one block per temporary column may be reclaimed at a time");
        require(
            topmost.stream().anyMatch(entry -> entry.x() == 4 && entry.y() == 72 && entry.z() == 8),
            "the highest block in a temporary column must be reclaimed first"
        );
        require(
            topmost.stream().anyMatch(entry -> entry.x() == 5 && entry.y() == 69 && entry.z() == 8),
            "independent temporary columns must remain eligible"
        );
        TemporaryScaffoldTracker.clear(companion);
    }

    private static boolean place(UUID companion, int x, int y, int z, long gameTime) {
        TemporaryScaffoldTracker.expectNavigationPlacement(
            companion,
            "minecraft:overworld",
            x,
            y,
            z,
            NavigationPlacementRole.PILLAR,
            gameTime
        );
        return TemporaryScaffoldTracker.recordObservedPlacement(
            companion,
            "minecraft:overworld",
            x,
            y,
            z,
            "minecraft:dirt",
            "minecraft:air",
            false,
            gameTime
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
