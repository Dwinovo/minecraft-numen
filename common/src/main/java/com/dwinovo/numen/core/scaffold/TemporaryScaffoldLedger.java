package com.dwinovo.numen.core.scaffold;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tracks only navigation-created blocks so explicit construction is never reclaimed. */
public final class TemporaryScaffoldLedger {
    public record Entry(
        String dimensionId,
        int x,
        int y,
        int z,
        String placedBlockId,
        String previousBlockId,
        long placedAtGameTime
    ) {
    }

    public record Report(
        String dimensionId,
        int x,
        int y,
        int z,
        String placedBlockId,
        String reason
    ) {
    }

    private record Key(String dimensionId, int x, int y, int z) {
    }

    private static final Map<UUID, LinkedHashMap<Key, Entry>> BY_COMPANION =
        new LinkedHashMap<>();
    private static final Map<UUID, LinkedHashMap<Key, String>> REASONS =
        new LinkedHashMap<>();

    private TemporaryScaffoldLedger() {
    }

    public static synchronized boolean recordPlacement(
        UUID companionId,
        String dimensionId,
        int x,
        int y,
        int z,
        String placedBlockId,
        String previousBlockId,
        boolean explicitBuildTarget,
        long placedAtGameTime
    ) {
        if (explicitBuildTarget
            || companionId == null
            || dimensionId == null
            || placedBlockId == null) {
            return false;
        }

        Key key = new Key(dimensionId, x, y, z);
        LinkedHashMap<Key, Entry> entries = BY_COMPANION.computeIfAbsent(
            companionId,
            ignored -> new LinkedHashMap<>()
        );
        Entry existing = entries.get(key);
        entries.put(
            key,
            new Entry(
                dimensionId,
                x,
                y,
                z,
                placedBlockId,
                existing == null ? previousBlockId : existing.previousBlockId(),
                existing == null ? placedAtGameTime : existing.placedAtGameTime()
            )
        );
        REASONS.computeIfAbsent(companionId, ignored -> new LinkedHashMap<>())
            .put(key, "pending_safety_recheck");
        return true;
    }

    public static synchronized List<Entry> entries(UUID companionId) {
        Map<Key, Entry> entries = BY_COMPANION.get(companionId);
        if (entries == null) {
            return List.of();
        }
        return List.copyOf(new ArrayList<>(entries.values()));
    }

    public static synchronized List<Entry> topmostEntries(UUID companionId) {
        List<Entry> entries = entries(companionId);
        return entries.stream()
            .filter(candidate -> entries.stream().noneMatch(other ->
                other.dimensionId().equals(candidate.dimensionId())
                    && other.x() == candidate.x()
                    && other.z() == candidate.z()
                    && other.y() > candidate.y()
            ))
            .toList();
    }

    public static synchronized boolean contains(
        UUID companionId,
        String dimensionId,
        int x,
        int y,
        int z
    ) {
        Map<Key, Entry> entries = BY_COMPANION.get(companionId);
        return entries != null && entries.containsKey(new Key(dimensionId, x, y, z));
    }

    public static synchronized void markExplicitBuildTarget(
        UUID companionId,
        String dimensionId,
        int x,
        int y,
        int z
    ) {
        if (companionId == null || dimensionId == null) {
            return;
        }

        removeKey(companionId, new Key(dimensionId, x, y, z));
    }

    public static synchronized void remove(UUID companionId, Entry entry) {
        if (entry == null) {
            return;
        }

        removeKey(
            companionId,
            new Key(entry.dimensionId(), entry.x(), entry.y(), entry.z())
        );
    }

    private static void removeKey(UUID companionId, Key key) {
        LinkedHashMap<Key, Entry> entries = BY_COMPANION.get(companionId);
        if (entries == null) {
            return;
        }

        entries.remove(key);
        LinkedHashMap<Key, String> reasons = REASONS.get(companionId);
        if (reasons != null) {
            reasons.remove(key);
            if (reasons.isEmpty()) {
                REASONS.remove(companionId);
            }
        }
        if (entries.isEmpty()) {
            BY_COMPANION.remove(companionId);
        }
    }

    public static synchronized void clear(UUID companionId) {
        BY_COMPANION.remove(companionId);
        REASONS.remove(companionId);
    }

    public static synchronized void markReason(UUID companionId, Entry entry, String reason) {
        LinkedHashMap<Key, Entry> entries = BY_COMPANION.get(companionId);
        if (entries == null || entry == null || reason == null) {
            return;
        }
        Key key = new Key(entry.dimensionId(), entry.x(), entry.y(), entry.z());
        if (!entries.containsKey(key)) {
            return;
        }
        REASONS.computeIfAbsent(companionId, ignored -> new LinkedHashMap<>())
            .put(key, reason);
    }

    public static synchronized List<Report> reports(UUID companionId) {
        LinkedHashMap<Key, Entry> entries = BY_COMPANION.get(companionId);
        if (entries == null) {
            return List.of();
        }
        Map<Key, String> reasons = REASONS.getOrDefault(companionId, new LinkedHashMap<>());
        List<Report> reports = new ArrayList<>();
        for (Map.Entry<Key, Entry> item : entries.entrySet()) {
            Entry entry = item.getValue();
            reports.add(
                new Report(
                    entry.dimensionId(),
                    entry.x(),
                    entry.y(),
                    entry.z(),
                    entry.placedBlockId(),
                    reasons.getOrDefault(item.getKey(), "pending_safety_recheck")
                )
            );
        }
        return List.copyOf(reports);
    }
}
