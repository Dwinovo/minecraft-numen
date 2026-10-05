package com.dwinovo.numen.core.scan;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link BlockSearch} 读地形的方式:按 section 记下"这一节里每种目标方块在哪",同一片地被反复问时
 * 不必重读。只有 {@link BlockSearch} 用它——找方块的出口只有那一个,这里是它的存储。
 *
 * <h2>形态(每维度一份,全部同伴共享)</h2>
 * {@code SectionPos → { Block → 段内位置集 }} 的倒排索引,只有读过的 section 才有条目。三条供给让它
 * 保持新鲜:
 * <ol>
 *   <li><b>方块变更钩子</b>——{@code ServerLevel.onBlockStateChange}(即原版 POI 系统自己的写入口)每次
 *       服务端方块变化调用 {@link #onBlockChange};无关方块两次哈希查询即返回,没有任何登记时第一行即返回。
 *       挖掉的目标实时出索引,长出的树苗实时进索引。</li>
 *   <li><b>懒构建</b>——搜索碰到没有新鲜条目的 section 时就地构建:palette 预筛(不含任何登记方块的
 *       section 几乎零成本,直接记成空条目)+ 一趟计数 + 一趟收位。真构建由搜索按 {@link SearchBudget}
 *       的读节配额计费。</li>
 *   <li><b>驱逐</b>——{@link #sweep} 周期清除已卸载区块的条目;最后一个登记注销时整个维度索引直接丢弃。</li>
 * </ol>
 *
 * <h2>谁登记、条目对谁新鲜</h2>
 * 登记是计数式的:每次搜索在跑的那几刻登记自己的目标,要反复找的任务(mine)在整个任务期间持有登记。
 * 一个条目只对"构建时已经登记、此后一直没断过"的方块可信——每种方块记下它这一轮登记开始的时间戳,
 * 条目记下构建时的时间戳,前者不晚于后者才算新鲜。于是新登记一种方块只让这一种方块的旧条目作废,
 * 别的任务攒热的缓存不受牵连。
 *
 * <h2>丰度分级</h2>
 * 一个 section 内某目标超过 {@link #SATURATION} 个(石头/泥土这类铺天盖地的),不枚举位置,只存"饱和"
 * 标记——取用时对【该一个 section】现场取位。稀疏目标(矿石)与成簇目标(原木)全量索引。
 *
 * <h2>线程契约</h2>
 * 全部状态仅服务端主线程读写。worldgen 线程途经 onBlockStateChange 的写入被直接丢弃——新生成区块首次
 * 被搜索时懒构建自然收录(与原版 POI 的自愈口径一致)。
 */
final class TargetIndex {

    private TargetIndex() {}

    /** 段内某目标超过该数即记"饱和",不枚举位置(4096 格的 1/16)。 */
    private static final int SATURATION = 256;
    /** 饱和标记(位置永远非负,-1 不会与真实位置冲突)。 */
    private static final short[] SATURATED = {-1};

    /** 有任何维度有登记时为 true——方块变更钩子的最外层免费闸门。 */
    private static volatile boolean anyActive;
    private static final Map<ResourceKey<Level>, LevelIndex> INDEXES = new HashMap<>();

    /** 一个维度的索引。 */
    private static final class LevelIndex {
        /** 目标方块 → 登记计数(多个搜索与任务可共享同一目标)。 */
        final Reference2IntOpenHashMap<Block> refs = new Reference2IntOpenHashMap<>();
        /** 目标方块 → 它这一轮登记开始时的时间戳。 */
        final Reference2LongOpenHashMap<Block> since = new Reference2LongOpenHashMap<>();
        /** SectionPos.asLong → 条目。 */
        final Long2ObjectOpenHashMap<SectionEntry> sections = new Long2ObjectOpenHashMap<>();
        /** 单调递增:每有一种方块开始一轮新登记就加一,条目按它记构建时刻。 */
        long stamp;

        /** 这个条目对这些方块都可信吗。 */
        boolean fresh(SectionEntry e, Collection<Block> targets) {
            for (Block b : targets) {
                if (!refs.containsKey(b) || since.getLong(b) > e.builtAt) {
                    return false;
                }
            }
            return true;
        }
    }

    /** 一个 section 的条目:该段内每种登记方块的打包位置(y<<8|z<<4|x),或饱和标记。 */
    static final class SectionEntry {
        final long builtAt;
        final Reference2ObjectOpenHashMap<Block, short[]> hits = new Reference2ObjectOpenHashMap<>();

        SectionEntry(long builtAt) {
            this.builtAt = builtAt;
        }

        void add(Block b, short packed) {
            short[] arr = hits.get(b);
            if (arr == SATURATED) {
                return;
            }
            if (arr == null) {
                hits.put(b, new short[]{packed});
                return;
            }
            if (arr.length + 1 > SATURATION) {
                hits.put(b, SATURATED);
                return;
            }
            short[] grown = new short[arr.length + 1];
            System.arraycopy(arr, 0, grown, 0, arr.length);
            grown[arr.length] = packed;
            hits.put(b, grown);
        }

        void remove(Block b, short packed) {
            short[] arr = hits.get(b);
            if (arr == null || arr == SATURATED) {
                return;   // 饱和段不枚举位置,取用时现场取位,轻微高估无害
            }
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] == packed) {
                    if (arr.length == 1) {
                        hits.remove(b);
                    } else {
                        short[] shrunk = new short[arr.length - 1];
                        System.arraycopy(arr, 0, shrunk, 0, i);
                        System.arraycopy(arr, i + 1, shrunk, i, arr.length - 1 - i);
                        hits.put(b, shrunk);
                    }
                    return;
                }
            }
        }

        /** 这些目标里有没有在本段饱和的——取用它要现场读整节。 */
        boolean saturatedAny(Collection<Block> targets) {
            for (Block b : targets) {
                if (hits.get(b) == SATURATED) {
                    return true;
                }
            }
            return false;
        }
    }

    // ==================== 登记 ====================

    static void register(ResourceKey<Level> dimension, Collection<Block> blocks) {
        LevelIndex idx = INDEXES.computeIfAbsent(dimension, k -> new LevelIndex());
        for (Block b : blocks) {
            if (idx.refs.addTo(b, 1) == 0) {
                idx.since.put(b, ++idx.stamp);
            }
        }
        anyActive = true;
    }

    /** 该维度最后一个登记注销后整个索引释放。没登记过的方块不计数。 */
    static void unregister(ResourceKey<Level> dimension, Collection<Block> blocks) {
        LevelIndex idx = INDEXES.get(dimension);
        if (idx == null) {
            return;
        }
        for (Block b : blocks) {
            int n = idx.refs.getInt(b);
            if (n <= 1) {
                idx.refs.removeInt(b);
                idx.since.removeLong(b);
            } else {
                idx.refs.put(b, n - 1);
            }
        }
        if (idx.refs.isEmpty()) {
            INDEXES.remove(dimension);
        }
        anyActive = !INDEXES.isEmpty();
    }

    // ==================== 供给:方块变更钩子 ====================

    static void onBlockChange(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!anyActive) {
            return;
        }
        if (!level.getServer().isSameThread()) {
            return;   // worldgen 线程的写入:该区块尚未被读过,首次被搜索时懒构建自然收录
        }
        LevelIndex idx = INDEXES.get(level.dimension());
        if (idx == null) {
            return;
        }
        Block ob = oldState.getBlock();
        Block nb = newState.getBlock();
        boolean oldT = idx.refs.containsKey(ob);
        boolean newT = idx.refs.containsKey(nb);
        if ((!oldT && !newT) || ob == nb) {
            return;
        }
        long key = SectionPos.asLong(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()), SectionPos.blockToSectionCoord(pos.getZ()));
        SectionEntry e = idx.sections.get(key);
        if (e == null) {
            return;   // 没读过:下次搜索构建时读的就是新状态
        }
        // 对这一种方块不新鲜的条目也照记:它在被用来回答这种方块之前一定会先重建
        short packed = pack(pos);
        if (oldT) {
            e.remove(ob, packed);
        }
        if (newT) {
            e.add(nb, packed);
        }
    }

    // ==================== 读 ====================

    /**
     * 不花读节配额就能拿到的条目:已有且对 {@code targets} 新鲜的,或者 palette 预筛就能断定不含任何登记
     * 方块的(当场记成空条目——常驻加载的大片空段按真构建计价的话,配额会在空气上烧光)。
     * 需要真读一遍才答得了时返回 null。
     */
    static SectionEntry cached(ResourceKey<Level> dimension, LevelChunkSection section, long key,
                               Collection<Block> targets) {
        LevelIndex idx = INDEXES.get(dimension);
        if (idx == null) {
            return null;
        }
        SectionEntry e = idx.sections.get(key);
        if (e != null && idx.fresh(e, targets)) {
            return e;
        }
        if (triviallyEmpty(section, idx)) {
            e = new SectionEntry(idx.stamp);
            idx.sections.put(key, e);
            return e;
        }
        return null;
    }

    /** 真读一遍建条目:一趟计数定饱和 → 一趟收位。收的是此刻登记着的全部方块。 */
    static SectionEntry build(ResourceKey<Level> dimension, LevelChunkSection section, long key) {
        LevelIndex idx = INDEXES.get(dimension);
        SectionEntry e = new SectionEntry(idx.stamp);
        idx.sections.put(key, e);
        Set<Block> targets = idx.refs.keySet();
        Reference2IntOpenHashMap<Block> counts = new Reference2IntOpenHashMap<>();
        section.getStates().count((state, n) -> {
            Block b = state.getBlock();
            if (targets.contains(b)) {
                counts.addTo(b, n);
            }
        });
        if (counts.isEmpty()) {
            return e;   // maybeHas 的假阳性(GlobalPalette 恒真)
        }
        Reference2ObjectOpenHashMap<Block, ShortArrayList> collecting = new Reference2ObjectOpenHashMap<>();
        for (var it = counts.reference2IntEntrySet().fastIterator(); it.hasNext(); ) {
            var en = it.next();
            if (en.getIntValue() > SATURATION) {
                e.hits.put(en.getKey(), SATURATED);
            } else {
                collecting.put(en.getKey(), new ShortArrayList(en.getIntValue()));
            }
        }
        if (!collecting.isEmpty()) {
            var states = section.getStates();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        ShortArrayList list = collecting.get(states.get(x, y, z).getBlock());
                        if (list != null) {
                            list.add((short) (y << 8 | z << 4 | x));
                        }
                    }
                }
            }
            for (var it = collecting.reference2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
                var en = it.next();
                e.hits.put(en.getKey(), en.getValue().toShortArray());
            }
        }
        return e;
    }

    /**
     * 把条目里 {@code targets} 落在球内的格子追加进 {@code out}:状态现读,方块不再是目标的不算;
     * 饱和的目标对这一个 section 现场取位。
     */
    static void collect(SectionEntry e, LevelChunkSection section, int cx, int sy, int cz,
                        Set<Block> targets, BlockPos center, int radius, double radiusSq,
                        List<BlockScanner.Hit> out) {
        if (e.hits.isEmpty()) {
            return;
        }
        int baseX = SectionPos.sectionToBlockCoord(cx);
        int baseY = SectionPos.sectionToBlockCoord(sy);
        int baseZ = SectionPos.sectionToBlockCoord(cz);
        var states = section.getStates();
        for (Block b : targets) {
            short[] arr = e.hits.get(b);
            if (arr == null) {
                continue;
            }
            if (arr == SATURATED) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            offer(states.get(x, y, z), b, baseX + x, baseY + y, baseZ + z,
                                    center, radius, radiusSq, out);
                        }
                    }
                }
                continue;
            }
            for (short p : arr) {
                int x = p & 15;
                int y = p >> 8 & 15;
                int z = p >> 4 & 15;
                offer(states.get(x, y, z), b, baseX + x, baseY + y, baseZ + z, center, radius, radiusSq, out);
            }
        }
    }

    private static void offer(BlockState state, Block wanted, int x, int y, int z,
                              BlockPos center, int radius, double radiusSq, List<BlockScanner.Hit> out) {
        if (state.getBlock() != wanted) {
            return;
        }
        int dx = x - center.getX();
        int dy = y - center.getY();
        int dz = z - center.getZ();
        if (dx < -radius || dx > radius || dy < -radius || dy > radius || dz < -radius || dz > radius) {
            return;
        }
        double distSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
        if (distSq > radiusSq) {
            return;
        }
        out.add(new BlockScanner.Hit(new BlockPos(x, y, z), state, Math.sqrt(distSq)));
    }

    /** palette 预筛:这个 section 一定不含任何登记方块(纯空气,或调色板里就没有)。 */
    private static boolean triviallyEmpty(LevelChunkSection section, LevelIndex idx) {
        var targets = idx.refs.keySet();
        return section == null || section.hasOnlyAir()
                || !section.maybeHas(state -> targets.contains(state.getBlock()));
    }

    // ==================== 生命周期 ====================

    /**
     * 驱逐已卸载区块的条目,并丢掉条目里已经没人登记的方块的位置——它们不再有钩子维护,
     * 再登记时一定先重建,留着只占内存。
     */
    static void sweep(MinecraftServer server) {
        for (Map.Entry<ResourceKey<Level>, LevelIndex> le : INDEXES.entrySet()) {
            LevelIndex idx = le.getValue();
            ServerLevel level = server.getLevel(le.getKey());
            if (level == null) {
                idx.sections.clear();
                continue;
            }
            long lastChunkKey = Long.MIN_VALUE;
            boolean lastLoaded = false;
            var it = idx.sections.long2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                var en = it.next();
                long key = en.getLongKey();
                int cx = SectionPos.x(key);
                int cz = SectionPos.z(key);
                long chunkKey = (long) cx << 32 | (cz & 0xFFFFFFFFL);
                if (chunkKey != lastChunkKey) {
                    lastChunkKey = chunkKey;
                    lastLoaded = BlockScanner.loadedChunk(level, cx, cz) != null;
                }
                if (!lastLoaded) {
                    it.remove();
                } else if (!en.getValue().hits.isEmpty()) {
                    en.getValue().hits.keySet().removeIf(b -> !idx.refs.containsKey(b));
                }
            }
        }
    }

    static boolean isEmpty() {
        return INDEXES.isEmpty();
    }

    /** 服务器停止时清空(别钉住旧世界)。 */
    static void dropAll() {
        INDEXES.clear();
        anyActive = false;
    }

    private static short pack(BlockPos pos) {
        return (short) ((pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | (pos.getX() & 15));
    }
}
