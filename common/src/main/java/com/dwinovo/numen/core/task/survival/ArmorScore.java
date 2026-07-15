package com.dwinovo.numen.core.task.survival;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * PURE armor-piece valuation for the autonomous armor chain: everything here reads
 * only the {@link ItemStack}'s data components (attribute modifiers, enchantments)
 * — no world, no entity — so two candidate pieces can be ranked headless.
 *
 * <h2>Score composition</h2>
 * <pre>  score = armor + toughness                 (attribute modifiers active in the slot)
 *        + 3 × main protection line          (Protection; on LEGS: Blast Protection)
 *        + 1 × each other protection line    (blast / fire / projectile, or protection on LEGS)
 *        + 1 × Unbreaking + 2 × Mending      (longevity)</pre>
 *
 * <p>Base points and enchantments trade off deliberately: a plain diamond
 * chestplate scores 8+2&nbsp;=&nbsp;10, while a Protection&nbsp;IV iron one scores
 * 6+12&nbsp;=&nbsp;18 and wins — Protection&nbsp;IV reduces more incoming damage
 * than the two missing armor points. Leggings weight Blast Protection as the main
 * line because creeper blasts are the dominant burst-damage source in ordinary
 * overworld play.
 */
public final class ArmorScore {

    private ArmorScore() {}

    /** Weight of the slot's main protection line (Protection; Blast Protection on LEGS). */
    public static final int MAIN_PROTECTION_WEIGHT = 3;
    /** Weight of Mending — a self-repairing piece effectively never wears out. */
    public static final int MENDING_WEIGHT = 2;

    /**
     * Full valuation of one piece for one armor slot. An empty stack scores 0, so
     * "any real armor beats a bare slot" falls out of the arithmetic.
     */
    public static float score(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) return 0.0f;
        return attributeScore(stack, slot) + enchantScore(
                slot == EquipmentSlot.LEGS,
                level(stack, Enchantments.PROTECTION),
                level(stack, Enchantments.BLAST_PROTECTION),
                level(stack, Enchantments.FIRE_PROTECTION),
                level(stack, Enchantments.PROJECTILE_PROTECTION),
                level(stack, Enchantments.UNBREAKING),
                level(stack, Enchantments.MENDING));
    }

    /**
     * Pure enchantment arithmetic (headless-testable): the slot's main protection
     * line at {@link #MAIN_PROTECTION_WEIGHT}×, the other protection lines at 1×,
     * plus longevity ({@code unbreaking} at 1×, {@code mending} at
     * {@link #MENDING_WEIGHT}×). On leggings ({@code legs}) Blast Protection is the
     * main line and plain Protection drops to 1×.
     */
    public static int enchantScore(boolean legs, int protection, int blast, int fire,
                                   int projectile, int unbreaking, int mending) {
        int main = legs ? blast : protection;
        int rest = (legs ? protection : blast) + fire + projectile;
        return MAIN_PROTECTION_WEIGHT * main + rest + unbreaking + MENDING_WEIGHT * mending;
    }

    /**
     * Whether the piece carries Curse of Binding. A CANDIDATE with the curse is
     * never equipped (it could not be taken off again); a WORN piece with the curse
     * makes its slot untouchable (the swap would be rejected anyway).
     */
    public static boolean isCursedOn(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ItemEnchantments ench = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (Object2IntMap.Entry<Holder<Enchantment>> e : ench.entrySet()) {
            if (e.getKey().is(Enchantments.BINDING_CURSE)) return true;
        }
        return false;
    }

    /**
     * The equipment slot this stack equips into ({@code null} when it is not
     * equipable at all) — item-level data only, no entity involved.
     */
    public static EquipmentSlot slotOf(ItemStack stack) {
        Equipable equipable = Equipable.get(stack);
        return equipable == null ? null : equipable.getEquipmentSlot();
    }

    /**
     * Sum of the piece's ARMOR and ARMOR_TOUGHNESS modifiers that are active in
     * {@code slot}. Every operation contributes its raw amount: {@code ADD_VALUE}
     * is exact; for the two multiplied operations an exact expansion needs the live
     * attribute total of a specific entity, which a pure function does not have —
     * and a player's BASE for both attributes is 0, so expanding against base
     * would erase the bonus entirely. Treating the multiplier's amount as a flat
     * add keeps such a (modded) piece visible to the ranking at roughly the right
     * magnitude; vanilla armor only ever uses {@code ADD_VALUE}, where this is exact.
     */
    private static float attributeScore(ItemStack stack, EquipmentSlot slot) {
        ItemAttributeModifiers mods = stack.getOrDefault(
                DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double sum = 0.0;
        for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
            if (!e.slot().test(slot)) continue;
            if (e.attribute().is(Attributes.ARMOR) || e.attribute().is(Attributes.ARMOR_TOUGHNESS)) {
                sum += e.modifier().amount();
            }
        }
        return (float) sum;
    }

    /** Level of {@code key} on the stack (0 when absent), read straight off the component. */
    private static int level(ItemStack stack, ResourceKey<Enchantment> key) {
        ItemEnchantments ench = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (Object2IntMap.Entry<Holder<Enchantment>> e : ench.entrySet()) {
            if (e.getKey().is(key)) return e.getIntValue();
        }
        return 0;
    }
}
