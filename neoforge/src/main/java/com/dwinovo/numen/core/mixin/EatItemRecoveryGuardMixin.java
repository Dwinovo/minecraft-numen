package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.survival.RecoveryInventory;
import com.dwinovo.numen.core.task.EatItemTaskRecord;
import com.dwinovo.numen.core.task.FailureType;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.entity.NumenPlayer;
import java.util.ArrayList;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
        guarded.add(() -> {
            String refusal = RecoveryInventory.requestedUseRefusal(this.player, this.r.item);
            return refusal == null ? null : new Precondition.Failure(refusal, FailureType.UNKNOWN);
        });
        callback.setReturnValue(List.copyOf(guarded));
    }
}
