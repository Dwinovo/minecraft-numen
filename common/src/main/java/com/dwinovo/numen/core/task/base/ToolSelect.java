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
 * <p>Durability guard: a damageable item whose remaining durability is inside the
 * {@link #nearBreaking} margin never enters either scan, and if the HELD item is
 * itself inside the margin with no usable replacement, the hand is vacated
 * ({@link #holdIdleSlot}) — mining and melee both consume durability, so a
 * nearly-broken pick/sword must leave the hand before it shatters. The guard is
 * a registered reflex ({@code tool_guard}) — {@link #nearBreaking} returns
 * {@code false} across the board when the owner switches it off.
 *
 * <p>Intent pin (constitution §5): a MAINHAND pin — the trace of an explicit
 * {@code equip_item} — makes both swaps a no-op: the explicitly-held item is
 * neither replaced by a "better" one nor vacated for being nearly broken
 * (explicit = informed consent, so even a fast-breaking pinned tool is let
 * through). The pin expires by fingerprint the moment the item breaks or
 * otherwise leaves the hand, and the task-idle edge releases it.
 */
public final class ToolSelect {

    private ToolSelect() {}

    /** Below this many remaining uses an item counts as nearly broken (absolute floor). */
    private static final int NEAR_BREAK_FLOOR = 8;

    /**
     * Whether {@code stack} is close enough to breaking that it must not be used
     * for work that consumes durability: remaining uses
     * ({@code getMaxDamage() - getDamageValue()}) at or under
     * {@code max(8, 10% of maxDamage)}. Non-damageable items (blocks, food, …)
     * are never near breaking. Shared by the hand-swap scans here and the pathing
     * cost model ({@code NavContext.scanBestTool}) so planning and execution agree
     * on which tools are off the table.
     */
    public static boolean nearBreaking(ItemStack stack) {
        // Policy-reflex switch (constitution §6): guard off → nothing counts as
        // nearly broken, restoring pre-guard behavior everywhere it's consulted.
        if (!com.dwinovo.numen.core.task.reflex.ReflexRegistry.enabled(
                com.dwinovo.numen.core.task.reflex.CoreReflexes.TOOL_GUARD_ID)) {
            return false;
        }
        if (stack.isEmpty() || !stack.isDamageableItem()) {
            return false;
        }
        int max = stack.getMaxDamage();
        int remaining = max - stack.getDamageValue();
        return remaining <= Math.max(NEAR_BREAK_FLOOR, max / 10);
    }

    /**
     * Hold the item that mines {@code state} fastest (highest
     * {@link ItemStack#getDestroySpeed}), measured against the bare-hand baseline
     * of 1.0. Nearly-broken candidates are skipped; when nothing beats the bare
     * hand and the held item is itself nearly broken, the hand is vacated so the
     * dig proceeds bare-handed instead of grinding the tool to dust.
     */
    public static void holdBestTool(NumenPlayer p, BlockState state) {
        if (handPinned(p)) return;   // explicit hold — don't swap it, don't guard it
        Inventory inv = p.getInventory();
        int best = -1;
        float bestSpeed = 1.0f;                       // bare-hand baseline
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (nearBreaking(s)) continue;
            float spd = s.getDestroySpeed(state);
            if (spd > bestSpeed) {
                bestSpeed = spd;
                best = i;
            }
        }
        if (best >= 0) {
            p.holdInHand(best);
        } else if (nearBreaking(inv.getItem(inv.selected))) {
            holdIdleSlot(p);
        }
    }

    /**
     * Hold the highest melee-attack-damage weapon. Nearly-broken candidates are
     * skipped; when no usable weapon exists and the held item is itself nearly
     * broken, the hand is vacated — a bare-hand punch beats shattering the sword.
     */
    public static void holdBestWeapon(NumenPlayer p) {
        if (handPinned(p)) return;   // explicit hold — don't swap it, don't guard it
        Inventory inv = p.getInventory();
        int best = -1;
        double bestDmg = 0.0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (nearBreaking(s)) continue;
            double d = weaponDamage(s);
            if (d > bestDmg) {
                bestDmg = d;
                best = i;
            }
        }
        if (best >= 0) {
            p.holdInHand(best);
        } else if (nearBreaking(inv.getItem(inv.selected))) {
            holdIdleSlot(p);
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
     * Vacate the main hand: swap in the first empty or non-damageable main-inventory
     * slot (the 36 storage slots only — armour and offhand must never be swapped into
     * the hand here). When every slot holds a damageable item, the hand is left as-is.
     */
    private static void holdIdleSlot(NumenPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (i == inv.selected) continue;
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !s.isDamageableItem()) {
                p.holdInHand(i);
                return;
            }
        }
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
