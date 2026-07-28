package com.dwinovo.numen.core.scaffold;

import java.util.UUID;
import org.junit.jupiter.api.Test;

public final class TemporaryScaffoldTrackerTest {
    @Test
    void onlyARecentMatchingNavigationIntentEntersTheLedger() {
        UUID companion = UUID.fromString("253f1c73-0d8a-45e3-a755-93e534d91e53");
        TemporaryScaffoldTracker.clear(companion);

        boolean ordinaryTracked = TemporaryScaffoldTracker.recordObservedPlacement(
            companion, "minecraft:overworld", 12, 70, -4,
            "minecraft:dirt", "minecraft:air", false, 100L
        );
        require(!ordinaryTracked, "a placement without a navigation intent must be ignored");
        require(
            TemporaryScaffoldLedger.entries(companion).isEmpty(),
            "an ordinary AI placement must not be misclassified as temporary navigation"
        );

        TemporaryScaffoldTracker.expectNavigationPlacement(
            companion, "minecraft:overworld", 12, 70, -4,
            NavigationPlacementRole.PILLAR, 101L
        );
        boolean intendedTracked = TemporaryScaffoldTracker.recordObservedPlacement(
            companion, "minecraft:overworld", 12, 70, -4,
            "minecraft:dirt", "minecraft:air", false, 101L
        );
        require(intendedTracked, "a matching navigation placement intent must be tracked");
        require(
            TemporaryScaffoldLedger.entries(companion).size() == 1,
            "the matching navigation placement must enter the ledger exactly once"
        );
        TemporaryScaffoldTracker.clear(companion);
    }

    @Test
    void onlyPillarsEnterAutomaticCleanupWhileRoutesReportWhyTheyStay() {
        UUID companion = UUID.fromString("dd6be974-bc87-4a22-a5a6-8d5047be7170");
        TemporaryScaffoldTracker.clear(companion);
        place(companion, 4, 70, 8, NavigationPlacementRole.PILLAR, 200L);
        place(companion, 5, 70, 8, NavigationPlacementRole.BRIDGE, 201L);

        var reclaimable = TemporaryScaffoldLedger.topmostReclaimableEntries(companion);
        require(reclaimable.size() == 1, "only disposable vertical support may auto-clean");
        require(
            reclaimable.getFirst().role() == NavigationPlacementRole.PILLAR,
            "a navigation bridge must not enter pillar-style cleanup"
        );
        var bridgeReport = TemporaryScaffoldLedger.reports(companion).stream()
            .filter(report -> report.role() == NavigationPlacementRole.BRIDGE)
            .findFirst()
            .orElseThrow();
        require(
            bridgeReport.reason().equals("preserved_navigation_bridge"),
            "a preserved bridge must report why it was not reclaimed"
        );
        TemporaryScaffoldTracker.clear(companion);
    }

    private static void place(
        UUID companion,
        int x,
        int y,
        int z,
        NavigationPlacementRole role,
        long gameTime
    ) {
        TemporaryScaffoldTracker.expectNavigationPlacement(
            companion, "minecraft:overworld", x, y, z, role, gameTime
        );
        require(
            TemporaryScaffoldTracker.recordObservedPlacement(
                companion, "minecraft:overworld", x, y, z,
                "minecraft:dirt", "minecraft:air", false, gameTime
            ),
            "the intended navigation placement must be recorded"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
