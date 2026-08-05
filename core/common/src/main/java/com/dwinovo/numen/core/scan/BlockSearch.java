package com.dwinovo.numen.core.scan;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.scan.BlockScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 找方块,全仓就这一条路:{@code scan_blocks} 问"附近有哪些",{@code goto} 的 FIND 模式问
 * "最近的那个在哪",两者只差 {@code want} 和半径。
 *
 * <p>从中心所在 chunk 起按 {@link RingSpiral} 逐环外扩走 chunk 列,每列内 section 的顺序和
 * 何时可以收工都问 {@link SearchGeometry}:攒够的 {@code want} 个一旦比下一环最近的可能还近,
 * 立刻停——这是精确界,停下来的结果和走满全程逐字一致。一个
 * {@link SearchBudget#trySectionScan} 配额换一节,跨 tick 续。
 *
 * <h2>已加载地形就是边界</h2>
 * 没加载的列跳过并计数,绝不去加载它:读它会把服务端线程按在区块 IO 或地形生成上,而一次感知
 * 查询没有理由为了回答自己而把世界变大。跳过的列数随 {@link ScanResult} 回去,让回执说得清
 * 覆盖到哪——那边的方块是"不知道",不是"没有"。
 *
 * <h2>为什么在主线程</h2>
 * {@code LevelChunkSection} 的调色板是可变的,主线程随时可能就地扩容,后台读到一半会炸。
 * 所以读地形只能排队,靠配额与 4ms 墙钟顶住 tick。代价是找一个八成不存在的方块要花几秒;
 * 换来的是不会漏节、不会读到半个调色板。
 *
 * <p>它<b>不占身体、不进任务队列</b>:她该走走该挖挖,搜索在后头自己推进。服务端主线程,
 * 由两个 loader 的 tick 末钩子驱动。
 */
public final class BlockSearch {

    /** Hard stop: convert a crawling scan into a partial answer (30s). */
    private static final int DEADLINE_TICKS = 600;
    /** Same collect cap as the synchronous scanner — bounds memory and sort. */
    private static final int MAX_COLLECT = 8_192;

    private static final List<BlockSearch> JOBS = new ArrayList<>();
    private static int nextId = 1;

    private final int id = nextId++;
    private final UUID entityUuid;
    private final ResourceKey<Level> dimension;
    private final BlockPos center;
    private final int radius;
    private final double radiusSq;
    private final Predicate<BlockState> filter;
    private final Consumer<ScanResult> onDone;

    private final int centerChunkX, centerChunkZ, maxRing;
    /** Section Y values in visit order — nearest layer first ({@link SearchGeometry#sectionOrder}). */
    private final int[] sectionOrder;
    private int ring, perimIdx;
    private long deadline = -1;
    private int columnsScanned, columnsUnloaded;
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
     * loaded, and whether the deadline cut the walk short. The caller words the
     * reply from these — a hit list alone can't tell the model whether "nothing
     * found" means "nothing there".
     */
    public record ScanResult(List<BlockScanner.Hit> matches, int columnsScanned,
                             int columnsUnloaded, int columnsTotal,
                             boolean deadlineHit, boolean stoppedEarly) {

        /** Did the walk actually cover the whole requested sphere? */
        public boolean coveredEverything() {
            return !deadlineHit && !stoppedEarly && columnsUnloaded == 0;
        }
    }

    private BlockSearch(UUID entityUuid, ServerLevel level, BlockPos center, int radius, int want,
                          Set<Block> targets, Consumer<ScanResult> onDone) {
        this.entityUuid = entityUuid;
        this.dimension = level.dimension();
        this.center = center;
        this.radius = radius;
        this.radiusSq = (double) radius * radius;
        this.filter = state -> targets.contains(state.getBlock());
        this.onDone = onDone;
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
        BlockSearch job = new BlockSearch(entityUuid, level, center, radius, want, targets, onDone);
        JOBS.add(job);
        Constants.LOG.info("[numen-scan] started radius-{} scan around {} in {} ({} columns)",
                radius, center.toShortString(), level.dimension().location(), job.columnsTotal);
        return job.id;
    }

    /** Abandon one search: no callback will fire. Unknown / already-finished ids are a no-op. */
    public static void cancel(int id) {
        JOBS.removeIf(job -> job.id == id);
    }

    /** Advance all pending scans under the shared budget. */
    public static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) return;
        SearchBudget.refresh(server);
        Iterator<BlockSearch> it = JOBS.iterator();
        while (it.hasNext()) {
            BlockSearch job = it.next();
            if (job.tickOne(server)) it.remove();
        }
    }

    /** @return true when finished (reply sent). */
    private boolean tickOne(MinecraftServer server) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            finish(false);
            return true;
        }
        if (deadline < 0) deadline = server.getTickCount() + DEADLINE_TICKS;
        if (server.getTickCount() >= deadline) {
            finish(true);
            return true;
        }
        while (true) {
            if (currentChunk == null && !nextColumn(level)) {
                finish(false);   // spiral exhausted
                return true;
            }
            // Scan the in-progress column one budgeted section at a time, nearest layer first.
            while (sectionCursor < sectionOrder.length) {
                if (!SearchBudget.trySectionScan()) return false;
                BlockScanner.scanChunkSection(level, currentChunk,
                        currentChunkX, sectionOrder[sectionCursor], currentChunkZ,
                        center, radius, radiusSq, filter, matches);
                sectionCursor++;
                feedBound();
                if (matches.size() >= MAX_COLLECT) {
                    // Ring order means what we have is the nearest area anyway.
                    finish(false);
                    return true;
                }
            }
            currentChunk = null;
            columnsScanned++;
        }
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

    private void finish(boolean deadlineHit) {
        matches.sort(Comparator.comparingDouble(BlockScanner.Hit::distance));
        onDone.accept(new ScanResult(matches, columnsScanned, columnsUnloaded, columnsTotal,
                deadlineHit, stoppedEarly));
    }
}
