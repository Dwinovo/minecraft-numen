package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.network.NumenPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client → Server: "where are these companions of mine right now?" Sent by the
 * roster panel (and the chat vitals strip) for entities the client can't see —
 * a working companion may be ticking in chunk-ticket-loaded terrain thousands
 * of blocks away, or in another dimension.
 *
 * <p>Answered with one {@link NumenLocationsPayload} carrying a snapshot per
 * requested UUID. Ownership is enforced per entity: UUIDs that don't resolve
 * to a pet owned by the sender come back {@code found=false} (no oracle for
 * other players' pets).
 */
public record LocateNumenPayload(List<UUID> entityUuids) implements NumenPayload {

    /** Roster panels are small; cap defends against garbage input. */
    public static final int MAX_UUIDS = 16;

    public static final ResourceLocation ID =
            new ResourceLocation(Constants.MOD_ID, "locate_numen");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        int n = Math.min(entityUuids.size(), MAX_UUIDS);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeUUID(entityUuids.get(i));
        }
    }

    public static LocateNumenPayload read(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX_UUIDS);
        List<UUID> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(buf.readUUID());
        }
        return new LocateNumenPayload(list);
    }

    /** Handler invoked on the server main thread. */
    public static void handle(LocateNumenPayload p, ServerPlayer player) {
        List<NumenLocationsPayload.Snapshot> out = new ArrayList<>(p.entityUuids().size());
        CompanionRegistry registry = CompanionRegistry.get(player.level().getServer());
        for (UUID uuid : p.entityUuids()) {
            NumenPlayer numen = NumenPlayer.findByUuid(player.level().getServer(), uuid);
            if (numen != null && numen.isOwnedByPlayer(player.getUUID())) {
                out.add(NumenLocationsPayload.Snapshot.live(uuid,
                        numen.getX(), numen.getY(), numen.getZ(),
                        numen.level().dimension().location().toString(),
                        numen.getHealth(), numen.getMaxHealth()));
                continue;
            }
            // Dormant (owner logged out, or not yet respawned this session): fall
            // back to the persistent companion index so the roster can show WHERE
            // it sleeps instead of a useless "offline". Owner-gated — the registry
            // carries ownership and the respawn-hint position.
            CompanionRegistry.Entry entry = registry.find(uuid);
            if (numen == null && entry != null && entry.owner().equals(player.getUUID())) {
                var pos = entry.pos();
                out.add(NumenLocationsPayload.Snapshot.lastSeen(uuid,
                        pos.getX(), pos.getY(), pos.getZ(),
                        entry.dimension().location().toString()));
                continue;
            }
            out.add(NumenLocationsPayload.Snapshot.notFound(uuid));
        }
        Services.NETWORK.sendToPlayer(player, new NumenLocationsPayload(out));
    }
}
