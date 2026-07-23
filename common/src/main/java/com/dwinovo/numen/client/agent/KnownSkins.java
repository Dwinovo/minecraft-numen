package com.dwinovo.numen.client.agent;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last-known companion skins. The GUI resolves a companion's skin off its live client
 * entity — which vanishes past render distance, and the avatar used to snap back to
 * the default Steve/Alex. Every successful resolve caches the skin here, so a
 * companion keeps its face at ANY distance; the default is only ever shown for a
 * companion whose entity has never been seen this session.
 */
public final class KnownSkins {

    private static final Map<UUID, PlayerSkin> LAST = new ConcurrentHashMap<>();

    private KnownSkins() {}

    /** The companion's skin: live entity first (and remembered), else last known, else default. */
    public static PlayerSkin of(UUID uuid) {
        AbstractClientPlayer e = ClientNumenLookup.resolve(uuid);
        if (e != null) {
            PlayerSkin s = e.getSkin();
            LAST.put(uuid, s);
            return s;
        }
        PlayerSkin cached = LAST.get(uuid);
        return cached != null ? cached : DefaultPlayerSkin.get(uuid);
    }

    /** World left — cached textures die with the connection. */
    public static void clear() {
        LAST.clear();
    }
}
