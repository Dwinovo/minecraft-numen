package com.dwinovo.numen.entity;

import com.dwinovo.numen.Constants;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Spawns and despawns companion {@link NumenPlayer} bodies through the vanilla
 * player-join path — entirely public API, no loader-specific construction needed
 * (the only fake piece is {@link FakeConnection}, which is common).
 *
 * <p>{@link net.minecraft.server.players.PlayerList#placeNewPlayer} adds the body
 * to the player list (→ chunk loading for free) and to the level, but does NOT
 * load a hand-built fake player's {@code .dat} (that path is tied to the real
 * login flow) — so {@link #spawn} restores position / inventory / owner from disk
 * explicitly afterwards, the way Carpet's {@code EntityPlayerMPFake} does.
 * {@link net.minecraft.server.players.PlayerList#remove} saves that data back and
 * removes the body — so despawn is a clean, persisted dormancy.
 */
@com.dwinovo.numen.api.Internal
public final class CompanionFactory {

    private CompanionFactory() {}

    /**
     * Bring a companion into the world. On first creation pass a {@code pos}
     * (the spawn location, e.g. beside the owner); on a respawn from dormancy
     * pass {@code null} to keep the position restored from its {@code .dat}.
     */
    public static NumenPlayer spawn(MinecraftServer server, UUID companionUuid, String name,
                                     UUID ownerUuid, ServerLevel level, Vec3 pos) {
        GameProfile profile = new GameProfile(companionUuid, name);
        NumenPlayer player = new NumenPlayer(server, level, profile);
        FakeConnection connection = new FakeConnection();
        server.getPlayerList().placeNewPlayer(connection, player);
        // placeNewPlayer does NOT load a hand-built fake player's .dat, so restore
        // it ourselves (Carpet's model): position, inventory, health, owner from
        // disk. Without this a respawned companion spawns at 0,0,0 with no items.
        loadPlayerData(server, player);
        // Companions are always survival, whatever the world's default game type — their whole design
        // (gather/drops, real combat, recoverable death) is survival-shaped, and placeNewPlayer would
        // otherwise hand a creative world's body instabuild (no block drops, breaks auto_mine). Forced
        // here after the .dat restore so a stale saved game type can't override it.
        CompanionPreferences.apply(player, CompanionPreferences.get(server).get(companionUuid));
        // First spawn has no .dat to restore the owner from; set it explicitly.
        if (player.getOwnerUuid() == null) {
            player.setOwnerUuid(ownerUuid);
        }
        // An explicit pos (fresh summon) overrides the restored position; a respawn
        // from dormancy passes null to keep exactly what the .dat restored.
        if (pos != null) {
            player.teleportTo(level, pos.x, pos.y, pos.z, player.getYRot(), player.getXRot());
        }
        return player;
    }

    /**
     * Restore a fake player's saved state from its playerdata {@code .dat}
     * ({@link net.minecraft.server.players.PlayerList#loadPlayerData} +
     * {@link net.minecraft.world.entity.Entity#load}). {@code placeNewPlayer}
     * skips this for hand-constructed players, so we do it like Carpet's
     * {@code loadPlayerData}. No-op on first summon (no file yet).
     */
    private static void loadPlayerData(MinecraftServer server, NumenPlayer player) {
        try {
            java.io.File f = new java.io.File(
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).toFile(),
                player.getStringUUID() + ".dat");
            if (f.exists()) {
                net.minecraft.nbt.CompoundTag data = net.minecraft.nbt.NbtIo.readCompressed(f);
                if (data != null) player.load(data);
            }
        } catch (IOException e) {
            Constants.LOG.warn("Failed to load companion player data for {}", player.getStringUUID(), e);
        }
    }

    /** Save the companion's data and remove it from the world (dormancy). */
    public static void despawn(MinecraftServer server, NumenPlayer player) {
        // Tell tool packs the body is leaving so they can finalize their own
        // per-companion work (e.g. clear a mining crack overlay) instead of leaving
        // it orphaned once the body drops out of the tick loop's player list.
        CompanionLifecycle.fireRemove(player);
        server.getPlayerList().remove(player);
    }
}
