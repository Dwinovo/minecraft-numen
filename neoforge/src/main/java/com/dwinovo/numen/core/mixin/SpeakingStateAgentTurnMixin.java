package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.agent.AgentTurnActivity;
import com.dwinovo.numen.network.payload.SpeakingStatePayload;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpeakingStatePayload.class)
public abstract class SpeakingStateAgentTurnMixin {
    @Inject(
        method = "handle",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dwinovo/numen/entity/CompanionSpeech;setSpeaking(Ljava/util/UUID;Z)V"
        )
    )
    private static void numen$observeValidatedAgentTurn(
        SpeakingStatePayload payload,
        ServerPlayer owner,
        CallbackInfo callback
    ) {
        AgentTurnActivity.observe(
            payload.entityUuid(),
            payload.speaking(),
            owner.level().getServer().getTickCount()
        );
    }
}
