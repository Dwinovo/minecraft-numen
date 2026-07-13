package com.dwinovo.numen.inventory;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Native container menu joining one companion inventory with its owner's inventory. */
public final class CompanionInventoryMenu extends AbstractContainerMenu {
    public static final int COMPANION_SLOT_COUNT = 46;
    public static final int OWNER_SLOT_START = COMPANION_SLOT_COUNT;
    public static final int TOTAL_SLOT_COUNT = COMPANION_SLOT_COUNT + 36;

    private final UUID companionUuid;
    private final NumenPlayer body;

    public CompanionInventoryMenu(int containerId, Inventory ownerInventory, UUID companionUuid, NumenPlayer body) {
        super(NumenMenus.COMPANION_INVENTORY.get(), containerId);
        this.companionUuid = companionUuid;
        this.body = body;
        if (body == null) addClientCompanionSlots();
        else addServerCompanionSlots(body);
        addPlayerInventory(ownerInventory, 188, 76);
        if (body != null && !ownerInventory.player.level.isClientSide) {
            CompanionInventoryAccess.changed(body, true);
        }
    }

    public UUID companionUuid() {
        return companionUuid;
    }

    private void addClientCompanionSlots() {
        SimpleContainer placeholder = new SimpleContainer(COMPANION_SLOT_COUNT);
        addSlot(new Slot(placeholder, 0, 154, 28) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        });
        for (int i = 0; i < 4; i++) addSlot(new Slot(placeholder, 1 + i, 98 + (i % 2) * 18, 18 + (i / 2) * 18));
        for (int i = 0; i < 4; i++) addSlot(new Slot(placeholder, 5 + i, 8, 8 + i * 18));
        addSlot(new Slot(placeholder, 9, 77, 62));
        for (int slot = 9; slot < 36; slot++) {
            int n = slot - 9;
            addSlot(new Slot(placeholder, 10 + n, 8 + (n % 9) * 18, 84 + (n / 9) * 18));
        }
        for (int slot = 0; slot < 9; slot++) addSlot(new Slot(placeholder, 37 + slot, 8 + slot * 18, 142));
    }

    private void addServerCompanionSlots(NumenPlayer companion) {
        addSlot(new ProxySlot(companion.inventoryMenu.getSlot(0), 154, 28));
        for (int i = 0; i < 4; i++) {
            addSlot(new ProxySlot(companion.inventoryMenu.getSlot(1 + i),
                    98 + (i % 2) * 18, 18 + (i / 2) * 18));
        }
        for (int i = 0; i < 4; i++) {
            addSlot(new ProxySlot(companion.inventoryMenu.getSlot(5 + i), 8, 8 + i * 18));
        }
        addSlot(new ProxySlot(companion.inventoryMenu.getSlot(45), 77, 62));
        Inventory inventory = companion.getInventory();
        for (int slot = 9; slot < 36; slot++) {
            int n = slot - 9;
            addSlot(new Slot(inventory, slot, 8 + (n % 9) * 18, 84 + (n / 9) * 18));
        }
        for (int slot = 0; slot < 9; slot++) addSlot(new Slot(inventory, slot, 8 + slot * 18, 142));
    }

    private void addPlayerInventory(Inventory inventory, int x, int y) {
        for (int slot = 9; slot < 36; slot++) {
            int n = slot - 9;
            addSlot(new Slot(inventory, slot, x + 8 + (n % 9) * 18, y + 8 + (n / 9) * 18));
        }
        for (int slot = 0; slot < 9; slot++) addSlot(new Slot(inventory, slot, x + 8 + slot * 18, y + 66));
    }

    @Override public boolean stillValid(Player player) {
        if (player.level.isClientSide || body == null) return true;
        return !body.isRemoved() && body.isAlive() && body.isOwnedByPlayer(player.getUUID());
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack source = slot.getItem();
        ItemStack original = source.copy();
        boolean moved = index < OWNER_SLOT_START
                ? moveItemStackTo(source, OWNER_SLOT_START, TOTAL_SLOT_COUNT, false)
                : moveItemStackTo(source, 10, COMPANION_SLOT_COUNT, false);
        if (!moved) return ItemStack.EMPTY;
        if (source.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player, source);
        return original;
    }

    @Override public void removed(Player player) {
        super.removed(player);
        if (body != null && !player.level.isClientSide) CompanionInventoryAccess.changed(body, false);
    }

    private static final class ProxySlot extends Slot {
        private final Slot delegate;

        private ProxySlot(Slot delegate, int x, int y) {
            super(delegate.container, delegate.getContainerSlot(), x, y);
            this.delegate = delegate;
        }

        @Override public ItemStack getItem() { return delegate.getItem(); }
        @Override public boolean hasItem() { return delegate.hasItem(); }
        @Override public void set(ItemStack stack) { delegate.set(stack); }
        @Override public void setByPlayer(ItemStack stack) { delegate.setByPlayer(stack); }
        @Override public ItemStack remove(int amount) { return delegate.remove(amount); }
        @Override public boolean mayPlace(ItemStack stack) { return delegate.mayPlace(stack); }
        @Override public boolean mayPickup(Player player) { return delegate.mayPickup(player); }
        @Override public void onTake(Player player, ItemStack stack) { delegate.onTake(player, stack); }
        @Override public void setChanged() { delegate.setChanged(); }
        @Override public int getMaxStackSize() { return delegate.getMaxStackSize(); }
        @Override public int getMaxStackSize(ItemStack stack) { return delegate.getMaxStackSize(stack); }
        @Override public boolean isActive() { return delegate.isActive(); }
    }
}
