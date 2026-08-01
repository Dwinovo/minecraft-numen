package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.entity.FakeConnection;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** Supply an IP-shaped address for Numen's in-memory fake-player connection. */
@Mixin(Connection.class)
public abstract class ConnectionRemoteAddressMixin {

    private static final InetSocketAddress NUMEN_LOOPBACK_ADDRESS =
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

    @Inject(method = "getRemoteAddress", at = @At("RETURN"), cancellable = true)
    private void numen$useCompatibleFakePlayerAddress(CallbackInfoReturnable<SocketAddress> cir) {
        SocketAddress original = cir.getReturnValue();
        SocketAddress compatible = numen$compatibleRemoteAddress((Connection) (Object) this, original);
        if (compatible != original) {
            cir.setReturnValue(compatible);
        }
    }

    private static SocketAddress numen$compatibleRemoteAddress(Connection connection, SocketAddress original) {
        if (connection instanceof FakeConnection && !(original instanceof InetSocketAddress)) {
            return NUMEN_LOOPBACK_ADDRESS;
        }
        return original;
    }
}
