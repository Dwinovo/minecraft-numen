package com.dwinovo.numen.permission;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 每主人一份:他手下每只同伴的{@link Mode 模式}。存在主世界的存档数据里,文件名带主人 UUID。
 * 面板改它是后面的事;这里只是它的家。
 */
public final class PermissionStore extends SavedData {

    private static final Codec<PermissionStore> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING)
                    .fieldOf("modes").forGetter(s -> s.modeNames())
    ).apply(i, PermissionStore::new));

    private static final SavedData.Factory<PermissionStore> FACTORY = new SavedData.Factory<>(
            PermissionStore::new, PermissionStore::load, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private final Map<UUID, Mode> modes = new HashMap<>();

    PermissionStore() {
    }

    private PermissionStore(Map<UUID, String> modeNames) {
        modeNames.forEach((uuid, name) -> modes.put(uuid, Mode.byName(name)));
    }

    public static PermissionStore of(MinecraftServer server, UUID owner) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "numen_permissions_" + owner);
    }

    static PermissionStore load(CompoundTag tag, HolderLookup.Provider registries) {
        return CODEC.parse(NbtOps.INSTANCE, tag).result().orElseGet(PermissionStore::new);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CODEC.encodeStart(NbtOps.INSTANCE, this).result()
                .ifPresent(t -> { if (t instanceof CompoundTag c) tag.merge(c); });
        return tag;
    }

    /** 这只同伴的模式;没设过是 {@link Mode#ASK}。 */
    public Mode modeOf(UUID companion) {
        return modes.getOrDefault(companion, Mode.ASK);
    }

    public void setMode(UUID companion, Mode mode) {
        modes.put(companion, mode);
        setDirty();
    }

    private Map<UUID, String> modeNames() {
        Map<UUID, String> out = new HashMap<>();
        modes.forEach((uuid, mode) -> out.put(uuid, mode.name().toLowerCase(java.util.Locale.ROOT)));
        return out;
    }
}
