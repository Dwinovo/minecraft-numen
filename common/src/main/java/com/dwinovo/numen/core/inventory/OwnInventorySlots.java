package com.dwinovo.numen.core.inventory;

public final class OwnInventorySlots {
    public static final int HEAD = 5;
    public static final int CHEST = 6;
    public static final int LEGS = 7;
    public static final int FEET = 8;
    public static final int BACKPACK_START = 9;
    public static final int BACKPACK_END = 35;
    public static final int HOTBAR_START = 36;
    public static final int OFFHAND = 45;

    private OwnInventorySlots() {
    }

    public static int mainHand(int selectedHotbarSlot) {
        if (selectedHotbarSlot < 0 || selectedHotbarSlot > 8) {
            throw new IllegalArgumentException("selected hotbar slot must be between 0 and 8");
        }
        return HOTBAR_START + selectedHotbarSlot;
    }
}
