package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.tools.CraftingPlanner;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;

/** Deterministic inventory planning used by the crafting task's commit/recovery state machine. */
final class CraftInventoryPolicy {
    private CraftInventoryPolicy() { }

    static boolean canFitAdditions(List<ItemStack> inventorySlots, List<ItemStack> additions) {
        List<CapacitySlot<ItemStack>> slots = new ArrayList<>(inventorySlots.size());
        for (ItemStack stack : inventorySlots) {
            slots.add(stack.isEmpty()
                    ? new CapacitySlot<>(null, 0, 0)
                    : new CapacitySlot<>(stack, stack.getCount(), stack.getMaxStackSize()));
        }
        List<CapacitySlot<ItemStack>> incoming = new ArrayList<>(additions.size());
        for (ItemStack addition : additions) {
            incoming.add(new CapacitySlot<>(addition, addition.getCount(), addition.getMaxStackSize()));
        }
        return fits(slots, incoming, ItemStack::isSameItemSameTags);
    }

    static <T> boolean fits(List<CapacitySlot<T>> slots, List<CapacitySlot<T>> additions,
                            BiPredicate<T, T> sameKind) {
        List<CapacitySlot<T>> simulation = new ArrayList<>(slots);
        for (CapacitySlot<T> addition : additions) {
            int remaining = addition.count();
            for (int i = 0; i < simulation.size() && remaining > 0; i++) {
                CapacitySlot<T> slot = simulation.get(i);
                if (slot.key() != null && sameKind.test(slot.key(), addition.key())) {
                    int move = Math.min(remaining, slot.max() - slot.count());
                    simulation.set(i, new CapacitySlot<>(slot.key(), slot.count() + move, slot.max()));
                    remaining -= move;
                }
            }
            for (int i = 0; i < simulation.size() && remaining > 0; i++) {
                CapacitySlot<T> slot = simulation.get(i);
                if (slot.key() != null) continue;
                int move = Math.min(remaining, addition.max());
                simulation.set(i, new CapacitySlot<>(addition.key(), move, addition.max()));
                remaining -= move;
            }
            if (remaining > 0) return false;
        }
        return true;
    }

    static Item selectFuel(List<ItemStack> inventorySlots, int cookTime,
                           CraftingPlanner.Station station) {
        RecipeType<?> type = switch (station) {
            case BLAST_FURNACE -> RecipeType.BLASTING;
            case SMOKER -> RecipeType.SMOKING;
            default -> RecipeType.SMELTING;
        };
        Item best = null;
        int bestBurn = Integer.MAX_VALUE;
        for (ItemStack stack : inventorySlots) {
            if (stack.isEmpty()) continue;
            int burn = net.minecraftforge.common.ForgeHooks.getBurnTime(stack, type);
            if (burn > 0 && burn >= cookTime && burn < bestBurn) {
                best = stack.getItem();
                bestBurn = burn;
            }
        }
        return best;
    }

    static Item firstIngredient(List<Item> slots) {
        return slots.stream().filter(item -> item != Items.AIR).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("recipe has no ingredient"));
    }

    static Map<Item, Integer> counts(List<Item> items) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (Item item : items) if (item != Items.AIR) out.merge(item, 1, Integer::sum);
        return out;
    }

    static <T> Map<T, Integer> baselines(Collection<T> inputs, ToIntFunction<T> counter) {
        Map<T, Integer> out = new LinkedHashMap<>();
        for (T item : inputs) out.put(item, counter.applyAsInt(item));
        return out;
    }

    static <T> boolean inputsWereConsumed(Map<T, Integer> baselines, ToIntFunction<T> counter) {
        for (Map.Entry<T, Integer> entry : baselines.entrySet()) {
            if (counter.applyAsInt(entry.getKey()) < entry.getValue()) return true;
        }
        return false;
    }

    record CapacitySlot<T>(T key, int count, int max) { }
}
