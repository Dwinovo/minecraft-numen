package com.dwinovo.numen.core.task.pin;

import com.dwinovo.numen.entity.NumenPlayer;
import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * World-saved intent pins, keyed by companion UUID — the persistence ride for
 * {@link IntentPins}. The companion body itself persists as a vanilla player
 * {@code .dat} inside numen-api ({@code NumenPlayer.addAdditionalSaveData}),
 * which core cannot extend without an api change; so like the api's own
 * {@code CompanionRegistry}, pins live in one overworld {@code SavedData} file
 * ({@code numen_intent_pins}) for all companions. Mutations mark the data dirty
 * through each {@link IntentPins}' onChange hook.
 */
public final class IntentPinsData extends SavedData {

    private static final Codec<Map<UUID, Map<String, String>>> CODEC =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC,
                            Codec.unboundedMap(Codec.STRING, Codec.STRING))
                    .fieldOf("pins").codec();

    private final Map<UUID, IntentPins> byCompanion = new HashMap<>();

    private IntentPinsData() {}

    private IntentPinsData(Map<UUID, Map<String, String>> snapshot) {
        snapshot.forEach((uuid, pins) ->
                byCompanion.put(uuid, IntentPins.fromSnapshot(pins, this::setDirty)));
    }

    // 1.20.1 predates SavedData.Factory and the HolderLookup-aware save/load; register via the
    // classic computeIfAbsent(loadFn, factory, name) and (de)serialise through CODEC ourselves.
    private static IntentPinsData load(CompoundTag tag) {
        return CODEC.parse(NbtOps.INSTANCE, tag).result()
                .map(IntentPinsData::new).orElseGet(IntentPinsData::new);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        Map<UUID, Map<String, String>> snapshot = new LinkedHashMap<>();
        byCompanion.forEach((uuid, pins) -> {
            if (!pins.isEmpty()) snapshot.put(uuid, pins.snapshot());
        });
        CODEC.encodeStart(NbtOps.INSTANCE, snapshot).result()
                .ifPresent(t -> { if (t instanceof CompoundTag c) tag.merge(c); });
        return tag;
    }

    public static IntentPinsData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(IntentPinsData::load, IntentPinsData::new, "numen_intent_pins");
    }

    /** One companion's pin table (created empty on first touch). */
    public IntentPins pins(UUID companionUuid) {
        return byCompanion.computeIfAbsent(companionUuid, u -> new IntentPins(this::setDirty));
    }

    /**
     * The one lookup everything server-side uses: this companion's pins. Falls
     * back to a detached, non-persistent table if the body has no server (never
     * the case on the tick thread; belt-and-braces so callers can't NPE).
     */
    public static IntentPins pinsFor(NumenPlayer companion) {
        MinecraftServer server = companion.getServer();
        if (server == null) return new IntentPins();
        return get(server).pins(companion.getUUID());
    }
}
