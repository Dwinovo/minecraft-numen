package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.combat.CombatTacticPolicy;
import com.dwinovo.numen.core.combat.CombatWeaponController;
import com.dwinovo.numen.core.combat.CombatWeaponSelector;
import com.dwinovo.numen.core.combat.ShieldCombatController;
import com.dwinovo.numen.core.task.MeleeAttackTaskRecord;
import com.dwinovo.numen.core.task.base.ToolSelect;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.dwinovo.numen.core.task.MeleeAttackCompanionTask")
public abstract class MeleeAttackTacticsMixin {
    @Shadow private LivingEntity target;

    @Unique private NumenPlayer numen$tacticsPlayer;
    @Unique private MeleeAttackTaskRecord numen$tacticsRecord;
    @Unique private int numen$tacticsTargetId = -1;
    @Unique private int numen$creeperDangerTicks;
    @Unique private int numen$creeperRetreatCycles;
    @Unique private boolean numen$creeperFuseWasActive;
    @Unique private boolean numen$creeperRetreatActive;
    @Unique private boolean numen$creeperCloseHitPending;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void numen$captureTactics(
        NumenPlayer player,
        MeleeAttackTaskRecord record,
        CallbackInfo callback
    ) {
        this.numen$tacticsPlayer = player;
        this.numen$tacticsRecord = record;
    }

    @Inject(method = "tickTarget", at = @At("HEAD"), cancellable = true)
    private void numen$useDistanceAwareCombat(CallbackInfoReturnable<TaskState> callback) {
        LivingEntity current = this.target;
        if (current == null) {
            return;
        }
        if (current.getId() != this.numen$tacticsTargetId) {
            this.numen$resetTargetState(current.getId());
        }

        boolean creeper = current instanceof Creeper;
        boolean fuseActive = creeper && numen$fuseActive((Creeper) current);
        if (fuseActive) {
            this.numen$creeperDangerTicks++;
            if (!this.numen$creeperFuseWasActive) {
                this.numen$creeperRetreatCycles++;
            }
        } else {
            this.numen$creeperDangerTicks = 0;
        }
        this.numen$creeperFuseWasActive = fuseActive;

        CombatWeaponSelector.Loadout loadout = CombatWeaponSelector.inspect(this.numen$tacticsPlayer);
        boolean rangedReady = CombatWeaponController.rangedReady(
            this.numen$tacticsPlayer,
            current,
            loadout
        );
        double distance = this.numen$tacticsPlayer.distanceTo(current);
        CombatTacticPolicy.Engagement engagement = CombatTacticPolicy.updateEngagement(
            creeper,
            fuseActive,
            distance,
            rangedReady,
            new CombatTacticPolicy.Engagement(
                this.numen$creeperRetreatActive,
                this.numen$creeperCloseHitPending
            )
        );
        this.numen$creeperRetreatActive = engagement.retreatActive();
        this.numen$creeperCloseHitPending = engagement.closeHitPending();
        boolean closeHitReady = this.numen$creeperCloseHitPending
            && this.numen$tacticsPlayer.getAttackStrengthScale(0.0F) >= 0.99F;
        boolean rangedShotInProgress = CombatWeaponController.rangedShotInProgress(
            this.numen$tacticsPlayer,
            current
        );
        CombatTacticPolicy.Action action = CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
            creeper,
            fuseActive,
            distance,
            this.numen$tacticsPlayer.hasLineOfSight(current),
            rangedReady,
            loadout.spearReady(),
            true,
            this.numen$creeperDangerTicks,
            this.numen$creeperRetreatCycles
        ), this.numen$creeperRetreatActive, closeHitReady, rangedShotInProgress);
        if (creeper && action == CombatTacticPolicy.Action.RETREAT) {
            this.numen$creeperRetreatActive = true;
        }

        switch (action) {
            case RETREAT -> {
                CombatWeaponController.reset(this.numen$tacticsPlayer);
                ShieldCombatController.release(this.numen$tacticsPlayer);
                double spacing = fuseActive
                    ? CombatTacticPolicy.CREEPER_DANGER_RANGE + 0.5
                    : rangedReady
                        ? CombatTacticPolicy.CREEPER_RANGED_RESUME_RANGE
                        : CombatTacticPolicy.CREEPER_MELEE_RESUME_RANGE;
                callback.setReturnValue(
                    ((MeleeAttackTaskAccessor) this).numen$backOffTarget(spacing)
                );
            }
            case RANGED -> {
                ShieldCombatController.release(this.numen$tacticsPlayer);
                CombatWeaponController.tickRanged(
                    this.numen$tacticsPlayer,
                    current,
                    loadout.ranged()
                );
                callback.setReturnValue(TaskState.RUNNING);
            }
            case SPEAR -> {
                ShieldCombatController.release(this.numen$tacticsPlayer);
                CombatWeaponController.tickSpear(
                    this.numen$tacticsPlayer,
                    current,
                    loadout.spear()
                );
                callback.setReturnValue(
                    ((MeleeAttackTaskAccessor) this).numen$chaseTarget(
                        CombatTacticPolicy.SPEAR_MIN_RANGE
                    )
                );
            }
            case ABANDON -> {
                CombatWeaponController.reset(this.numen$tacticsPlayer);
                ShieldCombatController.release(this.numen$tacticsPlayer);
                this.numen$tacticsRecord.unreachable(current.getId());
                ((MeleeAttackTaskAccessor) this).numen$clearTarget();
                callback.setReturnValue(TaskState.RUNNING);
            }
            case MELEE, APPROACH -> CombatWeaponController.pause(this.numen$tacticsPlayer);
        }
    }

    @Redirect(
        method = "tickTarget",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dwinovo/numen/core/task/base/ToolSelect;holdBestWeapon(Lcom/dwinovo/numen/entity/NumenPlayer;)V"
        )
    )
    private void numen$holdCloseRangeWeapon(NumenPlayer player) {
        CombatWeaponSelector.Candidate close = CombatWeaponSelector.inspect(player).meleeOrSpear();
        if (close != null) {
            CombatWeaponSelector.hold(player, close);
        } else {
            ToolSelect.holdBestWeapon(player);
        }
    }

    @Redirect(
        method = "tickTarget",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dwinovo/numen/core/task/MeleeAttackTaskRecord;hit(I)V"
        )
    )
    private void numen$recordConfirmedCloseHit(MeleeAttackTaskRecord record, int targetId) {
        record.hit(targetId);
        if (targetId == this.numen$tacticsTargetId && this.numen$creeperCloseHitPending) {
            this.numen$creeperCloseHitPending = false;
            this.numen$creeperRetreatActive = true;
        }
    }

    @Inject(method = {"beginLoot", "clearTarget", "cleanup"}, at = @At("HEAD"))
    private void numen$releaseTacticalWeapon(CallbackInfo callback) {
        CombatWeaponController.reset(this.numen$tacticsPlayer);
    }

    @Unique
    private void numen$resetTargetState(int targetId) {
        CombatWeaponController.reset(this.numen$tacticsPlayer);
        this.numen$tacticsTargetId = targetId;
        this.numen$creeperDangerTicks = 0;
        this.numen$creeperRetreatCycles = 0;
        this.numen$creeperFuseWasActive = false;
        this.numen$creeperRetreatActive = false;
        this.numen$creeperCloseHitPending = false;
    }

    @Unique
    private static boolean numen$fuseActive(Creeper creeper) {
        return creeper.isIgnited()
            || creeper.getSwellDir() > 0
            || creeper.getSwelling(0.0F) > 0.0F;
    }
}
