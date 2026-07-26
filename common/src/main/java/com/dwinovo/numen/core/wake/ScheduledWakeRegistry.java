package com.dwinovo.numen.core.wake;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.api.NumenEvents;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent one-shot wake leases created by {@code schedule_wake}. These are
 * timers for the client-side brain, not body tasks: they never enter the LLM
 * task lane and therefore never reserve movement, combat, or another tool.
 *
 * <p>Deadlines use the overworld's monotonic {@code gameTime}. A paused/stopped
 * world does not burn the timer; a running dedicated server does. Once due, a
 * lease remains pending while the owner is offline or the body is dormant and
 * is consumed only after its authorized-wake packet is actually sent.
 */
public final class ScheduledWakeRegistry extends SavedData {

    public static final int MAX_PER_COMPANION = 8;
    private static final String EVENT_KIND = "scheduled_wake";
    private static final long CHECK_INTERVAL_TICKS = 20L;
    private static final String DATA_NAME = "numen_core_scheduled_wakes";

    public record Entry(UUID id, UUID companion, long createdGameTime,
                        long dueGameTime, String reason) {}

    private static final SavedData.Factory<ScheduledWakeRegistry> FACTORY =
            new SavedData.Factory<>(ScheduledWakeRegistry::new, ScheduledWakeRegistry::load,
                    net.minecraft.util.datafix.DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private final Map<UUID, Entry> entries = new HashMap<>();
    private long nextCheckGameTime = Long.MIN_VALUE;

    public static ScheduledWakeRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Loader hook: inexpensive one-second cadence; no LLM call unless a due lease is delivered. */
    public static void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        ScheduledWakeRegistry registry = get(server);
        if (registry.nextCheckGameTime != Long.MIN_VALUE && now < registry.nextCheckGameTime) return;
        registry.nextCheckGameTime = now + CHECK_INTERVAL_TICKS;
        registry.deliverDue(server, now);
    }

    public Entry schedule(UUID companion, long nowGameTime, int seconds, String reason) {
        if (list(companion).size() >= MAX_PER_COMPANION) {
            throw new IllegalStateException("at most " + MAX_PER_COMPANION
                    + " scheduled wakes are allowed per companion");
        }
        UUID id;
        do id = UUID.randomUUID(); while (entries.containsKey(id));
        Entry entry = new Entry(id, companion, nowGameTime, nowGameTime + seconds * 20L, reason);
        entries.put(id, entry);
        setDirty();
        return entry;
    }

    public List<Entry> list(UUID companion) {
        return entries.values().stream()
                .filter(e -> e.companion().equals(companion))
                .sorted(Comparator.comparingLong(Entry::dueGameTime))
                .toList();
    }

    public boolean cancel(UUID companion, UUID id) {
        Entry entry = entries.get(id);
        if (entry == null || !entry.companion().equals(companion)) return false;
        entries.remove(id);
        setDirty();
        return true;
    }

    /** Package-private pure seam used by tests and the delivery pass. */
    List<Entry> due(long nowGameTime) {
        return entries.values().stream()
                .filter(e -> e.dueGameTime() <= nowGameTime)
                .sorted(Comparator.comparingLong(Entry::dueGameTime))
                .toList();
    }

    private void deliverDue(MinecraftServer server, long nowGameTime) {
        if (entries.isEmpty()) return;
        List<UUID> consumed = new ArrayList<>();

        for (Entry entry : due(nowGameTime)) {
            if (!NumenPlayer.isRegistered(server, entry.companion())) {
                consumed.add(entry.id());
                continue;
            }
            // Unavailable registered bodies may be dormant, dead, or temporarily unloaded.
            // Keep the durable lease and retry after the body returns.
            NumenPlayer body = NumenPlayer.findByUuid(server, entry.companion());
            if (body == null) continue;

            if (NumenEvents.emitAuthorizedWake(body, EVENT_KIND,
                    Map.of("id", entry.id().toString()),
                    "Scheduled reminder is due: " + entry.reason()
                            + ". Re-check the current world state before deciding the next action.")) {
                consumed.add(entry.id());
            }
        }

        if (!consumed.isEmpty()) {
            consumed.forEach(entries::remove);
            setDirty();
            Constants.LOG.info("[numen-core] delivered/cleared {} scheduled wake(s)", consumed.size());
        }
    }

    public static long remainingSeconds(Entry entry, long nowGameTime) {
        long ticks = Math.max(0L, entry.dueGameTime() - nowGameTime);
        return (ticks + 19L) / 20L;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag wakes = new ListTag();
        for (Entry entry : entries.values()) {
            CompoundTag wake = new CompoundTag();
            wake.putUUID("id", entry.id());
            wake.putUUID("companion", entry.companion());
            wake.putLong("createdGameTime", entry.createdGameTime());
            wake.putLong("dueGameTime", entry.dueGameTime());
            wake.putString("reason", entry.reason());
            wakes.add(wake);
        }
        tag.put("wakes", wakes);
        return tag;
    }

    static ScheduledWakeRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        ScheduledWakeRegistry registry = new ScheduledWakeRegistry();
        ListTag wakes = tag.getList("wakes", Tag.TAG_COMPOUND);
        for (int i = 0; i < wakes.size(); i++) {
            CompoundTag wake = wakes.getCompound(i);
            if (!wake.hasUUID("id") || !wake.hasUUID("companion")) continue;
            Entry entry = new Entry(
                    wake.getUUID("id"),
                    wake.getUUID("companion"),
                    wake.getLong("createdGameTime"),
                    wake.getLong("dueGameTime"),
                    wake.getString("reason"));
            registry.entries.put(entry.id(), entry);
        }
        return registry;
    }
}
