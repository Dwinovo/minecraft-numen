package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.combat.ShieldCombatController;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.task.MeleeAttackTaskRecord;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.dwinovo.numen.core.task.MeleeAttackCompanionTask")
public abstract class MeleeAttackShieldMixin {
    @Shadow private LivingEntity target;

    @Unique private NumenPlayer numen$shieldPlayer;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void numen$captureShieldPlayer(
        NumenPlayer player,
        MeleeAttackTaskRecord record,
        CallbackInfo callback
    ) {
        this.numen$shieldPlayer = player;
    }

    @Inject(
        method = "tickTarget",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dwinovo/numen/entity/NumenPlayer;isUsingItem()Z"
        ),
        cancellable = true
    )
    private void numen$defendBetweenMeleeSwings(CallbackInfoReturnable<TaskState> callback) {
        if (this.target != null
            && ShieldCombatController.beforeAttack(this.numen$shieldPlayer, this.target)
                == ShieldCombatController.Result.WAIT) {
            callback.setReturnValue(TaskState.RUNNING);
        }
    }

    @Inject(method = {"chaseTarget", "backOffTarget"}, at = @At("HEAD"))
    private void numen$releaseShieldForMovement(
        double distance,
        CallbackInfoReturnable<TaskState> callback
    ) {
        ShieldCombatController.release(this.numen$shieldPlayer);
    }

    @Inject(method = {"beginLoot", "clearTarget", "cleanup"}, at = @At("HEAD"))
    private void numen$releaseShieldAfterCombat(CallbackInfo callback) {
        ShieldCombatController.release(this.numen$shieldPlayer);
    }
}
