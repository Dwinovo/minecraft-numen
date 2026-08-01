package com.dwinovo.numen.core.task;

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

    /**
     * 建造能动用的格数:快捷栏 + 主背包,<b>不含盔甲栏与副手</b>。
     *
     * <p>建造那一族(报价、逐格闸门、实扣)必须共用这一个数,而不是各写各的循环。这条
     * 口径分岔过两次,症状一模一样:一整叠木板放在副手,数 41 格的那一方说"料够了",
     * 数 36 格的那一方每格都判缺料——玩家看着手里那叠木板,而我们两张嘴说两样话。
     *
     * <p>为什么是 36 而不是 41:盔甲是穿在身上的,副手是她另一只手里握着的东西(演出要
     * 用),都不是"备料"。把它们算进可用材料,等于说她会拆自己的胸甲去砌墙。
     */
    public static final int BUILDABLE_SLOTS = 36;

    /** Total count of {@code item} across the whole inventory (armor + offhand included). */
    public static int count(Inventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) n += s.getCount();
        }
        return n;
    }

    /** 建造口径的存量:只数 {@link #BUILDABLE_SLOTS} 格。报价与实扣共用这一个。 */
    public static int buildableCount(Inventory inv, Item item) {
        int limit = Math.min(BUILDABLE_SLOTS, inv.getNonEquipmentItems().size());
        int n = 0;
        for (int i = 0; i < limit; i++) {
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
