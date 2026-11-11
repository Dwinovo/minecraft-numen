package com.dwinovo.numen.api.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import com.dwinovo.numen.api.entity.Attendance.Outside;
import com.dwinovo.numen.api.entity.Attendance.Why;

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

    /** One companion's catalog entry.
     *  {@code skinValue}/{@code skinSig} = 借来的正版皮肤(Mojang 签名的 textures 属性),
     *  空串 = 无皮肤,客户端回落原版默认皮肤(按 UUID 哈希抽取)。
     *  {@code outside} = 她被留在外面(主人下令下线的,或被外面的人或插件请出了服务器的,后者带当时说的理由),在主人再召唤她之前不重建。
     *  {@code leftAt} = 她被 Numen 登出世界那一刻的游戏时间(overworld 的 gameTime),回来时据此告诉她离开了多久;-1 = 没有这回事。
     *  死亡不进名册:她死了就由原版重生流程当场重生,没有"死着等复活"的状态;老存档里的 {@code diedAt}、{@code deathCause}
     *  读档时被忽略,下一次存盘就不再写。 */
    public record Entry(String name, UUID owner, ResourceKey<Level> dimension, BlockPos pos,
                        String skinValue, String skinSig,
                        String taskName, String taskLua, String taskOld, Outside outside, long leftAt) {
        /** A companion with no borrowed skin, idle. */
        public Entry(String name, UUID owner, ResourceKey<Level> dimension, BlockPos pos) {
            this(name, owner, dimension, pos, "", "", "", "", "", Outside.NONE, -1L);
        }

        /**
         * 她现在在做什么:这件活给模型看的名字(派它的函数的全名)与重启后再跑的那一行 Lua({@code taskLua});空串 = 闲着,只有名字 =
         * 在做、但接不回来。见 {@code TaskPersistence}。{@code taskOld} 只是读进来的:旧版本按一行命令记下的活,这一版不再跑它。
         */
        public Entry doing(String task, String lua) {
            return new Entry(name, owner, dimension, pos, skinValue, skinSig,
                    task == null ? "" : task, lua == null ? "" : lua, "", outside, leftAt);
        }

        /** 刷新落点(休眠/移动时的 respawn 提示),皮肤原样保留。 */
        public Entry movedTo(ResourceKey<Level> dimension, BlockPos pos) {
            return new Entry(name, owner, dimension, pos, skinValue, skinSig,
                    taskName, taskLua, taskOld, outside, leftAt);
        }

        /** 换上 Mojang 签名的皮肤数据(value+signature)。 */
        public Entry withSkin(String value, String sig) {
            return new Entry(name, owner, dimension, pos,
                    value == null ? "" : value, sig == null ? "" : sig, taskName, taskLua, taskOld, outside, leftAt);
        }

        /** 换一个"留在外面"的状态({@link Outside#NONE} = 不在外面)。 */
        Entry outside(Outside next) {
            return new Entry(name, owner, dimension, pos, skinValue, skinSig,
                    taskName, taskLua, taskOld, next, leftAt);
        }

        /** 记下她被登出世界的游戏时间;-1 = 清掉。 */
        Entry leftAt(long at) {
            return new Entry(name, owner, dimension, pos, skinValue, skinSig,
                    taskName, taskLua, taskOld, outside, at);
        }

        /** 被服务器请出去时说的理由;不是服务器请出去的是空串。面板的名册只显示这一种。 */
        public String removedFor() {
            return outside.server() ? outside.reason() : "";
        }

        private static final Codec<Outside> OUTSIDE = RecordCodecBuilder.create(i -> i.group(
                Codec.stringResolver(w -> w.name().toLowerCase(java.util.Locale.ROOT), Entry::whyOf)
                        .fieldOf("why").forGetter(Outside::why),
                Codec.STRING.optionalFieldOf("reason", "").forGetter(Outside::reason)
        ).apply(i, Outside::new));

        private static Why whyOf(String name) {
            for (Why why : Why.values()) {
                if (why.name().equalsIgnoreCase(name)) return why;
            }
            return null;
        }

        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(Entry::name),
                UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(Entry::owner),
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Entry::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
                Codec.STRING.optionalFieldOf("skinValue", "").forGetter(Entry::skinValue),
                Codec.STRING.optionalFieldOf("skinSig", "").forGetter(Entry::skinSig),
                Codec.STRING.optionalFieldOf("taskName", "").forGetter(Entry::taskName),
                Codec.STRING.optionalFieldOf("taskLua", "").forGetter(Entry::taskLua),
                // 旧版本记的那一行命令:读进来只为如实说它接不回来,写回去它已经清掉了
                Codec.STRING.optionalFieldOf("taskArgs", "").forGetter(Entry::taskOld),
                // 旧版本记的"被服务器请出去"的理由:读进来当作服务器请出去的状态,写回去它已经并进了 outside
                Codec.STRING.optionalFieldOf("removedFor", "").forGetter(e -> ""),
                OUTSIDE.optionalFieldOf("outside", Outside.NONE).forGetter(Entry::outside),
                Codec.LONG.optionalFieldOf("leftAt", -1L).forGetter(Entry::leftAt)
        ).apply(i, (name, owner, dimension, pos, skinValue, skinSig, taskName, taskLua, taskOld, legacyRemovedFor,
                    outside, leftAt) -> new Entry(name, owner, dimension, pos, skinValue, skinSig, taskName, taskLua,
                taskOld, legacyRemovedFor.isEmpty() ? outside : Outside.byServer(legacyRemovedFor), leftAt)));
    }

    private static final Codec<CompanionRegistry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Entry.CODEC)
                    .fieldOf("companions").forGetter(d -> d.entries),
            Codec.STRING.optionalFieldOf("worldId", "").forGetter(d -> d.worldId)
    ).apply(i, CompanionRegistry::new));

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

    // 包内可见:持久化是这个类最要命的部分(解析失败 = 全世界同伴静默消失),
    // 得让单测够得着。
    static CompanionRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        return CODEC.parse(NbtOps.INSTANCE, tag).result().orElseGet(CompanionRegistry::new);
    }

    private final Map<UUID, Entry> entries;
    private String worldId;

    CompanionRegistry() {
        this.entries = new HashMap<>();
        this.worldId = "";
    }

    private CompanionRegistry(Map<UUID, Entry> entries, String worldId) {
        this.entries = new HashMap<>(entries);
        this.worldId = worldId == null ? "" : worldId;
    }

    public static CompanionRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "numen_companions");
    }

    /**
     * 这个世界的身份证——首次访问时随机生成并持久化。
     *
     * <p>客户端的同伴数据({@code config/numen/companions/}) 是一个跨存档共用的目录,
     * 所以"这只同伴不在名册上"必须先问清楚"名册是哪个世界的"。没有这个 id,换一个
     * 存档进去就会把上一个存档的同伴全判成已遣散——那是会毁数据的。
     *
     * <p>随机 UUID 而不是存档名:存档名会重名、会改名,服务器地址会变。
     */
    public String worldId() {
        if (worldId == null || worldId.isBlank()) {
            worldId = UUID.randomUUID().toString();
            setDirty();
        }
        return worldId;
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

    /** Every companion that exists, whoever owns it. */
    public List<UUID> all() {
        return new ArrayList<>(entries.keySet());
    }

    /** Every companion owned by {@code ownerUuid} (UUID + entry). */
    public List<Map.Entry<UUID, Entry>> ownedBy(UUID ownerUuid) {
        List<Map.Entry<UUID, Entry>> out = new ArrayList<>();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            if (e.getValue().owner().equals(ownerUuid)) out.add(e);
        }
        return out;
    }

    /** 记下她被留在外面(服务器请出去的理由随名册落盘,主人下次上线还说得出)。 */
    public void markOutside(UUID uuid, Outside outside) {
        Entry e = entries.get(uuid);
        if (e == null) return;
        entries.put(uuid, e.outside(outside));
        setDirty();
    }

    /** 主人再召唤了她:不再是留在外面的状态。 */
    public void markReturned(UUID uuid) {
        Entry e = entries.get(uuid);
        if (e == null || !e.outside().isOutside()) return;
        entries.put(uuid, e.outside(Outside.NONE));
        setDirty();
    }

    /** 记下她被登出世界的游戏时间;{@code -1} 是她回到了世界。 */
    public void markLeftAt(UUID uuid, long gameTime) {
        Entry e = entries.get(uuid);
        if (e == null) return;
        entries.put(uuid, e.leftAt(gameTime));
        setDirty();
    }
}
