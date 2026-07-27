package com.dwinovo.numen.core.inventory;

public final class PickupQuantity {
    private PickupQuantity() {
    }

    public static int fromInventoryDelta(int before, int after) {
        return Math.max(0, after - before);
    }
}
