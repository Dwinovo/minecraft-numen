package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.combat.CombatTacticPolicy;
import com.dwinovo.numen.core.combat.CombatWeaponController;
import com.dwinovo.numen.core.combat.CombatWeaponSelector;
import com.dwinovo.numen.core.combat.ShieldCombatController;
import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.task.base.ToolSelect;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.dwinovo.numen.core.task.chain.MobDefenseChain")
public abstract class MobDefenseTacticsMixin {
    @Shadow
    private void flee(NumenPlayer player) {
        throw new AssertionError();
    }

    @Unique private int numen$tacticsTargetId = -1;
    @Unique private int numen$creeperDangerTicks;
    @Unique private int numen$creeperRetreatCycles;
    @Unique private boolean numen$creeperFuseWasActive;
    @Unique private boolean numen$creeperRetreatActive;
    @Unique private boolean numen$creeperCloseHitPending;
    @Unique private boolean numen$useSpearThisTick;
    @Unique private CombatWeaponSelector.Candidate numen$spearThisTick;

    @Inject(method = "fight", at = @At("HEAD"), cancellable = true)
    private void numen$chooseDefenseTactic(
        NumenPlayer player,
        LivingEntity threat,
        CallbackInfo callback
    ) {
        this.numen$useSpearThisTick = false;
        this.numen$spearThisTick = null;
        if (threat.getId() != this.numen$tacticsTargetId) {
            this.numen$resetTargetState(player, threat.getId());
        }

        boolean creeper = threat instanceof Creeper;
        boolean fuseActive = creeper && numen$fuseActive((Creeper) threat);
        if (fuseActive) {
            this.numen$creeperDangerTicks++;
            if (!this.numen$creeperFuseWasActive) {
                this.numen$creeperRetreatCycles++;
            }
        } else {
            this.numen$creeperDangerTicks = 0;
        }
        this.numen$creeperFuseWasActive = fuseActive;

        CombatWeaponSelector.Loadout loadout = CombatWeaponSelector.inspect(player);
        double distance = player.distanceTo(threat);
        boolean rangedReady = CombatWeaponController.rangedReady(player, threat, loadout);
        boolean safeSpearApproach = loadout.spearReady()
            && distance > 3.5
            && distance <= CombatTacticPolicy.SPEAR_MAX_RANGE;
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
            && player.getAttackStrengthScale(0.0F) >= 0.99F;
        boolean rangedShotInProgress = CombatWeaponController.rangedShotInProgress(player, threat);
        CombatTacticPolicy.Action action = CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
            creeper,
            fuseActive,
            distance,
            player.hasLineOfSight(threat),
            rangedReady,
            safeSpearApproach,
            true,
            this.numen$creeperDangerTicks,
            this.numen$creeperRetreatCycles
        ), this.numen$creeperRetreatActive, closeHitReady, rangedShotInProgress);
        if (creeper && action == CombatTacticPolicy.Action.RETREAT) {
            this.numen$creeperRetreatActive = true;
        }

        switch (action) {
            case RETREAT, ABANDON -> {
                CombatWeaponController.reset(player);
                ShieldCombatController.release(player);
                this.flee(player);
                callback.cancel();
            }
            case RANGED -> {
                ShieldCombatController.release(player);
                CombatWeaponController.tickRanged(player, threat, loadout.ranged());
                callback.cancel();
            }
            case SPEAR -> {
                ShieldCombatController.release(player);
                CombatWeaponController.tickSpear(player, threat, loadout.spear());
                this.numen$useSpearThisTick = true;
                this.numen$spearThisTick = loadout.spear();
            }
            case MELEE, APPROACH -> CombatWeaponController.pause(player);
        }
    }

    @Redirect(
        method = "fight",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dwinovo/numen/core/task/base/ToolSelect;holdBestWeapon(Lcom/dwinovo/numen/entity/NumenPlayer;)V"
        )
    )
    private void numen$holdDefenseWeapon(NumenPlayer player) {
        CombatWeaponSelector.Loadout loadout = CombatWeaponSelector.inspect(player);
        CombatWeaponSelector.Candidate selected = this.numen$useSpearThisTick
            ? this.numen$spearThisTick
            : loadout.meleeOrSpear();
        if (selected != null) {
            CombatWeaponSelector.hold(player, selected);
        } else {
            ToolSelect.holdBestWeapon(player);
        }
    }

    @Redirect(
        method = "fight",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dwinovo/numen/core/act/Interaction;tick()Lcom/dwinovo/numen/core/act/Interaction$Status;"
        )
    )
    private Interaction.Status numen$recordConfirmedDefenseHit(Interaction interaction) {
        Interaction.Status status = interaction.tick();
        if (status == Interaction.Status.DONE && this.numen$creeperCloseHitPending) {
            this.numen$creeperCloseHitPending = false;
            this.numen$creeperRetreatActive = true;
        }
        return status;
    }

    @Inject(method = {"flee", "release", "onInterrupt"}, at = @At("HEAD"))
    private void numen$releaseDefenseWeapon(NumenPlayer player, CallbackInfo callback) {
        CombatWeaponController.reset(player);
    }

    @Unique
    private void numen$resetTargetState(NumenPlayer player, int targetId) {
        CombatWeaponController.reset(player);
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
