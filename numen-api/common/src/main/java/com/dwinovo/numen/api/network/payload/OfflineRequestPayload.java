package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.entity.Companions;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * Client → Server: the owner asked a companion to go offline from the panel (menu → 下线, no confirm: nothing is lost).
 * This side only checks the companion is the caller's; what going offline does is {@link Companions#offline}.
 */
public record OfflineRequestPayload(UUID uuid) implements CustomPacketPayload {

    public static final Type<OfflineRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "offline_request"));

    public static final StreamCodec<ByteBuf, OfflineRequestPayload> STREAM_CODEC =
            StreamCodec.composite(UUIDUtil.STREAM_CODEC, OfflineRequestPayload::uuid, OfflineRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server main thread. */
    public static void handle(OfflineRequestPayload p, ServerPlayer owner) {
        MinecraftServer server = ((ServerLevel) owner.level()).getServer();
        if (server == null || p.uuid() == null) return;
        if (!Companions.owns(server, owner.getUUID(), p.uuid())) return;
        Companions.offline(server, owner.getUUID(), List.of(p.uuid()));
    }
}
