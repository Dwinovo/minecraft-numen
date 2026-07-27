package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.combat.ShieldCombatController;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.dwinovo.numen.core.task.chain.MobDefenseChain")
public abstract class MobDefenseShieldMixin {
    @Shadow
    private boolean inReach(NumenPlayer player, LivingEntity threat) {
        throw new AssertionError();
    }

    @Inject(method = "fight", at = @At("HEAD"))
    private void numen$releaseShieldBeforeChase(
        NumenPlayer player,
        LivingEntity threat,
        CallbackInfo callback
    ) {
        if (!this.inReach(player, threat)) {
            ShieldCombatController.release(player);
        }
    }

    @Inject(
        method = "fight",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dwinovo/numen/core/act/Interaction;attackEntity(Lcom/dwinovo/numen/entity/NumenPlayer;Lnet/minecraft/world/entity/Entity;)Lcom/dwinovo/numen/core/act/Interaction;"
        ),
        cancellable = true
    )
    private void numen$defendBetweenReflexSwings(
        NumenPlayer player,
        LivingEntity threat,
        CallbackInfo callback
    ) {
        if (ShieldCombatController.beforeAttack(player, threat) == ShieldCombatController.Result.WAIT) {
            callback.cancel();
        }
    }

    @Inject(method = {"flee", "release", "onInterrupt"}, at = @At("HEAD"))
    private void numen$releaseShieldOutsideFight(NumenPlayer player, CallbackInfo callback) {
        ShieldCombatController.release(player);
    }
}
