package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.mining.InteractionTargetGuard;
import com.dwinovo.numen.core.task.FailureType;
import com.dwinovo.numen.core.task.InteractAtTaskRecord;
import com.dwinovo.numen.core.task.base.GoToThenDoTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.dwinovo.numen.core.task.InteractAtCompanionTask")
public abstract class InteractAtSafetyMixin extends GoToThenDoTask<InteractAtTaskRecord> {
    protected InteractAtSafetyMixin(NumenPlayer player, InteractAtTaskRecord record) {
        super(player, record);
    }

    @Inject(method = "act", at = @At("HEAD"), cancellable = true)
    private void numen$rejectMissingAttackTarget(CallbackInfoReturnable<TaskState> callback) {
        if (this.r.button != InteractAtTaskRecord.Button.LEFT || this.r.aim == null) {
            return;
        }

        boolean targetIsAir = this.player.level().getBlockState(this.r.aim).isAir();
        if (InteractionTargetGuard.decide(targetIsAir, false)
            != InteractionTargetGuard.Decision.TARGET_GONE) {
            return;
        }

        fail(
            "target " + this.r.aim.toShortString() + " is already air; no other block was attacked",
            FailureType.TARGET_LOST
        );
        callback.setReturnValue(TaskState.FAILED);
    }
}
