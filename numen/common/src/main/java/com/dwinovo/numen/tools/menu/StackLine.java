package com.dwinovo.numen.tools.menu;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;

/**
 * 界面里一格放着的一叠,写成一行:{@code minecraft:diamond_sword (named X; sharpness 2)},带数量;空的是 {@code empty}。
 * 各种界面读回来的结果用的是这同一个写法。
 */
public final class StackLine {

    private StackLine() {}

    public static String of(ItemStack stack) {
        if (stack.isEmpty()) {
            return "empty";
        }
        List<String> details = new ArrayList<>();
        if (stack.has(DataComponents.CUSTOM_NAME)) {
            details.add("named " + stack.getHoverName().getString());
        }
        enchantments(stack.get(DataComponents.ENCHANTMENTS), details);
        enchantments(stack.get(DataComponents.STORED_ENCHANTMENTS), details);
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + (stack.getCount() > 1 ? " x" + stack.getCount() : "")
                + (details.isEmpty() ? "" : " (" + String.join("; ", details) + ")");
    }

    private static void enchantments(ItemEnchantments enchantments, List<String> into) {
        if (enchantments == null) {
            return;
        }
        for (Holder<Enchantment> each : enchantments.keySet()) {
            into.add(each.unwrapKey().map(key -> key.location().getPath()).orElse("?") + " " + enchantments.getLevel(each));
        }
    }
}
