package com.dwinovo.numen.api.gametest.mixin.inject;

import com.dwinovo.numen.api.gametest.BatchStart;
import net.minecraft.gametest.framework.GameTestBatchRunner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原版开一批的时刻交给 {@link BatchStart}:等到整数倍的刻开头、实体编号对齐之后再开。这一代开批的是 {@code GameTestBatchRunner.runBatch}。 */
@Mixin(GameTestBatchRunner.class)
public abstract class GameTestBatchStartMixin {

    @Shadow
    abstract void runBatch(int batchIndex);

    @Inject(method = "runBatch", at = @At("HEAD"), cancellable = true)
    private void numen$startOnTheBeat(int batchIndex, CallbackInfo ci) {
        if (!BatchStart.admit(() -> runBatch(batchIndex))) {
            ci.cancel();
        }
    }
}
