package com.dwinovo.numen.core.combat;

import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Coordinates shield use shared by explicit melee tasks and the reflex defense chain. */
public final class ShieldCombatController {
    public enum Result {
        PROCEED,
        WAIT
    }

    private ShieldCombatController() {
    }

    public static Result beforeAttack(NumenPlayer player, LivingEntity threat) {
        boolean shieldRaised = isUsingOffhandShield(player);

        ItemStack shield = shieldInOffhand(player);
        boolean usingOtherItem = player.isUsingItem() && !shieldRaised;
        if (shield.isEmpty() && !usingOtherItem) {
            shield = equipShieldInEmptyOffhand(player);
        }

        boolean shieldUsable = !shield.isEmpty() && !player.getCooldowns().isOnCooldown(shield);
        if (shieldRaised && !shieldUsable) {
            player.releaseUsingItem();
            shieldRaised = false;
        }

        boolean attackReady = player.getAttackStrengthScale(0.0F) >= 0.99F;
        ShieldCombatPolicy.Decision decision = ShieldCombatPolicy.decide(
            shieldUsable,
            usingOtherItem,
            shieldRaised,
            attackReady
        );
        return switch (decision) {
            case PROCEED -> Result.PROCEED;
            case WAIT -> Result.WAIT;
            case RAISE -> {
                InputDriver.lookAt(player, threat.getEyePosition());
                player.startUsingItem(InteractionHand.OFF_HAND);
                yield isUsingOffhandShield(player) ? Result.WAIT : Result.PROCEED;
            }
            case HOLD -> {
                InputDriver.lookAt(player, threat.getEyePosition());
                yield Result.WAIT;
            }
            case RELEASE -> {
                player.releaseUsingItem();
                yield Result.WAIT;
            }
        };
    }

    public static void release(NumenPlayer player) {
        if (isUsingOffhandShield(player)) {
            player.releaseUsingItem();
        }
    }

    private static ItemStack shieldInOffhand(NumenPlayer player) {
        ItemStack offhand = player.getOffhandItem();
        return offhand.is(Items.SHIELD) ? offhand : ItemStack.EMPTY;
    }

    private static ItemStack equipShieldInEmptyOffhand(NumenPlayer player) {
        if (!player.getOffhandItem().isEmpty()) {
            return ItemStack.EMPTY;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(Items.SHIELD)) {
                continue;
            }
            ItemStack shield = stack.split(1);
            player.setItemSlot(EquipmentSlot.OFFHAND, shield);
            player.inventoryMenu.broadcastChanges();
            return shield;
        }
        return ItemStack.EMPTY;
    }

    private static boolean isUsingOffhandShield(NumenPlayer player) {
        return player.isUsingItem()
            && player.getUsedItemHand() == InteractionHand.OFF_HAND
            && player.getUseItem().is(Items.SHIELD);
    }
}
