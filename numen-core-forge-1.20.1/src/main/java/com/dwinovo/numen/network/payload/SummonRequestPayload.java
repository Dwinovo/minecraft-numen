package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.Companions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client -> Server: the owner asked to summon a companion by name from the panel's
 * "+" button. Mirrors the {@code /numen player summon} command -- summon is
 * idempotent per (owner, name), so re-summoning an existing name just wakes it.
 */
public record SummonRequestPayload(String name) {

    public static final int MAX_NAME = 32;

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "summon_request");

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(name);
    }

    public static SummonRequestPayload decode(FriendlyByteBuf buf) {
        return new SummonRequestPayload(buf.readUtf());
    }

    /** Server main thread. */
    public static void handle(SummonRequestPayload p, ServerPlayer owner) {
        String name = p.name() == null ? "" : p.name().trim();
        if (name.isEmpty() || name.length() > MAX_NAME) return;
        ServerLevel level = (ServerLevel) owner.level;
        if (Companions.summon(level.getServer(), owner.getUUID(), name, level, owner.position()) == null) {
            long now = level.getServer().overworld().getGameTime();
            for (var entry : com.dwinovo.numen.entity.CompanionRegistry.get(level.getServer()).ownedBy(owner.getUUID())) {
                if (!entry.getValue().name().equals(name) || entry.getValue().diedAt() <= 0) continue;
                int seconds = com.dwinovo.numen.entity.CompanionPreferences.get(level.getServer()).get(entry.getKey()).respawnSeconds();
                long remainingMs = Math.max(0, seconds * 1000L - (now - entry.getValue().diedAt()) * 50L);
                com.dwinovo.numen.platform.Services.NETWORK.sendToPlayer(owner,
                        NumenDeathPayload.ID, new NumenDeathPayload(entry.getKey(), entry.getValue().deathCause(), remainingMs));
                break;
            }
        }
        Companions.syncRosterToOwner(level.getServer(), owner);   // push the new roster to the owner
    }
}
