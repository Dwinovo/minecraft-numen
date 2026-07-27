package com.dwinovo.numen.core.combat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Records real living-entity deaths long enough for asynchronous combat tasks to reconcile them. */
public final class CombatDeathLedger {
    private final long ttl;
    private final Map<Key, Long> deaths = new HashMap<>();

    public CombatDeathLedger(long ttl) {
        if (ttl < 1L) {
            throw new IllegalArgumentException("combat death ttl must be positive");
        }
        this.ttl = ttl;
    }

    public synchronized void recordDeath(
        String dimension,
        int entityId,
        UUID entityUuid,
        long deathTime
    ) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(entityUuid, "entityUuid");
        prune(deathTime);
        deaths.put(new Key(dimension, entityId, entityUuid), deathTime);
    }

    public synchronized boolean diedSince(
        String dimension,
        int entityId,
        UUID entityUuid,
        long taskStart,
        long now
    ) {
        prune(now);
        Long deathTime = deaths.get(new Key(dimension, entityId, entityUuid));
        return deathTime != null && deathTime >= taskStart;
    }

    private void prune(long now) {
        Iterator<Long> times = deaths.values().iterator();
        while (times.hasNext()) {
            long recordedAt = times.next();
            if (recordedAt > now || now - recordedAt > ttl) {
                times.remove();
            }
        }
    }

    private record Key(String dimension, int entityId, UUID entityUuid) {
    }
}
