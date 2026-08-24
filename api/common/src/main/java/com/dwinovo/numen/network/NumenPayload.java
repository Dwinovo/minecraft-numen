package com.dwinovo.numen.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Mod-local stand-in for vanilla's {@code CustomPacketPayload}, which does NOT exist on
 * MC 1.20.1 — the {@code net.minecraft.network.protocol.common.custom} package (and the
 * whole common/configuration-phase packet split) is a 1.20.2+ addition.
 *
 * <p>The cross-loader networking layer ({@link com.dwinovo.numen.platform.services.INetworkChannel})
 * only needs a payload to expose its channel {@link ResourceLocation id()} and a
 * {@code write(FriendlyByteBuf)} serialiser (its reverse is a static {@code read(FriendlyByteBuf)}
 * passed to {@code register*} as the decoder). This interface declares exactly that contract, so
 * every payload record keeps its existing {@code id()} / {@code write()} overrides unchanged —
 * only the implemented interface differs from the 1.20.2 branch.
 */
public interface NumenPayload {

    /** Stable channel identifier for this payload (also its public {@code ID}). */
    ResourceLocation id();

    /** Serialise this payload into the buffer. */
    void write(FriendlyByteBuf buf);
}
