package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client -> Server: the owner asked to permanently delete a companion from the
 * panel (rail x -> confirm). Like a death -- the live body drops its whole
 * inventory at its feet -- then it's dismissed for good (registry entry removed,
 * won't return on login). A dormant (unloaded) companion has no body to drop
 * from, so it's just forgotten (its orphaned {@code .dat} keeps the items but
 * nothing respawns it).
 */
public record DismissRequestPayload(UUID uuid) {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "dismiss_request");

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(uuid);
    }

    public static DismissRequestPayload decode(FriendlyByteBuf buf) {
        return new DismissRequestPayload(buf.readUUID());
    }

    /** Server main thread. */
    public static void handle(DismissRequestPayload p, ServerPlayer owner) {
        MinecraftServer server = ((ServerLevel) owner.level).getServer();
        if (server == null || p.uuid() == null) return;

        NumenPlayer body = NumenPlayer.findByUuid(server, p.uuid());
        if (body != null) {
            if (!body.isOwnedByPlayer(owner.getUUID())) return;   // not the caller's companion
            body.getInventory().dropAll();                        // death-style: drop everything at its feet
            Companions.dismiss(server, body);                     // despawn + forget (no respawn)
        } else {
            // Dormant / not loaded -- verify ownership via the registry, then forget it.
            CompanionRegistry reg = CompanionRegistry.get(server);
            CompanionRegistry.Entry e = reg.find(p.uuid());
            if (e == null || !e.owner().equals(owner.getUUID())) return;
            reg.remove(p.uuid());
        }
        Companions.syncRosterToOwner(server, owner);              // push the trimmed roster to the owner
    }
}
