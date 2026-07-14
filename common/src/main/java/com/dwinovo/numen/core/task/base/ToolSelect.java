package com.dwinovo.numen.core.task.base;

import com.dwinovo.numen.entity.NumenPlayer;
import com.google.common.collect.Multimap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The two "swap the best implement into the main hand" helpers, unified from the
 * byte-for-byte duplicated scans that lived in {@code BlockDigger.switchToBestTool}
 * and {@code HuntCompanionTask.switchToBestWeapon}.
 *
 * <p>Both scan the WHOLE inventory (not just the hotbar) and swap via
 * {@link NumenPlayer#holdInHand(int)} — deliberately wider than a
 * hotbar-only scan, and kept consistent with the pathing cost model
 * ({@code NavContext.scanBestTool}) so the planned break cost matches the tool
 * actually used. Behaviour is preserved exactly so the Stage-2 migration onto these
 * helpers is a no-op semantically.
 */
public final class ToolSelect {

    private ToolSelect() {}

    /**
     * Hold the item that mines {@code state} fastest (highest
     * {@link ItemStack#getDestroySpeed}). Mirrors {@code BlockDigger.switchToBestTool}.
     */
    public static void holdBestTool(NumenPlayer p, BlockState state) {
        Inventory inv = p.getInventory();
        int best = inv.selected;
        float bestSpeed = inv.getItem(best).getDestroySpeed(state);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            float s = inv.getItem(i).getDestroySpeed(state);
            if (s > bestSpeed) {
                bestSpeed = s;
                best = i;
            }
        }
        p.holdInHand(best);
    }

    /**
     * Hold the highest melee-attack-damage weapon. Mirrors
     * {@code HuntCompanionTask.switchToBestWeapon}.
     */
    public static void holdBestWeapon(NumenPlayer p) {
        Inventory inv = p.getInventory();
        int best = inv.selected;
        double bestDmg = weaponDamage(inv.getItem(best));
        for (int i = 0; i < inv.getContainerSize(); i++) {
            double d = weaponDamage(inv.getItem(i));
            if (d > bestDmg) {
                bestDmg = d;
                best = i;
            }
        }
        p.holdInHand(best);
    }

    /**
     * The flat main-hand attack damage an item grants — the sum of {@code ADDITION}
     * modifiers on {@link Attributes#ATTACK_DAMAGE} that apply in the main hand.
     * Sword/axe carry the largest; a block or food scores 0 so it is never chosen
     * over a real weapon. Identical to {@code HuntCompanionTask.weaponDamage}.
     */
    private static double weaponDamage(ItemStack stack) {
        if (stack.isEmpty()) return 0.0;
        // 1.20.1: attribute modifiers come from the item's per-slot Multimap, not a component.
        Multimap<Attribute, AttributeModifier> mods = stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
        double sum = 0.0;
        for (AttributeModifier m : mods.get(Attributes.ATTACK_DAMAGE)) {
            if (m.getOperation() == AttributeModifier.Operation.ADDITION) {
                sum += m.getAmount();
            }
        }
        return sum;
    }
}
