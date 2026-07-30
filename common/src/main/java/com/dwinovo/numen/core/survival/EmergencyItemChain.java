package com.dwinovo.numen.core.survival;

import com.dwinovo.numen.core.task.SurvivalConfig;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.BodyLog;
import com.dwinovo.numen.task.TaskChain;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Performs one-tick totem swaps; all ordinary healing stays in the existing FoodChain. */
public final class EmergencyItemChain implements TaskChain {
    private final BodyLog bodyLog;

    private TotemSafetyPolicy.Decision decision = new TotemSafetyPolicy.Decision(
        TotemSafetyPolicy.Action.NONE,
        Float.NEGATIVE_INFINITY
    );
    private boolean equippedBySystem;
    private int originalSlot = -1;
    private ItemStack originalOffhand = ItemStack.EMPTY;
    private int safeTicks;

    public EmergencyItemChain(BodyLog bodyLog) {
        this.bodyLog = bodyLog;
    }

    @Override
    public float getPriority(NumenPlayer player) {
        boolean survivalEnabled = SurvivalConfig.enabled();
        if (!survivalEnabled && !this.equippedBySystem) {
            this.decision = new TotemSafetyPolicy.Decision(
                TotemSafetyPolicy.Action.NONE,
                Float.NEGATIVE_INFINITY
            );
            return this.decision.priority();
        }

        boolean ongoingDanger = player.isInLava()
            || player.isOnFire()
            || RecoveryEffects.inspect(player).dangerousOngoing();
        if (!ongoingDanger && player.getHealth() >= 12.0f) {
            this.safeTicks++;
        } else {
            this.safeTicks = 0;
        }

        ItemStack offhand = player.getOffhandItem();
        boolean canEquip = !this.equippedBySystem || offhand.isEmpty();
        this.decision = TotemSafetyPolicy.decide(new TotemSafetyPolicy.State(
            player.getHealth(),
            survivalEnabled && canEquip && findTotemSlot(player.getInventory()) >= 0,
            offhand.is(Items.TOTEM_OF_UNDYING),
            ongoingDanger,
            this.equippedBySystem,
            this.safeTicks
        ));
        return this.decision.priority();
    }

    @Override
    public void tick(NumenPlayer player) {
        switch (this.decision.action()) {
            case EQUIP -> equip(player);
            case RESTORE -> restore(player);
            case NONE -> {
            }
        }
    }

    @Override
    public void onInterrupt(NumenPlayer player) {
    }

    @Override
    public String name() {
        return "emergency_items";
    }

    private void equip(NumenPlayer player) {
        if (player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
            return;
        }
        Inventory inventory = player.getInventory();
        int totemSlot = findTotemSlot(inventory);
        if (totemSlot < 0) {
            return;
        }
        if (player.isUsingItem()) {
            player.releaseUsingItem();
        }

        if (!this.equippedBySystem) {
            ItemStack totem = inventory.getItem(totemSlot);
            ItemStack offhand = player.getOffhandItem();
            this.originalSlot = totemSlot;
            this.originalOffhand = offhand.copy();
            inventory.setItem(totemSlot, offhand);
            player.setItemSlot(EquipmentSlot.OFFHAND, totem);
            this.equippedBySystem = true;
        } else if (player.getOffhandItem().isEmpty()) {
            ItemStack replacement = inventory.getItem(totemSlot).split(1);
            player.setItemSlot(EquipmentSlot.OFFHAND, replacement);
        } else {
            return;
        }
        player.inventoryMenu.broadcastChanges();
        report("equipped a totem of undying because death risk was immediate");
    }

    private void restore(NumenPlayer player) {
        if (!this.equippedBySystem || this.originalSlot < 0) {
            clearTracking();
            return;
        }

        Inventory inventory = player.getInventory();
        ItemStack offhand = player.getOffhandItem();
        ItemStack stored = inventory.getItem(this.originalSlot);
        boolean offhandExpected = offhand.isEmpty() || offhand.is(Items.TOTEM_OF_UNDYING);
        boolean storedExpected = ItemStack.matches(stored, this.originalOffhand);
        if (!offhandExpected || !storedExpected) {
            report("kept the current offhand because its saved slot changed while the totem was active");
            clearTracking();
            return;
        }

        inventory.setItem(this.originalSlot, offhand);
        player.setItemSlot(EquipmentSlot.OFFHAND, stored);
        player.inventoryMenu.broadcastChanges();
        report("restored the previous offhand after the death risk passed");
        clearTracking();
    }

    private static int findTotemSlot(Inventory inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(Items.TOTEM_OF_UNDYING)) {
                return slot;
            }
        }
        return -1;
    }

    private void clearTracking() {
        this.equippedBySystem = false;
        this.originalSlot = -1;
        this.originalOffhand = ItemStack.EMPTY;
        this.safeTicks = 0;
    }

    private void report(String message) {
        if (this.bodyLog != null) {
            this.bodyLog.report(message);
        }
    }
}
