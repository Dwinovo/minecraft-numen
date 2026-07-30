package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.payload.RequestInventoryPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NumenPlayer.class)
public abstract class NumenInventorySyncMixin {
    @Unique private boolean numen$inventorySnapshotDirty = true;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void numen$listenForInventoryChanges(CallbackInfo callback) {
        NumenPlayer player = (NumenPlayer) (Object) this;
        player.inventoryMenu.addSlotListener(new ContainerListener() {
            @Override
            public void slotChanged(AbstractContainerMenu menu, int slot, ItemStack stack) {
                NumenInventorySyncMixin.this.numen$inventorySnapshotDirty = true;
            }

            @Override
            public void dataChanged(AbstractContainerMenu menu, int property, int value) {
            }
        });
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void numen$flushChangedInventory(CallbackInfo callback) {
        if (!this.numen$inventorySnapshotDirty) {
            return;
        }
        NumenPlayer player = (NumenPlayer) (Object) this;
        ServerPlayer owner = player.resolveOwnerPlayer();
        if (owner == null) {
            return;
        }
        RequestInventoryPayload.handle(new RequestInventoryPayload(player.getUUID()), owner);
        this.numen$inventorySnapshotDirty = false;
    }
}
