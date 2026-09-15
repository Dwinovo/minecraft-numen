package com.dwinovo.numen.core.scan;

import com.dwinovo.numen.core.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 找方块,全仓就这一个出口:{@code scan_blocks} 问"附近有哪些",{@code goto} 的 FIND 模式问"最近的那个在哪",
 * {@code mine} 反复问"最近的一批在哪",三者只差 {@code want} 和半径。
 *
 * <p>从中心所在 chunk 起按 {@link RingSpiral} 逐环外扩走 chunk 列,每列内 section 的顺序和何时可以收工都问
 * {@link SearchGeometry}:攒够的 {@code want} 个一旦比下一环最近的可能还近,立刻停——这是精确界,停下来的
 * 结果和走满全程逐字一致。跨 tick 续。
 *
 * <h2>读地形经共享索引</h2>
 * 每一节的内容由 {@link TargetIndex} 答:它按 section 记着每种登记方块的位置,由方块变更钩子保持新鲜。
 * 搜索在跑的那几刻登记自己的目标;要反复找同几种方块的任务用 {@link #hold} 持有登记,期间的每次搜索读的
 * 都是热缓存,不必重读地形。
 *
 * <h2>每刻的预算</h2>
 * 真读一节(构建条目,或现场读一节铺天盖地的目标)花一个 {@link SearchBudget#trySectionRead} 配额;
 * 索引已有新鲜条目、palette 就能排除、整节在球外的,不花配额,只受同一份 4ms 墙钟约束
 * ({@link SearchBudget#withinTime})。调用方要逐格处理命中(scan_blocks 逐格问权限层再分团)的,把处理
 * 交给搜索({@code eachHit}):走完之后由近及远逐格调用,同样受墙钟约束、跨 tick 续,处理完才回执。
 *
 * <h2>已加载地形就是边界</h2>
 * 没加载的列跳过并计数,绝不去加载它:读它会把服务端线程按在区块 IO 或地形生成上,而一次查询没有理由
 * 为了回答自己而把世界变大。跳过的列数随 {@link ScanResult} 回去,让回执说得清覆盖到哪——那边的方块是
 * "不知道",不是"没有"。
 *
 * <h2>为什么在主线程</h2>
 * {@code LevelChunkSection} 的调色板是可变的,主线程随时可能就地扩容,后台读到一半会炸。所以读地形只能
 * 排队,靠配额与墙钟顶住 tick。
 *
 * <p>它<b>不占身体、不进任务队列</b>:她该走走该挖挖,搜索在后头自己推进。服务端主线程,由两个 loader 的
 * tick 末钩子驱动。
 */
public final class BlockSearch {

    static {
        // 搜索与索引描述的都是一个具体的世界,世界没了就得跟着没;清理跟状态同居。见 ServerLifecycle。
        com.dwinovo.numen.platform.ServerLifecycle.onStopped(BlockSearch::dropAll);
    }

    /** Hard stop: convert a crawling scan into a partial answer (30s). */
    private static final int DEADLINE_TICKS = 600;
    /**
     * Collect cap — bounds memory and sort; ring order means what is kept is the nearest area.
     * Passing it as {@code want} asks for every hit in the radius: the cap ends the walk before
     * the stop rule could.
     */
    public static final int MAX_COLLECT = 8_192;
    /** 索引驱逐清扫周期(tick)。 */
    private static final int EVICT_SWEEP_TICKS = 200;

    private static final List<BlockSearch> JOBS = new ArrayList<>();
    private static int nextId = 1;
    private static int sweepTimer;

    private final int id = nextId++;
    /** What was asked for, short enough for one log line: {@code iron_ore} / {@code iron_ore+1}. */
    private final String label;
    private long startTick = -1;
    private final UUID entityUuid;
    private final ResourceKey<Level> dimension;
    private final BlockPos center;
    private final int radius;
    private final double radiusSq;
    private final Set<Block> targets;
    /** Per-hit stage run after the walk, before the reply; null = none. */
    private final Consumer<BlockScanner.Hit> eachHit;
    /** Watermark into the result's hits already handed to {@link #eachHit}. */
    private int staged;
    private final Consumer<ScanResult> onDone;

    private final int centerChunkX, centerChunkZ, maxRing;
    /** Section Y values in visit order — nearest layer first ({@link SearchGeometry#sectionOrder}). */
    private final int[] sectionOrder;
    private int ring, perimIdx;
    private long deadline = -1;
    private int columnsScanned, columnsUnloaded, sectionsRead;
    private final int columnsTotal;
    private boolean stoppedEarly;

    /** How far out the nearest {@code want} reach — the stop rule's whole input. */
    private final SearchGeometry.NearestBound bound;
    /** Watermark into {@link #matches} — everything below it is already in {@link #bound}. */
    private int fed;

    // Column in progress (budget ran dry mid-column); null = fetch next.
    private ChunkAccess currentChunk;
    private int currentChunkX, currentChunkZ, sectionCursor;

    private final List<BlockScanner.Hit> matches = new ArrayList<>();

    /**
     * One scan's answer plus its coverage ledger: how many of {@code columnsTotal}
     * chunk columns were actually read, how many were skipped for not being
     * loaded, and whether the deadline or the collect cap cut the walk short. The
     * caller words the reply from these — a hit list alone can't tell the model
     * whether "nothing found" means "nothing there".
     */
    public record ScanResult(List<BlockScanner.Hit> matches, int columnsScanned,
                             int columnsUnloaded, int columnsTotal,
                             boolean deadlineHit, boolean stoppedEarly, boolean collectCapHit) {

        /** Did the walk actually cover the whole requested sphere? */
        public boolean coveredEverything() {
            return !deadlineHit && !stoppedEarly && !collectCapHit && columnsUnloaded == 0;
        }
    }

    private BlockSearch(UUID entityUuid, ServerLevel level, BlockPos center, int radius, int want,
                        Set<Block> targets, Consumer<BlockScanner.Hit> eachHit, Consumer<ScanResult> onDone) {
        this.entityUuid = entityUuid;
        this.dimension = level.dimension();
        this.center = center;
        this.radius = radius;
        this.radiusSq = (double) radius * radius;
        this.targets = Set.copyOf(targets);
        this.eachHit = eachHit;
        this.onDone = onDone;
        this.label = describe(targets);
        this.centerChunkX = SectionPos.blockToSectionCoord(center.getX());
        this.centerChunkZ = SectionPos.blockToSectionCoord(center.getZ());
        this.maxRing = Math.max(
                SectionPos.blockToSectionCoord(center.getX() + radius) - centerChunkX,
                centerChunkX - SectionPos.blockToSectionCoord(center.getX() - radius));
        this.sectionOrder = SearchGeometry.sectionOrder(
                SectionPos.blockToSectionCoord(Math.max(center.getY() - radius, level.getMinBuildHeight())),
                SectionPos.blockToSectionCoord(Math.min(center.getY() + radius, level.getMaxBuildHeight())),
                SectionPos.blockToSectionCoord(center.getY()));
        this.bound = new SearchGeometry.NearestBound(want);
        int side = 2 * maxRing + 1;
        this.columnsTotal = side * side;
    }

    /**
     * Register a search; the result arrives via the callback on a later tick.
     *
     * @param want how many nearest hits the caller actually needs — the stop rule's quota.
     *             Ask for what you will use: a bigger number walks further to prove itself.
     * @return a handle for {@link #cancel(int)}, per SEARCH rather than per companion —
     *         one pet can have a {@code scan_blocks} query and a {@code goto} lookup in
     *         flight at once, and abandoning one must not silence the other.
     */
    public static int start(UUID entityUuid, ServerLevel level, BlockPos center, int radius, int want,
                            Set<Block> targets, Consumer<ScanResult> onDone) {
        return start(entityUuid, level, center, radius, want, targets, null, onDone);
    }

    /**
     * Same, with a per-hit stage: once the walk is done, {@code eachHit} sees every hit nearest
     * first on the server thread, under the same per-tick wall clock, and the callback fires
     * after the last one.
     */
    public static int start(UUID entityUuid, ServerLevel level, BlockPos center, int radius, int want,
                            Set<Block> targets, Consumer<BlockScanner.Hit> eachHit,
                            Consumer<ScanResult> onDone) {
        BlockSearch job = new BlockSearch(entityUuid, level, center, radius, want, targets, eachHit, onDone);
        TargetIndex.register(job.dimension, job.targets);
        JOBS.add(job);
        return job.id;
    }

    /** Abandon one search: no callback will fire. Unknown / already-finished ids are a no-op. */
    public static void cancel(int id) {
        Iterator<BlockSearch> it = JOBS.iterator();
        while (it.hasNext()) {
            BlockSearch job = it.next();
            if (job.id == id) {
                it.remove();
                TargetIndex.unregister(job.dimension, job.targets);
                return;
            }
        }
    }

    /**
     * 要反复找这几种方块:持有期间索引由方块变更钩子保持它们的缓存新鲜,每次 {@link #start} 读热缓存。
     * 与 {@link #release} 成对调用(计数式,可重入)。
     */
    public static void hold(ServerLevel level, Set<Block> blocks) {
        TargetIndex.register(level.dimension(), blocks);
    }

    /** 放下 {@link #hold} 的登记。 */
    public static void release(ServerLevel level, Set<Block> blocks) {
        TargetIndex.unregister(level.dimension(), blocks);
    }

    /** 由 ServerLevel.onBlockStateChange 的 mixin 调用——服务端每次方块变化都会路过这里。 */
    public static void onBlockChange(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        TargetIndex.onBlockChange(level, pos, oldState, newState);
    }

    /** Advance all pending scans under the shared budget; periodically evict index entries of unloaded chunks. */
    public static void tick(MinecraftServer server) {
        if (!TargetIndex.isEmpty() && ++sweepTimer >= EVICT_SWEEP_TICKS) {
            sweepTimer = 0;
            TargetIndex.sweep(server);
        }
        if (JOBS.isEmpty()) return;
        SearchBudget.refresh(server);
        // A callback may start the next search; it joins the list for the next tick.
        for (BlockSearch job : List.copyOf(JOBS)) {
            if (JOBS.contains(job) && job.tickOne(server)) {
                JOBS.remove(job);
                job.onDone.accept(job.result);
                TargetIndex.unregister(job.dimension, job.targets);
            }
        }
    }

    /** The answer, set by {@link #finish}. */
    private ScanResult result;

    /** @return true when finished: the walk is done and every hit went through {@link #eachHit}. */
    private boolean tickOne(MinecraftServer server) {
        if (result == null && !walk(server)) {
            return false;
        }
        if (eachHit == null) {
            return true;
        }
        List<BlockScanner.Hit> hits = result.matches();
        while (staged < hits.size()) {
            if (!SearchBudget.withinTime()) {
                return false;
            }
            eachHit.accept(hits.get(staged++));
        }
        return true;
    }

    /** @return true when the walk is over ({@link #result} is set). */
    private boolean walk(MinecraftServer server) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            finish(server, false);
            return true;
        }
        if (deadline < 0) {
            startTick = server.getTickCount();
            deadline = startTick + DEADLINE_TICKS;
        }
        if (server.getTickCount() >= deadline) {
            finish(server, true);
            return true;
        }
        while (true) {
            if (currentChunk == null && !nextColumn(level)) {
                finish(server, false);   // spiral exhausted
                return true;
            }
            // Read the in-progress column one section at a time, nearest layer first.
            while (sectionCursor < sectionOrder.length) {
                if (!SearchBudget.withinTime() || !readSection(level, sectionOrder[sectionCursor])) {
                    return false;
                }
                sectionCursor++;
                feedBound();
                if (matches.size() >= MAX_COLLECT) {
                    // Ring order means what we have is the nearest area anyway.
                    finish(server, false);
                    return true;
                }
            }
            currentChunk = null;
            columnsScanned++;
        }
    }

    /**
     * 读当前列里的一节,命中追加进 {@link #matches}。要真读地形而本刻配额用完时返回 false,
     * 什么也没做,下一刻从这一节接着来。
     */
    private boolean readSection(ServerLevel level, int sectionY) {
        int index = level.getSectionIndexFromSectionY(sectionY);
        if (index < 0 || index >= currentChunk.getSectionsCount() || !touchesSphere(sectionY)) {
            return true;
        }
        LevelChunkSection section = currentChunk.getSection(index);
        long key = SectionPos.asLong(currentChunkX, sectionY, currentChunkZ);
        TargetIndex.SectionEntry entry = TargetIndex.cached(dimension, section, key, targets);
        if (entry == null || entry.saturatedAny(targets)) {
            if (!SearchBudget.trySectionRead()) {
                return false;
            }
            sectionsRead++;
            if (entry == null) {
                entry = TargetIndex.build(dimension, section, key);
            }
        }
        TargetIndex.collect(entry, section, currentChunkX, sectionY, currentChunkZ, targets,
                center, radius, radiusSq, matches);
        return true;
    }

    /** 这一节的立方体离中心最近的那一点在球内吗——整节在球外的不读。 */
    private boolean touchesSphere(int sectionY) {
        double dx = axisGap(center.getX(), SectionPos.sectionToBlockCoord(currentChunkX));
        double dy = axisGap(center.getY(), SectionPos.sectionToBlockCoord(sectionY));
        double dz = axisGap(center.getZ(), SectionPos.sectionToBlockCoord(currentChunkZ));
        return dx * dx + dy * dy + dz * dz <= radiusSq;
    }

    /** 一个坐标到 [base, base+15] 的距离;在区间内为 0。 */
    private static double axisGap(int c, int base) {
        return c < base ? base - c : Math.max(0, c - (base + 15));
    }

    /**
     * Resolve the next spiral column into {@link #currentChunk}, tallying and
     * skipping columns whose chunk isn't loaded. Returns false only when the
     * spiral is exhausted — walking past unloaded terrain costs one cache lookup
     * per column, so it needs no permit and never defers to the next tick.
     */
    private boolean nextColumn(ServerLevel level) {
        while (ring <= maxRing) {
            if (perimIdx >= RingSpiral.perimeter(ring)) {
                if (SearchGeometry.canStop(ring, bound)) {
                    // The nearest `want` are already closer than anything the next ring could hold.
                    stoppedEarly = true;
                    return false;
                }
                ring++;
                perimIdx = 0;
                continue;
            }
            int[] d = RingSpiral.offset(ring, perimIdx++);
            int cx = centerChunkX + d[0];
            int cz = centerChunkZ + d[1];
            ChunkAccess chunk = BlockScanner.loadedChunk(level, cx, cz);
            if (chunk == null) {
                columnsUnloaded++;
                continue;
            }
            currentChunk = chunk;
            currentChunkX = cx;
            currentChunkZ = cz;
            sectionCursor = 0;
            return true;
        }
        return false;
    }

    /** Hand the hits found since the last call to the distance bound the stop rule reads. */
    private void feedBound() {
        for (int i = fed; i < matches.size(); i++) {
            bound.offer(matches.get(i).distance());
        }
        fed = matches.size();
    }

    private void finish(MinecraftServer server, boolean deadlineHit) {
        matches.sort(Comparator.comparingDouble(BlockScanner.Hit::distance));
        // One line per search, and it has to carry everything a bug report needs: what was
        // asked, what came back, WHY it stopped, and what it cost. "She can't find X" is
        // answered by the stop reason plus the unloaded count, without a debug build.
        Constants.LOG.info("[numen-scan] {} r={} → {} hit(s){} | {} | {}/{} columns read,"
                        + " {} not loaded, {} section read(s) | {} tick(s)",
                label, radius, matches.size(),
                matches.isEmpty() ? "" : String.format(", nearest %.1f", matches.get(0).distance()),
                stopReason(deadlineHit), columnsScanned, columnsTotal, columnsUnloaded, sectionsRead,
                startTick < 0 ? 0 : server.getTickCount() - startTick);
        result = new ScanResult(matches, columnsScanned, columnsUnloaded, columnsTotal,
                deadlineHit, stoppedEarly, matches.size() >= MAX_COLLECT);
    }

    private String stopReason(boolean deadlineHit) {
        if (deadlineHit) return "deadline";
        if (stoppedEarly) return "proved nearest at ring " + ring;
        if (matches.size() >= MAX_COLLECT) return "collect cap";
        return "covered the whole radius";
    }

    /** {@code iron_ore} for one target, {@code iron_ore+1} for a set — one log line, not a list. */
    private static String describe(Set<Block> targets) {
        Iterator<Block> it = targets.iterator();
        if (!it.hasNext()) return "nothing";
        String first = BuiltInRegistries.BLOCK.getKey(it.next()).getPath();
        return targets.size() > 1 ? first + "+" + (targets.size() - 1) : first;
    }

    /** 服务器停止时清空在飞的搜索与索引(别钉住旧世界,也别让旧搜索的登记漏进新世界的计数)。 */
    private static void dropAll() {
        JOBS.clear();
        TargetIndex.dropAll();
        sweepTimer = 0;
    }
}
