package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.chat.EntityPromptContract;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.dwinovo.numen.client.agent.EntityAgentLoop")
public abstract class EntityAgentLoopPromptMixin {
    @Inject(method = "composeSystemPrompt", at = @At("RETURN"), cancellable = true)
    private void numen$useRegisteredToolNames(CallbackInfoReturnable<String> callback) {
        callback.setReturnValue(EntityPromptContract.apply(callback.getReturnValue()));
    }
}
