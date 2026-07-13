package com.dwinovo.numen.client.data;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Client cache for revisioned, server-pushed task-page state. */
public final class ClientTaskList {
    public record Entry(String toolCallId, String toolName, String description,
                        String state, boolean active, boolean paused,
                        int progressCurrent, int progressTotal, String phase,
                        String blocker, int etaSeconds) { }
    public record Snapshot(long revision, boolean queuePaused, boolean inventoryLocked,
                           List<Entry> tasks, long receivedAt) {
        public Snapshot { tasks = List.copyOf(tasks); }
    }

    private static final Map<UUID, Snapshot> CACHE = new ConcurrentHashMap<>();
    private ClientTaskList() { }

    public static Snapshot get(UUID uuid) { return CACHE.get(uuid); }
    public static void put(UUID uuid, Snapshot snapshot) {
        CACHE.compute(uuid, (ignored, current) -> current == null || snapshot.revision() >= current.revision()
                ? snapshot : current);
    }
    public static void clear() { CACHE.clear(); }
}
