package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.mining.BlockTargetLifecycle;
import com.dwinovo.numen.core.mining.MiningTargetIdentity;
import com.dwinovo.numen.core.mining.RecentMiningTargets;
import com.dwinovo.numen.core.pathing.execute.PathingCore;
import com.dwinovo.numen.core.scaffold.TemporaryScaffoldLedger;
import com.dwinovo.numen.core.task.FailureType;
import com.dwinovo.numen.core.task.MoveToTaskRecord;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.dwinovo.numen.core.task.MoveToCompanionTask")
public abstract class MoveToTargetLifecycleMixin
    extends AbstractCompanionTask<MoveToTaskRecord> {

    @Shadow @Final private BlockPos blockTarget;
    @Shadow @Final private List<BlockPos> candidates;
    @Shadow private Block findTarget;

    @Unique private boolean numen$exactBlockTarget;
    @Unique private boolean numen$startedAsBlock;

    protected MoveToTargetLifecycleMixin(NumenPlayer player, MoveToTaskRecord record) {
        super(player, record);
    }

    @Inject(method = "onStart", at = @At("HEAD"), cancellable = true)
    private void numen$captureOrRejectTarget(CallbackInfo callback) {
        this.numen$exactBlockTarget = this.r.kind == MoveToTaskRecord.Kind.BLOCK;
        if (!this.numen$exactBlockTarget) {
            return;
        }

        this.numen$startedAsBlock = !this.player.level().getBlockState(this.blockTarget).isAir();
        if (numen$rejectLostTarget()) {
            callback.cancel();
        }
    }

    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true)
    private void numen$stopWhenTargetDisappears(
        CallbackInfoReturnable<TaskState> callback
    ) {
        if (numen$rejectLostTarget()) {
            callback.setReturnValue(TaskState.FAILED);
        }
    }

    @Inject(method = "drainFindScan", at = @At("TAIL"))
    private void numen$excludeTemporaryFindTargets(CallbackInfo callback) {
        if (this.r.kind != MoveToTaskRecord.Kind.FIND || this.findTarget == null) {
            return;
        }

        ServerLevel level = this.player.level();
        String dimensionId = level.dimension().identifier().toString();
        this.candidates.removeIf(candidate -> !MiningTargetIdentity.isEligible(
            this.player.getUUID(),
            dimensionId,
            candidate.getX(),
            candidate.getY(),
            candidate.getZ(),
            level.getBlockState(candidate).getBlock() == this.findTarget
        ));
    }

    @Unique
    private boolean numen$rejectLostTarget() {
        if (!this.numen$exactBlockTarget) {
            return false;
        }

        ServerLevel level = this.player.level();
        String dimensionId = level.dimension().identifier().toString();
        boolean currentIsAir = level.getBlockState(this.blockTarget).isAir();
        boolean temporaryScaffold = TemporaryScaffoldLedger.contains(
            this.player.getUUID(),
            dimensionId,
            this.blockTarget.getX(),
            this.blockTarget.getY(),
            this.blockTarget.getZ()
        );
        boolean recentlyMined = RecentMiningTargets.contains(
            this.player.getUUID(),
            dimensionId,
            this.blockTarget.getX(),
            this.blockTarget.getY(),
            this.blockTarget.getZ(),
            level.getGameTime()
        );
        if (!BlockTargetLifecycle.isLost(
            true,
            this.numen$startedAsBlock,
            currentIsAir,
            temporaryScaffold,
            recentlyMined
        )) {
            return false;
        }

        stopNav();
        for (PathingCore core : PathingCore.liveCores()) {
            if (core.player().getUUID().equals(this.player.getUUID())) {
                core.forceCancel();
            }
        }
        fail(
            "target " + this.blockTarget.getX() + "," + this.blockTarget.getY() + ","
                + this.blockTarget.getZ()
                + " was removed before arrival; stopped instead of building into its old cell",
            FailureType.TARGET_LOST
        );
        return true;
    }
}
