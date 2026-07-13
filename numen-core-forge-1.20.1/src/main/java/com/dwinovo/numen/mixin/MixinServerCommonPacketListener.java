package com.dwinovo.numen.mixin;

import com.dwinovo.numen.entity.FakeConnection;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinServerCommonPacketListener {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void numen$dropOutboundForFakeConnection(Packet<?> packet, CallbackInfo ci) {
        if (((ConnectionAccessor) this).getConnection() instanceof FakeConnection) {
            ci.cancel();
        }
    }
}
