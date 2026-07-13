package com.dwinovo.numen.core.task;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CraftInventoryPolicyTest {
    @Test void simulatesMergingAndEmptySlotsWithoutMutatingInventory() {
        var partial = new CraftInventoryPolicy.CapacitySlot<>("cobble", 60, 64);
        var empty = new CraftInventoryPolicy.CapacitySlot<String>(null, 0, 0);
        List<CraftInventoryPolicy.CapacitySlot<String>> slots = List.of(partial, empty);

        assertTrue(CraftInventoryPolicy.fits(slots,
                List.of(new CraftInventoryPolicy.CapacitySlot<>("cobble", 68, 64)), String::equals));
        assertFalse(CraftInventoryPolicy.fits(slots,
                List.of(new CraftInventoryPolicy.CapacitySlot<>("cobble", 69, 64)), String::equals));
        assertEquals(60, partial.count());
    }

    @Test void capturesAndChecksInputBaselines() {
        Map<String, Integer> baselines = CraftInventoryPolicy.baselines(
                List.of("planks"), ignored -> 4);
        assertFalse(CraftInventoryPolicy.inputsWereConsumed(baselines, ignored -> 4));
        assertTrue(CraftInventoryPolicy.inputsWereConsumed(baselines, ignored -> 3));
    }
}
