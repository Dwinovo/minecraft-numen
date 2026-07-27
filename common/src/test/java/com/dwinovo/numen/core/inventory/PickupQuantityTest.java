package com.dwinovo.numen.core.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PickupQuantityTest {

    @Test
    void reportsTheCompleteInventoryGainIncludingMergedStacks() {
        assertEquals(4, PickupQuantity.fromInventoryDelta(71, 75));
    }

    @Test
    void neverReportsInventoryLossAsACollectedItem() {
        assertEquals(0, PickupQuantity.fromInventoryDelta(75, 74));
    }
}
