package com.dwinovo.numen.client.data;

import com.dwinovo.numen.agent.request.BodySnapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Client-side cache of a companion's backpack, fed by {@code NumenStatePayload}
 * and read by the Items tab. Other players' inventories aren't synced to clients,
 * so this only holds what an explicit request fetched. Client main thread only.
 */
public final class ClientNumenState {

    private static final Map<UUID, BodySnapshot> CACHE = new HashMap<>();

    private ClientNumenState() {}

    public static void update(UUID uuid, BodySnapshot snapshot) {
        CACHE.put(uuid, snapshot);
    }

    public static Optional<BodySnapshot> get(UUID uuid) {
        return Optional.ofNullable(CACHE.get(uuid));
    }

    public static void clear() {
        CACHE.clear();
    }
}
