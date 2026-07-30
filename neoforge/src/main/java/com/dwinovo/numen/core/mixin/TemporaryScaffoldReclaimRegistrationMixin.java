package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.NumenCore;
import com.dwinovo.numen.core.scaffold.TemporaryScaffoldReclaimFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NumenCore.class)
public abstract class TemporaryScaffoldReclaimRegistrationMixin {
    @Inject(method = "init", at = @At("RETURN"))
    private static void numen$registerTemporaryScaffoldReclaim(CallbackInfo callback) {
        TemporaryScaffoldReclaimFeature.register();
    }
}
