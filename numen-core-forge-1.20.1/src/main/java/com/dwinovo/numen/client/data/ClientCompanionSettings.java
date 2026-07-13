package com.dwinovo.numen.client.data;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Client cache for per-companion gameplay attributes. */
public final class ClientCompanionSettings {
    public record Snapshot(String gameMode, double maxHealth, double attackDamage,
                           double attackSpeed, double movementSpeed, double armor,
                           double armorToughness, double knockbackResistance, double luck,
                           boolean invulnerable, int respawnSeconds, long receivedAt) {}
    private static final Map<UUID, Snapshot> CACHE = new ConcurrentHashMap<>();
    private ClientCompanionSettings() {}
    public static Snapshot get(UUID uuid) { return CACHE.get(uuid); }
    public static void put(UUID uuid, Snapshot snapshot) { CACHE.put(uuid, snapshot); }
    public static void clear() { CACHE.clear(); }
}
