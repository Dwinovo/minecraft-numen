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
 * Budget-sliced long-range block scan — the async backend of the
 * {@code scan_blocks} tool for radii beyond what a single synchronous tick
 * can afford. Walks chunk COLUMNS on an expanding {@link RingSpiral} from the
 * scan center (nearest-first, so an early result cap still favours close
 * hits), scanning one palette-filtered 16³ section per
 * {@link SearchBudget#trySectionScan} permit. Which layer of a column comes first
 * and when the walk may stop are {@link SearchGeometry}'s call, shared with every
 * other place in the mod that looks for a block.
 *
 * <h2>Loaded terrain is the boundary</h2>
 * A column that isn't loaded is skipped and counted, never loaded: reading it
 * would park the server thread on chunk I/O or worldgen, and a perception query
 * has no business making the world bigger to answer itself. The skipped count
 * rides back in {@link ScanResult} so the reply can say what the answer covers
 * — out there blocks are UNKNOWN, not absent. Nothing here can stall a tick:
 * every unit of work is metered, and a deadline converts a too-expensive scan
 * into an honest partial result.
 *
 * <p>This is a QUERY in spirit — it never occupies the body or the task
 * queue; the pet keeps walking/mining while the scan runs. The reply rides
 * the normal async tool-result channel. Server main thread only; ticked from
 * both loaders' end-of-tick hooks.
 */
public final class ScanBlocksJob {

    /** Hard stop: convert a crawling scan into a partial answer (30s). */
    private static final int DEADLINE_TICKS = 600;
    /** Same collect cap as the synchronous scanner — bounds memory and sort. */
    private static final int MAX_COLLECT = 8_192;

    private static final List<ScanBlocksJob> JOBS = new ArrayList<>();

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

    private ScanBlocksJob(UUID entityUuid, ServerLevel level, BlockPos center, int radius, int want,
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

    /** Register a scan; the result arrives via the callback on a later tick. */
    public static void start(UUID entityUuid, ServerLevel level, BlockPos center, int radius, int want,
                             Set<Block> targets, Consumer<ScanResult> onDone) {
        ScanBlocksJob job = new ScanBlocksJob(entityUuid, level, center, radius, want, targets, onDone);
        JOBS.add(job);
        Constants.LOG.info("[numen-scan] started radius-{} scan around {} in {} ({} columns)",
                radius, center.toShortString(), level.dimension().location(), job.columnsTotal);
    }

    /** Drop pending scans for one entity (owner interrupt — client already
     *  synthesized cancelled results, a late reply would be an orphan). */
    public static void cancelFor(UUID entityUuid) {
        JOBS.removeIf(job -> job.entityUuid.equals(entityUuid));
    }

    /** Advance all pending scans under the shared budget. */
    public static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) return;
        SearchBudget.refresh(server);
        Iterator<ScanBlocksJob> it = JOBS.iterator();
        while (it.hasNext()) {
            ScanBlocksJob job = it.next();
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
