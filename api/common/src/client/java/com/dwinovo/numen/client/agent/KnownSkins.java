package com.dwinovo.numen.client.agent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Companion skin lookup, done the way vanilla TAB does it: skins live in the
 * connection's player-info table, NOT on the entity — companions are placed via
 * {@code PlayerList.placeNewPlayer} (same mechanism as Carpet bots), so their info
 * entry persists at ANY distance while the entity itself is only synced within
 * tracking range. Resolving through the entity was the old bug: past tracking
 * range the avatar snapped back to the default Steve/Alex.
 *
 * <p>The last-known cache stays as a belt for transient windows (dimension hops,
 * brief re-login races); the default skin only ever shows for a companion whose
 * info entry has never been seen this session.
 */
public final class KnownSkins {

    private static final Map<UUID, PlayerSkin> LAST = new ConcurrentHashMap<>();

    private KnownSkins() {}

    /** The companion's skin: player-info table first (distance-independent, and
     *  remembered), else last known, else default. */
    public static PlayerSkin of(UUID uuid) {
        var conn = Minecraft.getInstance().getConnection();
        if (conn != null) {
            PlayerInfo info = conn.getPlayerInfo(uuid);
            if (info != null) {
                PlayerSkin s = info.getSkin();
                LAST.put(uuid, s);
                return s;
            }
        }
        PlayerSkin cached = LAST.get(uuid);
        return cached != null ? cached : DefaultPlayerSkin.get(uuid);
    }

    /** World left — cached textures die with the connection. */
    public static void clear() {
        LAST.clear();
    }
}
