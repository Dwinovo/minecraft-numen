package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
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
 * Client → Server: the owner asked to permanently delete a companion from the panel (rail ✕ → confirm).
 * This side only checks the companion is the caller's; what dismissal does is {@link Companions#dismiss}.
 */
public record DismissRequestPayload(UUID uuid) implements CustomPacketPayload {

    public static final Type<DismissRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "dismiss_request"));

    public static final StreamCodec<ByteBuf, DismissRequestPayload> STREAM_CODEC =
            StreamCodec.composite(UUIDUtil.STREAM_CODEC, DismissRequestPayload::uuid, DismissRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server main thread. */
    public static void handle(DismissRequestPayload p, ServerPlayer owner) {
        MinecraftServer server = ((ServerLevel) owner.level()).getServer();
        if (server == null || p.uuid() == null) return;

        // 活体看身体上的主人;休眠 / 没加载的看注册表
        NumenPlayer body = NumenPlayer.findByUuid(server, p.uuid());
        boolean callers;
        if (body != null) {
            callers = body.isOwnedByPlayer(owner.getUUID());
        } else {
            CompanionRegistry.Entry e = CompanionRegistry.get(server).find(p.uuid());
            callers = e != null && e.owner().equals(owner.getUUID());
        }
        if (!callers) return;
        Companions.dismiss(server, owner.getUUID(), List.of(p.uuid()));
    }
}
