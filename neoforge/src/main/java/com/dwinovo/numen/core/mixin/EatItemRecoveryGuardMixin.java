package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.survival.RecoveryInventory;
import com.dwinovo.numen.core.task.EatItemTaskRecord;
import com.dwinovo.numen.core.task.FailureType;
import com.dwinovo.numen.core.task.PlayerInv;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.entity.NumenPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.dwinovo.numen.core.task.EatCompanionTask")
public abstract class EatItemRecoveryGuardMixin
    extends AbstractCompanionTask<EatItemTaskRecord> {
    protected EatItemRecoveryGuardMixin(NumenPlayer player, EatItemTaskRecord record) {
        super(player, record);
    }

    @Inject(method = "preconditions", at = @At("RETURN"), cancellable = true)
    private void numen$guardRequestedConsumable(
        CallbackInfoReturnable<List<Precondition>> callback
    ) {
        List<Precondition> guarded = new ArrayList<>(callback.getReturnValue());
        if ((this.r.item == Items.POTION || this.r.item == Items.MILK_BUCKET)
            && guarded.size() >= 2) {
            guarded.set(1, () -> null);
        }
        guarded.add(() -> {
            String refusal = RecoveryInventory.requestedUseRefusal(this.player, this.r.item);
            return refusal == null ? null : new Precondition.Failure(refusal, FailureType.UNKNOWN);
        });
        callback.setReturnValue(List.copyOf(guarded));
    }

    @Redirect(
        method = "onStart",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dwinovo/numen/core/task/PlayerInv;findSlot(Lnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/item/Item;)I"
        )
    )
    private int numen$selectExactConsumable(Inventory inventory, Item requestedItem) {
        int selected = RecoveryInventory.selectedRequestedSlot(this.player, requestedItem);
        return selected >= 0 ? selected : PlayerInv.findSlot(inventory, requestedItem);
    }
}
