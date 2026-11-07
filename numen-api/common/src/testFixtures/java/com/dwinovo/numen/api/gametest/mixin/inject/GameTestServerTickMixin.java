package com.dwinovo.numen.api.gametest.mixin.inject;

import com.dwinovo.numen.api.gametest.BackgroundSettle;
import java.util.function.BooleanSupplier;
import net.minecraft.gametest.framework.GameTestServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 测试服务器每刻收尾、执行别的线程排给主线程的任务之前,等这一刻里起的后台活做完({@link BackgroundSettle#afterTick})。 */
@Mixin(GameTestServer.class)
public abstract class GameTestServerTickMixin {

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void numen$settleAfterTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        BackgroundSettle.afterTick((GameTestServer) (Object) this);
    }
}
