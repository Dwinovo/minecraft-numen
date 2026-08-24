package com.dwinovo.numen.client.data;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Client-side cache of server-reported companion locations, fed by
 * {@code NumenLocationsPayload} and read by the roster panel / chat vitals.
 * Exists because a far-away companion isn't tracked by the client at all —
 * its position only reaches us through an explicit locate round-trip.
 *
 * <p>Client main thread only. Entries are overwritten per response; staleness
 * is judged by the reader via {@link Snapshot#receivedAtMs}.
 */
public final class ClientNumenLocations {

    /**
     * One locate answer. {@code found=false} = the server knows nothing;
     * {@code found && !loaded} = asleep in unloaded chunks at its last known
     * position (vitals invalid, position trustworthy — a chat wakes it).
     */
    public record Snapshot(boolean found, boolean loaded, double x, double y, double z,
                           String dimension, float hp, float maxHp, long receivedAtMs) {}

    private static final Map<UUID, Snapshot> CACHE = new HashMap<>();

    private ClientNumenLocations() {}

    public static void update(UUID uuid, Snapshot snapshot) {
        CACHE.put(uuid, snapshot);
    }

    public static Optional<Snapshot> get(UUID uuid) {
        return Optional.ofNullable(CACHE.get(uuid));
    }

    public static void clear() {
        CACHE.clear();
    }
}
