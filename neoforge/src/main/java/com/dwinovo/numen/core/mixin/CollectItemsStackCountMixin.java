package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.inventory.PickupQuantity;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.CollectItemsTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.dwinovo.numen.core.task.CollectItemsTaskGoal")
public abstract class CollectItemsStackCountMixin
    extends AbstractCompanionTask<CollectItemsTaskRecord> {
    @Unique private int numen$matchingInventoryCount;

    protected CollectItemsStackCountMixin(NumenPlayer player, CollectItemsTaskRecord record) {
        super(player, record);
    }

    @Inject(method = "onStart", at = @At("TAIL"))
    private void numen$startInventoryCount(CallbackInfo callback) {
        this.numen$matchingInventoryCount = this.numen$countMatchingInventory();
    }

    @Inject(method = "onTick", at = @At("HEAD"))
    private void numen$countInventoryGain(CallbackInfoReturnable<?> callback) {
        int current = this.numen$countMatchingInventory();
        int gained = PickupQuantity.fromInventoryDelta(this.numen$matchingInventoryCount, current);
        for (int i = 0; i < gained; i++) {
            this.r.incrementCollected();
        }
        this.numen$matchingInventoryCount = current;
    }

    @Redirect(
        method = "tickApproach",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dwinovo/numen/core/task/CollectItemsTaskRecord;incrementCollected()V"
        )
    )
    private void numen$ignoreRemovedEntityCount(CollectItemsTaskRecord record) {
        // Inventory deltas distinguish actual pickup from merge, despawn, or destruction.
    }

    @Unique
    private int numen$countMatchingInventory() {
        Inventory inventory = this.player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && (this.r.filter.isEmpty() || this.r.filter.contains(stack.getItem()))) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
