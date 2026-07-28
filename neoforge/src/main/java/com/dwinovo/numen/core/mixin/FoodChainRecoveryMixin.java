package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.survival.RecoveryInventory;
import com.dwinovo.numen.core.survival.RecoveryPolicy;
import com.dwinovo.numen.entity.NumenPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.dwinovo.numen.core.task.chain.FoodChain")
public abstract class FoodChainRecoveryMixin {
    @Unique
    private RecoveryPolicy.Decision numen$recoveryDecision = RecoveryPolicy.Decision.none();

    @Redirect(
        method = {"getPriority", "tick"},
        at = @At(
            value = "INVOKE",
            target = "Lcom/dwinovo/numen/core/task/chain/FoodChain;bestEdibleSlot(Lcom/dwinovo/numen/entity/NumenPlayer;)I"
        )
    )
    private int numen$selectRecoveryItem(NumenPlayer player) {
        this.numen$recoveryDecision = RecoveryInventory.decide(player);
        return this.numen$recoveryDecision.slot();
    }

    @Redirect(
        method = "getPriority",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dwinovo/numen/core/task/survival/SurvivalDecisions;foodPriority(IFZ)F"
        )
    )
    private float numen$useRecoveryPriority(int food, float health, boolean hasItem) {
        return this.numen$recoveryDecision.priority();
    }
}
