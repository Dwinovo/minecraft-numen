package com.dwinovo.numen.mixin;

import com.dwinovo.numen.entity.FakeConnection;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drop every outbound packet aimed at a companion {@link FakeConnection}, at the
 * <em>packet-listener</em> level — one layer above {@link FakeConnection#send}.
 *
 * <h2>1.20.1 (pre-config-phase) target</h2>
 * 1.20.1 predates the 1.20.2 networking refactor, so there is no shared
 * {@code ServerCommonPacketListenerImpl}; the clientbound {@code send(Packet)}
 * lives directly on {@link ServerGamePacketListenerImpl} (and its {@code connection}
 * field is {@code public final} here, not {@code protected}). Newer branches mix
 * into the common superclass instead.
 *
 * <h2>Why drop one layer above {@code FakeConnection.send}</h2>
 * A loader can insert custom-payload channel validation <em>inside</em> the
 * listener's {@code send}, which runs <strong>before</strong> the call reaches
 * {@link Connection#send}; a companion's no-op {@code Connection.send} then never
 * gets the chance to discard a modded clientbound payload. A fake player never
 * negotiated any custom channels, so such a payload can trip the check. Cancelling
 * at {@code HEAD} of the 1-arg {@code send(Packet)} short-circuits before that path
 * and is a strict superset of what {@link FakeConnection#send} already did (drop on
 * the floor — there is no client), so there is no behavioural regression.
 *
 * <p>Common (all environments): the integrated server hits this path in singleplayer too,
 * so it must NOT be a {@code server}-only (dedicated) mixin.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinServerGamePacketListener {

    @Shadow
    @Final
    public Connection connection;

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void numen$dropOutboundForFakeConnection(Packet<?> packet, CallbackInfo ci) {
        if (this.connection instanceof FakeConnection) {
            ci.cancel();
        }
    }
}
