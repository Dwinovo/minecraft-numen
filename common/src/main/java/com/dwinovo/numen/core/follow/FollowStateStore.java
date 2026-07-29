package com.dwinovo.numen.core.follow;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Independent world-saved follow intent, keyed only by companion UUID.
 *
 * <p>The file intentionally does not extend the companion registry or player
 * NBT. Removing a modified Numen JAR therefore leaves an unreferenced SavedData
 * file that the original mod can safely ignore.
 */
public final class FollowStateStore extends SavedData {

    public static final String DATA_NAME = "numen_auto_follow";

    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_COMPANION_UUID = "companionUuid";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_MANUAL_PAUSED = "manualPaused";
    private static final String KEY_SCHEMA_VERSION = "schemaVersion";
    private static final String KEY_STOP_DISTANCE = "stopDistanceOverride";
    private static final String KEY_START_DISTANCE = "startDistanceOverride";

    private final Map<UUID, FollowState> states;

    FollowStateStore() {
        this.states = new HashMap<>();
    }

    private FollowStateStore(Map<UUID, FollowState> states) {
        this.states = new HashMap<>(states);
    }

    public static FollowStateStore get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(FollowStateStore::load, FollowStateStore::new, DATA_NAME);
    }

    static FollowStateStore load(CompoundTag root) {
        if (root == null || !root.contains(KEY_ENTRIES, Tag.TAG_LIST)) {
            return new FollowStateStore();
        }

        Map<UUID, FollowState> loaded = new HashMap<>();
        try {
            ListTag entries = root.getList(KEY_ENTRIES, Tag.TAG_COMPOUND);
            for (Tag element : entries) {
                if (!(element instanceof CompoundTag entry)) {
                    continue;
                }
                try {
                    readEntry(entry).ifPresent(state ->
                            loaded.put(entry.getUUID(KEY_COMPANION_UUID), state));
                } catch (RuntimeException ignored) {
                    // A malformed companion entry is isolated from every other entry.
                }
            }
        } catch (RuntimeException ignored) {
            // A malformed top-level payload is equivalent to no follow state.
            return new FollowStateStore();
        }
        return new FollowStateStore(loaded);
    }

    private static Optional<FollowState> readEntry(CompoundTag entry) {
        if (!entry.hasUUID(KEY_COMPANION_UUID)) {
            return Optional.empty();
        }

        boolean enabled = readOptionalBoolean(entry, KEY_ENABLED, false);
        boolean manualPaused = readOptionalBoolean(entry, KEY_MANUAL_PAUSED, false);
        int schemaVersion = readOptionalInt(entry, KEY_SCHEMA_VERSION,
                FollowState.CURRENT_SCHEMA_VERSION);
        Double stopDistance = readOptionalDistance(entry, KEY_STOP_DISTANCE);
        Double startDistance = readOptionalDistance(entry, KEY_START_DISTANCE);

        return Optional.of(new FollowState(enabled, manualPaused, schemaVersion,
                stopDistance, startDistance));
    }

    private static boolean readOptionalBoolean(CompoundTag entry, String key, boolean defaultValue) {
        if (!entry.contains(key)) {
            return defaultValue;
        }
        requireNumeric(entry, key);
        return entry.getBoolean(key);
    }

    private static int readOptionalInt(CompoundTag entry, String key, int defaultValue) {
        if (!entry.contains(key)) {
            return defaultValue;
        }
        requireNumeric(entry, key);
        return entry.getInt(key);
    }

    private static Double readOptionalDistance(CompoundTag entry, String key) {
        if (!entry.contains(key)) {
            return null;
        }
        requireNumeric(entry, key);
        return entry.getDouble(key);
    }

    private static void requireNumeric(CompoundTag entry, String key) {
        if (!entry.contains(key, Tag.TAG_ANY_NUMERIC)) {
            throw new IllegalArgumentException("follow state field '" + key + "' is not numeric");
        }
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag entries = new ListTag();
        states.entrySet().stream()
                .sorted(Map.Entry.comparingByKey((left, right) ->
                        left.toString().compareTo(right.toString())))
                .forEach(saved -> entries.add(writeEntry(saved.getKey(), saved.getValue())));
        root.put(KEY_ENTRIES, entries);
        return root;
    }

    private static CompoundTag writeEntry(UUID companionUuid, FollowState state) {
        CompoundTag entry = new CompoundTag();
        entry.putUUID(KEY_COMPANION_UUID, companionUuid);
        entry.putBoolean(KEY_ENABLED, state.enabled());
        entry.putBoolean(KEY_MANUAL_PAUSED, state.manualPaused());
        entry.putInt(KEY_SCHEMA_VERSION, state.schemaVersion());
        if (state.stopDistanceOverride() != null) {
            entry.putDouble(KEY_STOP_DISTANCE, state.stopDistanceOverride());
        }
        if (state.startDistanceOverride() != null) {
            entry.putDouble(KEY_START_DISTANCE, state.startDistanceOverride());
        }
        return entry;
    }

    public FollowState getOrDefault(UUID companionUuid) {
        return states.getOrDefault(companionUuid, FollowState.defaults());
    }

    public Optional<FollowState> find(UUID companionUuid) {
        return Optional.ofNullable(states.get(companionUuid));
    }

    public Map<UUID, FollowState> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(states));
    }

    public int size() {
        return states.size();
    }

    public void put(UUID companionUuid, FollowState state) {
        if (companionUuid == null || state == null) {
            throw new IllegalArgumentException("companion UUID and follow state are required");
        }
        if (!state.equals(states.put(companionUuid, state))) {
            setDirty();
        }
    }

    public void setEnabled(UUID companionUuid, boolean enabled) {
        put(companionUuid, getOrDefault(companionUuid).withEnabled(enabled));
    }

    public void setManualPaused(UUID companionUuid, boolean manualPaused) {
        put(companionUuid, getOrDefault(companionUuid).withManualPaused(manualPaused));
    }

    public void setDistanceOverrides(UUID companionUuid, Double stopDistance, Double startDistance) {
        put(companionUuid, getOrDefault(companionUuid)
                .withDistanceOverrides(stopDistance, startDistance));
    }

    public void remove(UUID companionUuid) {
        if (states.remove(companionUuid) != null) {
            setDirty();
        }
    }
}
