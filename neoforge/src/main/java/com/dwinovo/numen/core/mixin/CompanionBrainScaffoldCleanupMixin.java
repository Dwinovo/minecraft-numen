package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.scaffold.ScaffoldCleanupGate;
import com.dwinovo.numen.core.scaffold.TemporaryScaffoldController;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskChain;
import com.dwinovo.numen.task.TaskQueue;
import com.dwinovo.numen.task.chain.SpeakingLookChain;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.dwinovo.numen.task.CompanionBrain")
public abstract class CompanionBrainScaffoldCleanupMixin {
    @Shadow @Final private TaskQueue queue;
    @Shadow private TaskChain running;

    @Inject(method = "tick", at = @At("TAIL"))
    private void numen$cleanupTemporaryScaffolds(NumenPlayer player, CallbackInfo callback) {
        boolean chainBlocksCleanup = ScaffoldCleanupGate.chainBlocksCleanup(
            this.running != null,
            this.running instanceof SpeakingLookChain
        );
        if (TemporaryScaffoldController.canRunCleanup(
            player,
            chainBlocksCleanup,
            this.queue.hasPending()
        )) {
            TemporaryScaffoldController.tickIdle(player);
        } else {
            TemporaryScaffoldController.pause(player);
        }
    }
}
