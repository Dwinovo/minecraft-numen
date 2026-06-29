package com.dwinovo.numen.task;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Small adapter giving the companion task layer the {@code SimpleContainer}-style
 * inventory operations it grew up on (count / remove-by-type / add-with-leftover)
 * over the player's native {@link Inventory}. The Mob used a 27-slot
 * SimpleContainer; the player body uses its full Inventory (hotbar + main +
 * armor + offhand), all reachable via {@link Inventory#getContainerSize()} /
 * {@link Inventory#getItem(int)}.
 */
public final class PlayerInv {

    private PlayerInv() {}

    /** Total count of {@code item} across the whole inventory. */
    public static int count(Inventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) n += s.getCount();
        }
        return n;
    }

    /** First slot holding {@code item}, or -1. */
    public static int findSlot(Inventory inv, Item item) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) return i;
        }
        return -1;
    }

    /** Remove up to {@code max} of {@code item}; returns how many were removed. */
    public static int remove(Inventory inv, Item item, int max) {
        int removed = 0;
        for (int i = 0; i < inv.getContainerSize() && removed < max; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !s.is(item)) continue;
            int take = Math.min(s.getCount(), max - removed);
            s.shrink(take);
            removed += take;
        }
        inv.setChanged();
        return removed;
    }

    /**
     * Add {@code stack} to the inventory; returns whatever didn't fit (empty if
     * all fit). Mirrors {@code SimpleContainer.addItem}'s leftover contract over
     * {@link Inventory#add(ItemStack)} (which mutates the stack down by what fit).
     */
    public static ItemStack add(Inventory inv, ItemStack stack) {
        inv.add(stack);
        return stack;   // Inventory.add consumed what fit; remainder stays here
    }
}
