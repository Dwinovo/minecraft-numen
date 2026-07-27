package com.dwinovo.numen.core.combat;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.combat.CombatArea;

public final class CombatAreaTest {
    @Test
    void verifiedRuntimeBehavior() {
        CombatArea area = CombatArea.samePlane(10.0, 64.0, 20.0, 12.0);

        assertTrue(area.contains(10.0, 66.0, 20.0), "two-block height variation must remain in scope");
        assertTrue(!area.contains(10.0, 66.01, 20.0), "a different vertical plane must be out of scope");
        assertTrue(area.contains(17.0, 64.0, 29.0), "a point inside the horizontal circle must remain in scope");
        assertTrue(!area.contains(19.0, 64.0, 29.0), "a square-corner point outside the radius must be rejected");
        assertTrue(CombatArea.Origin.parse(null) == CombatArea.Origin.SELF, "ordinary nearby commands must default to the AI");
        assertTrue(CombatArea.Origin.parse("owner") == CombatArea.Origin.OWNER, "owner origin must require an explicit value");

        CombatArea allHeights = CombatArea.allHeights(10.0, 64.0, 20.0, 12.0);
        assertTrue(allHeights.contains(10.0, 76.0, 20.0), "explicit all-height scope must use the scan radius vertically");
        assertTrue(!allHeights.contains(10.0, 76.01, 20.0), "all-height scope must remain bounded");
        assertTrue(CombatArea.LevelScope.parse(null) == CombatArea.LevelScope.SAME_PLANE, "ordinary nearby commands must stay on one plane");
        assertTrue(CombatArea.LevelScope.parse("all") == CombatArea.LevelScope.ALL, "all heights must require an explicit value");

        CombatArea ownerArea = CombatArea.samePlane(1.0, 80.0, 2.0, 8.0, CombatArea.Origin.OWNER);
        assertTrue(ownerArea.origin() == CombatArea.Origin.OWNER, "an explicit owner-centered scan must preserve its origin");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
