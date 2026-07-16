package com.dwinovo.numen.core.task.pin;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The pin fingerprint function: an item's namespaced id string. Deliberately
 * coarse — durability/damage changes do NOT change the fingerprint (a pinned
 * tool stays pinned while it wears), but the item breaking or being replaced by
 * anything else (including empty, fingerprint {@code ""}) mismatches and expires
 * the pin ({@link IntentPins#validate}).
 */
public final class Fingerprints {

    private Fingerprints() {}

    /** {@code ""} for an empty stack — never equal to any pinned fingerprint. */
    public static String of(ItemStack stack) {
        return stack.isEmpty() ? "" : of(stack.getItem());
    }

    public static String of(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
