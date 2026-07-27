package com.dwinovo.numen.core.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OwnInventorySlotsTest {

    @Test
    void exposesTheNamedEquipmentAndHandSlots() {
        assertEquals(5, OwnInventorySlots.HEAD);
        assertEquals(6, OwnInventorySlots.CHEST);
        assertEquals(7, OwnInventorySlots.LEGS);
        assertEquals(8, OwnInventorySlots.FEET);
        assertEquals(45, OwnInventorySlots.OFFHAND);
        assertEquals(40, OwnInventorySlots.mainHand(4));
    }

    @Test
    void rejectsInvalidSelectedHotbarSlots() {
        assertThrows(IllegalArgumentException.class, () -> OwnInventorySlots.mainHand(9));
    }
}
