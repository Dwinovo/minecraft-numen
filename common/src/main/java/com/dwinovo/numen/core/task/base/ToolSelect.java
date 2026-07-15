package com.dwinovo.numen.core.task.base;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The two "swap the best implement into the main hand" helpers, unified from the
 * byte-for-byte duplicated scans that lived in {@code BlockDigger} and
 * {@code HuntCompanionTask.switchToBestWeapon} (both now call in here).
 *
 * <p>Both scan the WHOLE inventory (not just the hotbar) and swap via
 * {@link NumenPlayer#holdInHand(int)} — deliberately wider than a
 * hotbar-only scan, and kept consistent with the pathing cost model
 * ({@code NavContext.scanBestTool}) so the planned break cost matches the tool
 * actually used.
 *
 * <p>Intent pin (constitution §5): a MAINHAND pin — the trace of an explicit
 * {@code equip_item} — makes both swaps a no-op: the explicitly-held item is
 * not replaced by a "better" one. The pin expires by fingerprint the moment the
 * item breaks or otherwise leaves the hand, and the task-idle edge releases it.
 */
public final class ToolSelect {

    private ToolSelect() {}

    /**
     * Hold the best implement for breaking {@code state}: among tools that beat
     * the bare hand, one that actually harvests the block (correct tier when the
     * block gates its drops) outranks a merely faster one — a wooden pick "mines"
     * iron ore quickly but yields nothing, so it must not win on speed alone.
     * Within a pool, highest {@link ItemStack#getDestroySpeed} wins.
     */
    public static void holdBestTool(NumenPlayer p, BlockState state) {
        if (handPinned(p)) return;   // explicit hold — don't swap it away
        Inventory inv = p.getInventory();
        boolean tierGated = state.requiresCorrectToolForDrops();
        int harvest = -1, any = -1;
        float harvestSpeed = 1.0f, anySpeed = 1.0f;   // bare-hand baseline
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            float spd = s.getDestroySpeed(state);
            if (spd <= 1.0f) continue;
            if (!tierGated || s.isCorrectToolForDrops(state)) {
                if (spd > harvestSpeed) { harvestSpeed = spd; harvest = i; }
            } else if (spd > anySpeed) {
                anySpeed = spd;
                any = i;
            }
        }
        int best = harvest >= 0 ? harvest : any;
        if (best >= 0) {
            p.holdInHand(best);
        }
    }

    /**
     * Hold the highest melee-attack-damage weapon. Mirrors the original
     * {@code HuntCompanionTask.switchToBestWeapon} scan.
     */
    public static void holdBestWeapon(NumenPlayer p) {
        if (handPinned(p)) return;   // explicit hold — don't swap it away
        Inventory inv = p.getInventory();
        int best = -1;
        double bestDmg = 0.0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            double d = weaponDamage(s);
            if (d > bestDmg) {
                bestDmg = d;
                best = i;
            }
        }
        if (best >= 0) {
            p.holdInHand(best);
        }
    }

    /**
     * Is the main hand pinned by explicit intent AND still holding the pinned
     * item? A scan-time fingerprint check ({@code IntentPins.validate}), so a
     * stale hand pin (item broke / was swapped by a survival chain) expires on
     * this very call and the swap proceeds normally.
     */
    private static boolean handPinned(NumenPlayer p) {
        return com.dwinovo.numen.core.task.pin.IntentPinsData.pinsFor(p).validate(
                com.dwinovo.numen.core.task.pin.IntentPins.SLOT_MAINHAND,
                com.dwinovo.numen.core.task.pin.Fingerprints.of(p.getMainHandItem()));
    }

    /**
     * The flat main-hand attack damage an item grants — the sum of {@code ADD_VALUE}
     * modifiers on {@link Attributes#ATTACK_DAMAGE} that apply in the main hand.
     * Sword/axe carry the largest; a block or food scores 0 so it is never chosen
     * over a real weapon. Identical to {@code HuntCompanionTask.weaponDamage}.
     */
    private static double weaponDamage(ItemStack stack) {
        if (stack.isEmpty()) return 0.0;
        ItemAttributeModifiers mods = stack.getOrDefault(
                DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double sum = 0.0;
        for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
            if (e.slot().test(EquipmentSlot.MAINHAND)
                    && e.attribute().is(Attributes.ATTACK_DAMAGE)
                    && e.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                sum += e.modifier().amount();
            }
        }
        return sum;
    }
}
