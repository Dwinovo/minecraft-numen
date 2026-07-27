package com.dwinovo.numen.core.mining;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Shares verified removals with short-lived follow-up navigation tasks. */
public final class RecentMiningTargets {
    private static final long RETENTION_TICKS = 600L;

    private record Key(String dimensionId, int x, int y, int z) {
    }

    private static final Map<UUID, LinkedHashMap<Key, Long>> BY_COMPANION =
        new LinkedHashMap<>();

    private RecentMiningTargets() {
    }

    public static synchronized void record(
        UUID companionId,
        String dimensionId,
        int x,
        int y,
        int z,
        long gameTime
    ) {
        if (companionId == null || dimensionId == null) {
            return;
        }
        BY_COMPANION.computeIfAbsent(companionId, ignored -> new LinkedHashMap<>())
            .put(new Key(dimensionId, x, y, z), gameTime);
    }

    public static synchronized boolean contains(
        UUID companionId,
        String dimensionId,
        int x,
        int y,
        int z,
        long gameTime
    ) {
        LinkedHashMap<Key, Long> entries = BY_COMPANION.get(companionId);
        if (entries == null || dimensionId == null) {
            return false;
        }
        entries.entrySet().removeIf(entry -> gameTime - entry.getValue() > RETENTION_TICKS);
        if (entries.isEmpty()) {
            BY_COMPANION.remove(companionId);
            return false;
        }
        return entries.containsKey(new Key(dimensionId, x, y, z));
    }

    public static synchronized void clear(UUID companionId) {
        BY_COMPANION.remove(companionId);
    }
}
