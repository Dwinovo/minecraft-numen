package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.NumenCore;
import com.dwinovo.numen.core.sleep.SleepFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NumenCore.class)
public abstract class SleepRegistrationMixin {
    @Inject(method = "init", at = @At("RETURN"))
    private static void numen$registerSleep(CallbackInfo callback) {
        SleepFeature.register();
    }
}
