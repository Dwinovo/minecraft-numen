package com.dwinovo.numen.core.combat;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.combat.CombatArea;
import com.dwinovo.numen.core.combat.CombatScanMemory;
import java.util.List;
import java.util.UUID;

public final class CombatScanMemoryTest {
    @Test
    void verifiedRuntimeBehavior() {
        UUID companion = UUID.randomUUID();
        CombatArea area = CombatArea.samePlane(4.0, 70.0, 8.0, 16.0);
        CombatScanMemory memory = new CombatScanMemory(4, 1_000L);

        memory.record(companion, "minecraft:overworld", area, List.of(10, 11), 100L);

        assertTrue(
            memory.find(companion, "minecraft:overworld", List.of(11), 101L).orElseThrow().equals(area),
            "an attack subset must inherit the exact fixed area from its entity scan"
        );
        assertTrue(
            memory.find(companion, "minecraft:overworld", List.of(12), 101L).isEmpty(),
            "an id outside the scan must never inherit its combat scope"
        );

        CombatArea laterArea = CombatArea.allHeights(20.0, 70.0, 20.0, 8.0);
        memory.record(companion, "minecraft:overworld", laterArea, List.of(20), 200L);
        assertTrue(
            memory.find(companion, "minecraft:overworld", List.of(10), 201L).orElseThrow().equals(area),
            "a later unrelated scan must not overwrite the scope belonging to older ids"
        );
        assertTrue(
            memory.find(companion, "minecraft:overworld", List.of(10), 1_101L).isEmpty(),
            "expired scan ids must not be reused by a later attack"
        );
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
