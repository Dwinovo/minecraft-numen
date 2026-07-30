package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.chat.EntityPromptContract;
import com.dwinovo.numen.core.chat.ClientInventoryPromptContext;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.network.payload.SpeakingStatePayload;
import com.dwinovo.numen.platform.Services;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.dwinovo.numen.client.agent.EntityAgentLoop")
public abstract class EntityAgentLoopPromptMixin {
    @Unique private static final int NUMEN$AGENT_TURN_HEARTBEAT_TICKS = 40;

    @Shadow @Final private UUID entityUuid;
    @Shadow private boolean lastSpeakingSent;
    @Shadow public abstract boolean isBusy();
    @Unique private int numen$agentTurnHeartbeatTicks;
    @Unique private boolean numen$agentTurnLeaseActive;
    @Unique private boolean numen$lastSpeakingObserved;
    @Unique private ClientInventoryPromptContext numen$inventoryPromptContext;

    @Inject(method = "composeSystemPrompt", at = @At("RETURN"), cancellable = true)
    private void numen$augmentSystemPrompt(CallbackInfoReturnable<String> callback) {
        if (this.numen$inventoryPromptContext == null) {
            this.numen$inventoryPromptContext = new ClientInventoryPromptContext();
        }
        String inventory = this.numen$inventoryPromptContext.refresh(
            this.entityUuid,
            ClientNumenLookup.resolve(this.entityUuid)
        );
        callback.setReturnValue(EntityPromptContract.apply(callback.getReturnValue()) + inventory);
    }

    @Inject(method = "clientTick", at = @At("TAIL"))
    private void numen$renewAgentTurnLease(CallbackInfo callback) {
        if (Minecraft.getInstance().getConnection() == null) {
            this.numen$agentTurnHeartbeatTicks = 0;
            this.numen$agentTurnLeaseActive = false;
            this.numen$lastSpeakingObserved = false;
            return;
        }

        boolean speaking = this.lastSpeakingSent;
        boolean active = this.isBusy() || speaking;
        boolean speakingDroppedWhileBusy =
            this.numen$lastSpeakingObserved && !speaking && active;
        this.numen$lastSpeakingObserved = speaking;

        if (!active) {
            this.numen$agentTurnHeartbeatTicks = 0;
            if (this.numen$agentTurnLeaseActive) {
                Services.NETWORK.sendToServer(new SpeakingStatePayload(this.entityUuid, false));
            }
            this.numen$agentTurnLeaseActive = false;
            return;
        }

        this.numen$agentTurnHeartbeatTicks++;
        if (!speaking
            && (!this.numen$agentTurnLeaseActive || speakingDroppedWhileBusy)) {
            this.numen$agentTurnHeartbeatTicks = 0;
            this.numen$agentTurnLeaseActive = true;
            Services.NETWORK.sendToServer(new SpeakingStatePayload(this.entityUuid, true));
            return;
        }
        this.numen$agentTurnLeaseActive = true;
        if (this.numen$agentTurnHeartbeatTicks >= NUMEN$AGENT_TURN_HEARTBEAT_TICKS) {
            this.numen$agentTurnHeartbeatTicks = 0;
            Services.NETWORK.sendToServer(new SpeakingStatePayload(this.entityUuid, true));
        }
    }
}
