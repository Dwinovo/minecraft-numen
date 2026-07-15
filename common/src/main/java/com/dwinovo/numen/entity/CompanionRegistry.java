package com.dwinovo.numen.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent index of every companion that exists, keyed by companion UUID.
 * The companion BODY (inventory, position, owner) persists for free as a vanilla
 * player {@code .dat}, but vanilla never enumerates the {@code playerdata/}
 * folder for players that aren't logging in — so without this index we couldn't
 * know which companions to recreate, or who owns them, while they sit dormant.
 *
 * <p>World-saved on the overworld data storage (one file, all owners' companions).
 * The {@code dimension}/{@code pos} are a respawn hint (which level to construct
 * the body in); the {@code .dat} carries the authoritative restored state.
 */
@com.dwinovo.numen.api.Internal
public final class CompanionRegistry extends SavedData {

    /** One companion's catalog entry. {@code diedAt > 0} = dead, awaiting a respawn-at-owner (the death
     *  state is persisted here so it SURVIVES a logout during the respawn window — see Companions).
     *  {@code skinValue}/{@code skinSig} = 借来的正版皮肤(Mojang 签名的 textures 属性),
     *  空串 = 无皮肤,客户端回落原版默认皮肤(按 UUID 哈希抽取)。 */
    public record Entry(String name, UUID owner, ResourceKey<Level> dimension, BlockPos pos,
                        String deathCause, long diedAt, String skinValue, String skinSig) {
        /** A live companion (not dead), no borrowed skin. */
        public Entry(String name, UUID owner, ResourceKey<Level> dimension, BlockPos pos) {
            this(name, owner, dimension, pos, "", 0L, "", "");
        }

        /** 刷新落点(休眠/移动时的 respawn 提示),皮肤与死亡状态原样保留。 */
        public Entry movedTo(ResourceKey<Level> dimension, BlockPos pos) {
            return new Entry(name, owner, dimension, pos, deathCause, diedAt, skinValue, skinSig);
        }

        /** 换上 Mojang 签名的皮肤数据(value+signature)。 */
        public Entry withSkin(String value, String sig) {
            return new Entry(name, owner, dimension, pos, deathCause, diedAt,
                    value == null ? "" : value, sig == null ? "" : sig);
        }

        Entry dead(String cause, long at) {
            return new Entry(name, owner, dimension, pos, cause, at, skinValue, skinSig);
        }

        Entry alive() {
            return new Entry(name, owner, dimension, pos, "", 0L, skinValue, skinSig);
        }

        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(Entry::name),
                UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(Entry::owner),
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Entry::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
                Codec.STRING.optionalFieldOf("deathCause", "").forGetter(Entry::deathCause),
                Codec.LONG.optionalFieldOf("diedAt", 0L).forGetter(Entry::diedAt),
                Codec.STRING.optionalFieldOf("skinValue", "").forGetter(Entry::skinValue),
                Codec.STRING.optionalFieldOf("skinSig", "").forGetter(Entry::skinSig)
        ).apply(i, Entry::new));
    }

    private static final Codec<CompanionRegistry> CODEC =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Entry.CODEC)
                    .xmap(CompanionRegistry::new, d -> d.entries)
                    .fieldOf("companions").codec();

    // 1.21.4 predates the codec-based SavedDataType; register with the old SavedData.Factory
    // (Supplier + deserializer + DataFixType) and drive (de)serialization through CODEC ourselves.
    private static final SavedData.Factory<CompanionRegistry> FACTORY = new SavedData.Factory<>(
            CompanionRegistry::new, CompanionRegistry::load,
            net.minecraft.util.datafix.DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CODEC.encodeStart(NbtOps.INSTANCE, this).result()
                .ifPresent(t -> { if (t instanceof CompoundTag c) tag.merge(c); });
        return tag;
    }

    private static CompanionRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        return CODEC.parse(NbtOps.INSTANCE, tag).result().orElseGet(CompanionRegistry::new);
    }

    private final Map<UUID, Entry> entries;

    private CompanionRegistry() {
        this.entries = new HashMap<>();
    }

    private CompanionRegistry(Map<UUID, Entry> entries) {
        this.entries = new HashMap<>(entries);
    }

    public static CompanionRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "numen_companions");
    }

    /** Add or update a companion's catalog entry. */
    public void put(UUID companionUuid, Entry entry) {
        entries.put(companionUuid, entry);
        setDirty();
    }

    public Entry find(UUID companionUuid) {
        return entries.get(companionUuid);
    }

    public void remove(UUID companionUuid) {
        if (entries.remove(companionUuid) != null) setDirty();
    }

    /** Every companion owned by {@code ownerUuid} (UUID + entry). */
    public List<Map.Entry<UUID, Entry>> ownedBy(UUID ownerUuid) {
        List<Map.Entry<UUID, Entry>> out = new ArrayList<>();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            if (e.getValue().owner().equals(ownerUuid)) out.add(e);
        }
        return out;
    }

    /** Every companion currently dead and awaiting respawn (persisted, survives a logout). */
    public List<Map.Entry<UUID, Entry>> pendingDead() {
        List<Map.Entry<UUID, Entry>> out = new ArrayList<>();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            if (e.getValue().diedAt() > 0L) out.add(e);
        }
        return out;
    }

    /** Mark a companion dead (records the cause + game-time, persisted for the respawn timer). */
    public void markDead(UUID uuid, String cause, long diedAt) {
        Entry e = entries.get(uuid);
        if (e == null) return;
        entries.put(uuid, e.dead(cause, diedAt));
        setDirty();
    }

    /** Clear the death state (called when the body is respawned). */
    public void markAlive(UUID uuid) {
        Entry e = entries.get(uuid);
        if (e == null || e.diedAt() == 0L) return;
        entries.put(uuid, e.alive());
        setDirty();
    }
}
