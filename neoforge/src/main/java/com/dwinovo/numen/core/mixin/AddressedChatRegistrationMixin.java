package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.NumenNeoForgeClient;
import com.dwinovo.numen.core.chat.AddressedChatFeature;
import net.neoforged.bus.api.IEventBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NumenNeoForgeClient.class)
public abstract class AddressedChatRegistrationMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void numen$registerAddressedChat(IEventBus modEventBus, CallbackInfo callback) {
        AddressedChatFeature.register();
    }
}
