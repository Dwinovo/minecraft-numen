package com.dwinovo.numen.core.scan;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端目标方块索引:回答"离这里最近的 X 方块在哪"而【不做周期性全量扫描】。
 *
 * <h2>形态(每维度一份,全部同伴共享)</h2>
 * {@code SectionPos → { Block → 段内位置集 }} 的倒排索引,只有含目标的 section 才有条目。
 * 三条供给让它保持新鲜:
 * <ol>
 *   <li><b>方块变更钩子</b>——{@code ServerLevel.onBlockStateChange}(即原版 POI 系统自己的
 *       写入口)每次服务端方块变化调用 {@link #onBlockChange};无关方块两次哈希查询即返回,
 *       没有任何任务注册目标时第一行即返回。挖掉的目标实时出索引,长出的树苗实时进索引。</li>
 *   <li><b>懒构建</b>——查询碰到未建/过期的 section 时就地构建:palette 预筛(不含目标的
 *       section 几乎零成本跳过)+ 一趟计数 + 一趟收位,每次查询有构建预算封顶。</li>
 *   <li><b>驱逐</b>——{@link #serverTick} 周期清除已卸载区块的条目;最后一个任务注销时整个
 *       维度索引直接丢弃。</li>
 * </ol>
 *
 * <h2>丰度分级</h2>
 * 一个 section 内某目标超过 {@link #SATURATION} 个(石头/泥土这类铺天盖地的),不枚举位置,
 * 只存"饱和"标记——查询碰到饱和段时对【该一个 section】现场取位即可。稀疏目标(矿石)与
 * 成簇目标(原木)全量索引。
 *
 * <h2>线程契约</h2>
 * 全部状态仅服务端主线程读写。worldgen 线程途经 onBlockStateChange 的写入被直接丢弃——
 * 新生成区块首次被查询/加载时懒构建自然收录(与原版 POI 的自愈口径一致)。
 */
public final class TargetIndex {

    private TargetIndex() {}

    /** 段内某目标超过该数即记"饱和",不枚举位置(4096 格的 1/16)。 */
    private static final int SATURATION = 256;
    /** 饱和标记(位置永远非负,-1 不会与真实位置冲突)。 */
    private static final short[] SATURATED = {-1};
    /** 驱逐清扫周期(tick)。 */
    private static final int EVICT_SWEEP_TICKS = 200;

    /** 有任何维度有注册目标时为 true——方块变更钩子的最外层免费闸门。 */
    private static volatile boolean anyActive;
    private static final Map<ResourceKey<Level>, LevelIndex> INDEXES = new HashMap<>();
    private static int sweepTimer;

    /** 一个维度的索引。 */
    private static final class LevelIndex {
        /** 目标方块 → 注册计数(多个任务可共享同一目标)。 */
        final Reference2IntOpenHashMap<Block> targetRefs = new Reference2IntOpenHashMap<>();
        /** SectionPos.asLong → 条目。 */
        final Long2ObjectOpenHashMap<SectionEntry> sections = new Long2ObjectOpenHashMap<>();
        /** 目标集合的版本号:成员增减时自增,旧版本条目查询时懒重建。 */
        int version = 1;
    }

    /** 一个 section 的条目:该段内每种目标的打包位置(y<<8|z<<4|x),或饱和标记。 */
    private static final class SectionEntry {
        final int version;
        final Reference2ObjectOpenHashMap<Block, short[]> hits = new Reference2ObjectOpenHashMap<>();

        SectionEntry(int version) {
            this.version = version;
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
                return;   // 饱和段轻微高估无害:消费端取用时还会活世界复验
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
    }

    /** 查询结果:命中(环序,近似由近及远)+ 覆盖是否完整(构建预算未耗尽即真)。 */
    public record Result(List<BlockPos> hits, boolean complete) {}

    // ==================== 注册 ====================

    /** 任务开始时登记其目标方块(计数式,可重入)。 */
    public static void register(ServerLevel level, Collection<Block> blocks) {
        LevelIndex idx = INDEXES.computeIfAbsent(level.dimension(), k -> new LevelIndex());
        boolean changed = false;
        for (Block b : blocks) {
            if (idx.targetRefs.addTo(b, 1) == 0) {
                changed = true;
            }
        }
        if (changed) {
            idx.version++;
        }
        anyActive = true;
    }

    /** 任务结束时注销;该维度最后一个目标注销后整个索引释放。 */
    public static void unregister(ServerLevel level, Collection<Block> blocks) {
        LevelIndex idx = INDEXES.get(level.dimension());
        if (idx == null) {
            return;
        }
        boolean changed = false;
        for (Block b : blocks) {
            if (idx.targetRefs.addTo(b, -1) == 1) {
                idx.targetRefs.removeInt(b);
                changed = true;
            }
        }
        if (changed) {
            idx.version++;
        }
        if (idx.targetRefs.isEmpty()) {
            INDEXES.remove(level.dimension());
        }
        anyActive = !INDEXES.isEmpty();
    }

    // ==================== 供给:方块变更钩子 ====================

    /** 由 ServerLevel.onBlockStateChange 的 mixin 调用——服务端每次方块变化都会路过这里。 */
    public static void onBlockChange(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!anyActive) {
            return;
        }
        if (!level.getServer().isSameThread()) {
            return;   // worldgen 线程的写入:该区块尚未被索引,加载后懒构建自然收录
        }
        LevelIndex idx = INDEXES.get(level.dimension());
        if (idx == null) {
            return;
        }
        Block ob = oldState.getBlock();
        Block nb = newState.getBlock();
        boolean oldT = idx.targetRefs.containsKey(ob);
        boolean newT = idx.targetRefs.containsKey(nb);
        if ((!oldT && !newT) || ob == nb) {
            return;
        }
        long key = SectionPos.asLong(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()), SectionPos.blockToSectionCoord(pos.getZ()));
        SectionEntry e = idx.sections.get(key);
        if (e == null || e.version != idx.version) {
            return;   // 未建/已过期:下次查询重建时读的就是新状态
        }
        short packed = pack(pos);
        if (oldT) {
            e.remove(ob, packed);
        }
        if (newT) {
            e.add(nb, packed);
        }
    }

    // ==================== 查询 ====================

    /**
     * 从 {@code center} 按 chebyshev 区块环由近及远收集 {@code targets} 的位置,直到凑够
     * {@code want} 个(收完当前环)或环耗尽。只读索引;未建条目就地构建,每次调用最多构建
     * {@code buildBudget} 个 section(预算耗尽返回 {@code complete=false},调用方稍后再查,
     * 冷区域在几次查询内渐进变热)。未加载区块跳过——索引只回答已加载世界(与扫描时代的
     * 事实边界一致)。
     */
    public static Result query(ServerLevel level, BlockPos center, Collection<Block> targets,
                               int want, int maxChunkRadius, int buildBudget) {
        LevelIndex idx = INDEXES.get(level.dimension());
        if (idx == null) {
            return new Result(List.of(), true);
        }
        List<BlockPos> out = new ArrayList<>();
        int centerCx = SectionPos.blockToSectionCoord(center.getX());
        int centerCz = SectionPos.blockToSectionCoord(center.getZ());
        int minSection = level.getMinSection();
        int sectionCount = level.getSectionsCount();
        int budget = buildBudget;
        boolean complete = true;

        outer:
        for (int r = 0; r <= maxChunkRadius; r++) {
            for (int cx = centerCx - r; cx <= centerCx + r; cx++) {
                for (int cz = centerCz - r; cz <= centerCz + r; cz++) {
                    if (Math.max(Math.abs(cx - centerCx), Math.abs(cz - centerCz)) != r) {
                        continue;   // 只走环壳
                    }
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) {
                        continue;
                    }
                    LevelChunkSection[] secs = chunk.getSections();
                    for (int si = 0; si < sectionCount && si < secs.length; si++) {
                        int sy = minSection + si;
                        long key = SectionPos.asLong(cx, sy, cz);
                        SectionEntry e = idx.sections.get(key);
                        if (e == null || e.version != idx.version) {
                            if (budget <= 0) {
                                complete = false;
                                break outer;
                            }
                            budget--;
                            e = build(secs[si], idx);
                            idx.sections.put(key, e);
                        }
                        collect(e, secs[si], cx, sy, cz, targets, want, out);
                    }
                }
            }
            if (out.size() >= want) {
                break;   // 本环收完,近似由近及远已够——精确排序由调用方负责
            }
        }
        return new Result(out, complete);
    }

    /** 构建一个 section 的条目:palette 预筛 → 一趟计数定饱和 → 一趟收位。 */
    private static SectionEntry build(LevelChunkSection section, LevelIndex idx) {
        SectionEntry e = new SectionEntry(idx.version);
        var targets = idx.targetRefs.keySet();
        if (section == null || section.hasOnlyAir()
                || !section.maybeHas(state -> targets.contains(state.getBlock()))) {
            return e;
        }
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

    /** 把条目中所请求目标的位置追加进 {@code out};饱和目标对该一个 section 现场取位。 */
    private static void collect(SectionEntry e, LevelChunkSection section,
                                int cx, int sy, int cz, Collection<Block> targets,
                                int want, List<BlockPos> out) {
        if (e.hits.isEmpty()) {
            return;
        }
        int baseX = SectionPos.sectionToBlockCoord(cx);
        int baseY = SectionPos.sectionToBlockCoord(sy);
        int baseZ = SectionPos.sectionToBlockCoord(cz);
        for (Block b : targets) {
            short[] arr = e.hits.get(b);
            if (arr == null) {
                continue;
            }
            if (arr == SATURATED) {
                // 铺天盖地的目标:就地对这一个 section 取位,凑到 want 即止
                var states = section.getStates();
                for (int y = 0; y < 16 && out.size() < want; y++) {
                    for (int z = 0; z < 16 && out.size() < want; z++) {
                        for (int x = 0; x < 16 && out.size() < want; x++) {
                            if (states.get(x, y, z).getBlock() == b) {
                                out.add(new BlockPos(baseX | x, baseY + y, baseZ | z));
                            }
                        }
                    }
                }
                continue;
            }
            for (short p : arr) {
                out.add(new BlockPos(baseX | (p & 15), baseY + (p >> 8 & 15), baseZ | (p >> 4 & 15)));
            }
        }
    }

    // ==================== 生命周期 ====================

    /** 每服务端 tick 调用(各 loader 的 tick 钩子);周期性驱逐已卸载区块的条目。 */
    public static void serverTick(MinecraftServer server) {
        if (INDEXES.isEmpty() || ++sweepTimer < EVICT_SWEEP_TICKS) {
            return;
        }
        sweepTimer = 0;
        for (Map.Entry<ResourceKey<Level>, LevelIndex> le : INDEXES.entrySet()) {
            ServerLevel level = server.getLevel(le.getKey());
            if (level == null) {
                le.getValue().sections.clear();
                continue;
            }
            long lastChunkKey = Long.MIN_VALUE;
            boolean lastLoaded = false;
            var it = le.getValue().sections.long2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                long key = it.next().getLongKey();
                int cx = SectionPos.x(key);
                int cz = SectionPos.z(key);
                long chunkKey = (long) cx << 32 | (cz & 0xFFFFFFFFL);
                if (chunkKey != lastChunkKey) {
                    lastChunkKey = chunkKey;
                    lastLoaded = level.getChunkSource().getChunkNow(cx, cz) != null;
                }
                if (!lastLoaded) {
                    it.remove();
                }
            }
        }
    }

    /** 服务器停止时清空(别钉住旧世界)。 */
    public static void dropAll() {
        INDEXES.clear();
        anyActive = false;
        sweepTimer = 0;
    }

    private static short pack(BlockPos pos) {
        return (short) ((pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | (pos.getX() & 15));
    }
}
