package com.dwinovo.numen.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

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
                        String deathCause, long diedAt, String skinValue, String skinSig,
                        String taskTool, String taskArgs, List<String> scaffoldMaterials) {
        /** A live companion (not dead), no borrowed skin, idle, spending the default scaffolding. */
        public Entry(String name, UUID owner, ResourceKey<Level> dimension, BlockPos pos) {
            this(name, owner, dimension, pos, "", 0L, "", "", "", "", DEFAULT_SCAFFOLD);
        }

        /** 她现在在做什么(工具名 + 当时的参数);空串 = 闲着。见 {@code TaskPersistence}。 */
        public Entry doing(String tool, String args) {
            return new Entry(name, owner, dimension, pos, deathCause, diedAt, skinValue, skinSig,
                    tool == null ? "" : tool, args == null ? "" : args, scaffoldMaterials);
        }

        /** 刷新落点(休眠/移动时的 respawn 提示),皮肤与死亡状态原样保留。 */
        public Entry movedTo(ResourceKey<Level> dimension, BlockPos pos) {
            return new Entry(name, owner, dimension, pos, deathCause, diedAt, skinValue, skinSig,
                    taskTool, taskArgs, scaffoldMaterials);
        }

        /** 换上 Mojang 签名的皮肤数据(value+signature)。 */
        public Entry withSkin(String value, String sig) {
            return new Entry(name, owner, dimension, pos, deathCause, diedAt,
                    value == null ? "" : value, sig == null ? "" : sig, taskTool, taskArgs,
                    scaffoldMaterials);
        }

        /**
         * 她愿意拿来垫路的方块(namespaced id)。<b>存的就是清单</b>:空表意味着"一块都不许
         * 垫",那是模型可以做的决定(背包里那些泥土留着盖房子),不是"没设过"——没设过由
         * {@link #DEFAULT_SCAFFOLD} 在读取时兜住。
         */
        public Entry withScaffoldMaterials(List<String> materials) {
            return new Entry(name, owner, dimension, pos, deathCause, diedAt, skinValue, skinSig,
                    taskTool, taskArgs, materials == null ? List.of() : List.copyOf(materials));
        }

        Entry dead(String cause, long at) {
            return new Entry(name, owner, dimension, pos, cause, at, skinValue, skinSig,
                    taskTool, taskArgs, scaffoldMaterials);
        }

        Entry alive() {
            return new Entry(name, owner, dimension, pos, "", 0L, skinValue, skinSig,
                    taskTool, taskArgs, scaffoldMaterials);
        }

        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(Entry::name),
                UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(Entry::owner),
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Entry::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
                Codec.STRING.optionalFieldOf("deathCause", "").forGetter(Entry::deathCause),
                Codec.LONG.optionalFieldOf("diedAt", 0L).forGetter(Entry::diedAt),
                Codec.STRING.optionalFieldOf("skinValue", "").forGetter(Entry::skinValue),
                Codec.STRING.optionalFieldOf("skinSig", "").forGetter(Entry::skinSig),
                Codec.STRING.optionalFieldOf("taskTool", "").forGetter(Entry::taskTool),
                Codec.STRING.optionalFieldOf("taskArgs", "").forGetter(Entry::taskArgs),
                Codec.STRING.listOf().optionalFieldOf("scaffold", DEFAULT_SCAFFOLD)
                        .forGetter(Entry::scaffoldMaterials)
        ).apply(i, Entry::new));
    }

    /**
     * 新同伴、以及这个字段出现之前的老存档,拿到的垫路料清单。<b>存的就是清单</b>——空表
     * 是"一块都不许垫"这个真实意图,不是"没设过"。
     *
     * <p>缺省值是一条<b>标签引用</b>而不是展开后的清单,两个理由:整合包改
     * {@code numen:scaffolds} 就能改掉所有新同伴的起点;而标签内容来自数据包、世界加载后
     * 才存在,静态常量比它早得多——存引用、用时再解析,才躲得开这个时序。和原版配方里
     * 存 {@code "#minecraft:planks"}、匹配时才现查是同一个形状。
     *
     * <p>模型一旦改过清单(add/delete/set),存的就是具体 id,从此不再跟标签走——所以这是
     * <b>初始</b>默认。选料判据写在消费方 {@code ScaffoldMaterials}。
     */
    public static final List<String> DEFAULT_SCAFFOLD = List.of("#numen:scaffolds");

    // 包内可见:持久化是这个类最要命的部分(解析失败 = 全世界同伴静默消失),
    // 得让单测够得着。
    static final Codec<CompanionRegistry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Entry.CODEC)
                    .fieldOf("companions").forGetter(d -> d.entries),
            Codec.STRING.optionalFieldOf("worldId", "").forGetter(d -> d.worldId)
    ).apply(i, CompanionRegistry::new));

    // 1.21.5 codec 化的 SavedDataType:名字 + 构造器 + CODEC + datafix 类型,存储层自己驱动
    // (反)序列化,save()/load() 重写不复存在。解析失败由存储层兜底:readSavedData 记日志
    // 返 null,computeIfAbsent 落回构造器 —— "垃圾降级为空注册表"的语义与旧代一致。
    private static final SavedDataType<CompanionRegistry> TYPE = new SavedDataType<>(
            "numen_companions", CompanionRegistry::new, CODEC,
            net.minecraft.util.datafix.DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

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
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
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
