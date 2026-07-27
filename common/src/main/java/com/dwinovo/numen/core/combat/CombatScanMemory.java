package com.dwinovo.numen.core.combat;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Carries a fixed scan boundary into the background attack created from its ids. */
public final class CombatScanMemory {
    private final int maxSnapshotsPerCompanion;
    private final long ttl;
    private final Map<UUID, ArrayDeque<Snapshot>> snapshots = new HashMap<>();

    public CombatScanMemory(int maxSnapshotsPerCompanion, long ttl) {
        if (maxSnapshotsPerCompanion < 1 || ttl < 1L) {
            throw new IllegalArgumentException("combat scan memory limits must be positive");
        }
        this.maxSnapshotsPerCompanion = maxSnapshotsPerCompanion;
        this.ttl = ttl;
    }

    public synchronized void record(
        UUID companionId,
        String dimension,
        CombatArea area,
        Collection<Integer> entityIds,
        long now
    ) {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(area, "area");
        pruneExpired(now);
        ArrayDeque<Snapshot> recent = snapshots.computeIfAbsent(companionId, ignored -> new ArrayDeque<>());
        recent.addFirst(new Snapshot(dimension, area, Set.copyOf(new HashSet<>(entityIds)), now));
        while (recent.size() > maxSnapshotsPerCompanion) {
            recent.removeLast();
        }
    }

    public synchronized Optional<CombatArea> find(
        UUID companionId,
        String dimension,
        Collection<Integer> entityIds,
        long now
    ) {
        pruneExpired(now);
        ArrayDeque<Snapshot> recent = snapshots.get(companionId);
        if (recent == null) {
            return Optional.empty();
        }
        Set<Integer> requested = new HashSet<>(entityIds);
        return recent.stream()
            .filter(snapshot -> snapshot.dimension.equals(dimension))
            .filter(snapshot -> snapshot.entityIds.containsAll(requested))
            .map(Snapshot::area)
            .findFirst();
    }

    private void pruneExpired(long now) {
        Iterator<Map.Entry<UUID, ArrayDeque<Snapshot>>> entries = snapshots.entrySet().iterator();
        while (entries.hasNext()) {
            ArrayDeque<Snapshot> recent = entries.next().getValue();
            recent.removeIf(snapshot -> now - snapshot.createdAt > ttl);
            if (recent.isEmpty()) {
                entries.remove();
            }
        }
    }

    private record Snapshot(String dimension, CombatArea area, Set<Integer> entityIds, long createdAt) {
    }
}
