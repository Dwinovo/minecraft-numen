package com.dwinovo.numen.tools.menu;

import com.dwinovo.numen.api.entity.BodyDelta;
import com.dwinovo.numen.api.entity.NumenPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;

/**
 * 在开着的界面里做一件事前后,她身上和界面里变了什么:背包与经验、血量({@link BodyDelta},点一下、挖、打的回执用的是同一份)和界面
 * 自己那一边的格子(附魔台里的剑上了附魔、青金石少了几块、铁砧的结果格换了名字——这些不在她的背包里,{@link BodyDelta} 看不到)。
 * 只报事实,不下结论:成没成由各界面读回来的结果说。
 */
final class Receipt {

    private final BodyDelta body;
    private final AbstractContainerMenu menu;
    private final List<String> slots;

    private Receipt(NumenPlayer her) {
        this.body = BodyDelta.open(her);
        this.menu = her.containerMenu;
        this.slots = windowSlots(her);
    }

    /** 此刻拍一张。 */
    static Receipt open(NumenPlayer her) {
        return new Receipt(her);
    }

    /** 从拍照到现在,每一条是一件真发生的事;都没变是空表。 */
    List<String> changes(NumenPlayer her) {
        List<String> facts = new ArrayList<>(body.changes(her));
        if (her.containerMenu == menu) {
            List<String> now = windowSlots(her);
            for (int i = 0; i < slots.size(); i++) {
                if (!slots.get(i).equals(now.get(i))) {
                    facts.add("window slot " + i + ": " + slots.get(i) + " -> " + now.get(i));
                }
            }
        }
        return facts;
    }

    /** 界面里不属于她背包的每一格,写成一行。 */
    private static List<String> windowSlots(NumenPlayer her) {
        List<String> out = new ArrayList<>();
        for (Slot slot : her.containerMenu.slots) {
            out.add(slot.container == her.getInventory() ? "" : stack(slot.getItem()));
        }
        return out;
    }

    /** 一叠东西写成一行:{@code minecraft:diamond_sword (named X; sharpness 2)};空的是 {@code empty}。 */
    static String stack(ItemStack stack) {
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
