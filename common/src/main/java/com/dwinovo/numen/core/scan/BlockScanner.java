package com.dwinovo.numen.core.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Shared spherical block search with a chunk-section palette short-circuit.
 * Factored out of the {@code scan_blocks} tool so the same efficient scan
 * backs both the LLM-facing perception tool and the internal
 * {@code MineBlockTaskGoal} (which repeatedly re-scans for the next nearest
 * target as it depletes a deposit).
 *
 * <h2>Performance</h2>
 * Naive search is {@code (2r+1)³} {@code getBlockState} calls (~15k for r=12).
 * Instead we iterate the chunk sections intersecting the bounding box and call
 * {@link LevelChunkSection#maybeHas(Predicate)} — which checks only the
 * section's palette (2-10 entries) before scanning its 4096 inner blocks.
 * Sections without any target block are skipped instantly (50-200× speedup for
 * sparse targets like ore).
 */
public final class BlockScanner {

    /**
     * Hard cap on collected matches. Exists for landscape-scale targets
     * (water, lava: an ocean inside a 48-block radius is ~10⁵ matching cells)
     * — without it the match list explodes in memory before sorting. Scanning
     * stops once the cap is hit, so for super-abundant targets the result is
     * "plenty of nearby hits" rather than the guaranteed global nearest;
     * for sparse targets (ores, structures) the cap is never reached.
     */
    private static final int MAX_COLLECT = 8_192;

    private BlockScanner() {}

    /**
     * The fully-loaded chunk at ({@code cx},{@code cz}), or {@code null} if it isn't loaded — a pure
     * cache read via {@link net.minecraft.server.level.ServerChunkCache#getChunkNow} that <b>never</b>
     * forces a load, generates, or bounces to the main thread. A synchronous/off-tick scan must never
     * drive chunk loading (that path can block the server thread on chunk I/O); long-range perception
     * over unloaded terrain is {@code ScanBlocksJob}'s budgeted job.
     */
    private static ChunkAccess loadedChunk(Level level, int cx, int cz) {
        return level instanceof ServerLevel serverLevel
                ? serverLevel.getChunkSource().getChunkNow(cx, cz)
                : null;
    }

    /** One match: world position, its state, and Euclidean distance from the search centre. */
    public record Hit(BlockPos pos, BlockState state, double distance) {}

    /**
     * Find every block in {@code targets} within {@code radius} (spherical,
     * Euclidean) of {@code center}, sorted nearest-first.
     *
     * @param level   world to search
     * @param center  search origin (entity feet position)
     * @param radius  spherical radius in blocks
     * @param targets block types to match (include variants, e.g. iron_ore +
     *                deepslate_iron_ore)
     * @return matches sorted by ascending distance; empty if none
     */
    public static List<Hit> findWithin(Level level, BlockPos center, int radius, Set<Block> targets) {
        if (targets.isEmpty()) return List.of();
        Predicate<BlockState> filter = state -> targets.contains(state.getBlock());
        double radiusSq = (double) radius * radius;

        int minChunkX = SectionPos.blockToSectionCoord(center.getX() - radius);
        int maxChunkX = SectionPos.blockToSectionCoord(center.getX() + radius);
        int minChunkZ = SectionPos.blockToSectionCoord(center.getZ() - radius);
        int maxChunkZ = SectionPos.blockToSectionCoord(center.getZ() + radius);
        int minSectionY = SectionPos.blockToSectionCoord(center.getY() - radius);
        int maxSectionY = SectionPos.blockToSectionCoord(center.getY() + radius);

        List<Hit> matches = new ArrayList<>();

        outer:
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                // LOADED chunks only (require=false): a synchronous scan must
                // never force chunk loads/generation — at radius 96 that used
                // to mean up to 49 sync loads inside one tick. Long-range
                // perception over unloaded terrain is ScanBlocksJob's job,
                // which loads under the global per-tick budget.
                ChunkAccess chunk = loadedChunk(level, cx, cz);
                if (chunk == null) continue;
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    scanChunkSection(level, chunk, cx, sy, cz, center, radius, radiusSq, filter, matches);
                    if (matches.size() >= MAX_COLLECT) break outer;
                }
            }
        }

        matches.sort(Comparator.comparingDouble(Hit::distance));
        return matches;
    }

    /**
     * Main-thread: snapshot the LOADED chunks intersecting the search box, with
     * no loading/generation. The only world-API touch the async scan needs from
     * the main thread — cheap chunk-map lookups, not a block sweep. Hand the
     * result to {@link #scanLoaded} on a background thread.
     */
    public static List<ChunkAccess> captureLoadedChunks(Level level, BlockPos center, int radius) {
        int minChunkX = SectionPos.blockToSectionCoord(center.getX() - radius);
        int maxChunkX = SectionPos.blockToSectionCoord(center.getX() + radius);
        int minChunkZ = SectionPos.blockToSectionCoord(center.getZ() - radius);
        int maxChunkZ = SectionPos.blockToSectionCoord(center.getZ() + radius);
        List<ChunkAccess> chunks = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                ChunkAccess chunk = loadedChunk(level, cx, cz);
                if (chunk != null) chunks.add(chunk);
            }
        }
        return chunks;
    }

    /**
     * Off-thread: read the captured chunks' section palettes (palette reads of
     * already-loaded
     * chunks). Reads can race with main-thread block writes, so each chunk is
     * guarded: a torn palette read skips that chunk this pass rather than
     * throwing. The result is advisory — callers re-validate every hit on the
     * main thread (prune / shaft) before mining.
     */
    public static List<Hit> scanLoaded(Level level, List<ChunkAccess> chunks,
                                       BlockPos center, int radius, Set<Block> targets) {
        if (targets.isEmpty() || chunks.isEmpty()) return List.of();
        Predicate<BlockState> filter = state -> targets.contains(state.getBlock());
        double radiusSq = (double) radius * radius;
        int minSectionY = SectionPos.blockToSectionCoord(center.getY() - radius);
        int maxSectionY = SectionPos.blockToSectionCoord(center.getY() + radius);

        List<Hit> matches = new ArrayList<>();
        outer:
        for (ChunkAccess chunk : chunks) {
            int cx = chunk.getPos().x;
            int cz = chunk.getPos().z;
            try {
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    scanChunkSection(level, chunk, cx, sy, cz, center, radius, radiusSq, filter, matches);
                    if (matches.size() >= MAX_COLLECT) break outer;
                }
            } catch (Throwable concurrentPaletteRead) {
                // The main thread mutated this chunk mid-read; skip it this pass.
            }
        }
        matches.sort(Comparator.comparingDouble(Hit::distance));
        return matches;
    }

    // ==================== 环形扫描(以身体为圆心,加载区为边界) ====================

    /** 捕获护栏:环半径超过该值(chunk)一律截断。正常服务端视距远小于此,纯防御。 */
    private static final int MAX_RING_RADIUS_CHUNKS = 64;

    /**
     * 主线程捕获的环序 chunk 引用:{@code rings.get(i)} = 整数圆环
     * {@code xoff²+zoff²==i} 上的已加载 chunk(无格点的环为空表占位)。
     * 捕获在第一个"有格点但无一加载"的环处截断——那就是加载区的边缘,
     * 后台扫描的事实边界。
     */
    public record RingCapture(List<List<ChunkAccess>> rings, BlockPos center) {}

    /** 主线程:以 {@code center} 所在 chunk 为圆心逐环捕获已加载 chunk 引用,
     *  不触发任何加载/生成。交给 {@link #scanRings} 在后台线程读。 */
    public static RingCapture captureRings(Level level, BlockPos center) {
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        List<List<ChunkAccess>> rings = new ArrayList<>();
        int guardSq = MAX_RING_RADIUS_CHUNKS * MAX_RING_RADIUS_CHUNKS;
        for (int ringSq = 0; ringSq <= guardSq; ringSq++) {
            boolean hasLattice = false;
            List<ChunkAccess> ring = new ArrayList<>(4);
            int reach = (int) Math.sqrt(ringSq);
            for (int xoff = -reach; xoff <= reach; xoff++) {
                for (int zoff = -reach; zoff <= reach; zoff++) {
                    if (xoff * xoff + zoff * zoff != ringSq) continue;
                    hasLattice = true;
                    ChunkAccess chunk = loadedChunk(level, centerChunkX + xoff, centerChunkZ + zoff);
                    if (chunk != null) ring.add(chunk);
                }
            }
            if (hasLattice && ring.isEmpty()) break;   // 加载区边缘
            rings.add(ring);
        }
        return new RingCapture(rings, center.immutable());
    }

    /**
     * 后台线程:按环序由近及远扫描捕获的 chunk。提前收工条件:已凑够
     * {@code max} 个命中,且(超出 {@code maxChunkRadius} 环,或已扫过第 1 环
     * 且有玩家 Y±{@code yLevelThreshold} 内的命中)。目标稀缺时一路扫到
     * 捕获截断处(加载区边缘)。每个 chunk 内 section 按离玩家 Y 最近优先;
     * 结果无序,调用方自行按距离排序截断。撕裂的调色板读跳过该 chunk。
     */
    public static List<Hit> scanRings(Level level, RingCapture cap, Set<Block> targets,
                                      int max, int yLevelThreshold, int maxChunkRadius) {
        if (targets.isEmpty()) return List.of();
        Predicate<BlockState> filter = state -> targets.contains(state.getBlock());
        BlockPos center = cap.center();
        int minY = level.getMinY();
        int playerY = center.getY() - minY;
        int playerSection = playerY >> 4;
        int[] order = java.util.stream.IntStream.range(0, level.getSectionsCount()).boxed()
                .sorted(Comparator.comparingInt(y -> Math.abs(y - playerSection)))
                .mapToInt(Integer::intValue).toArray();
        int maxRadiusSq = maxChunkRadius * maxChunkRadius;
        // 收集硬顶:环序天然由近及远,最先入表的就是最近的一批;超过 4×max 的部分
        // 反正会被调用方的距离裁剪丢弃,继续扫只是给主线程的合并/校验层制造成千上万
        // 条注定扔掉的条目(地表下令挖深层矿时"同层提前收工"永不触发,没有这个顶,
        // 一轮扫描能带回整个加载区的全部矿位)。
        int hardCap = max * 4;
        List<Hit> res = new ArrayList<>();
        boolean foundWithinY = false;
        outer:
        for (int ringSq = 0; ringSq < cap.rings().size(); ringSq++) {
            for (ChunkAccess chunk : cap.rings().get(ringSq)) {
                try {
                    if (scanWholeChunk(chunk, minY, filter, res,
                            max, yLevelThreshold, playerY, order, center)) {
                        foundWithinY = true;
                    }
                } catch (Throwable concurrentPaletteRead) {
                    // 主线程改了这个 chunk 的调色板:本轮跳过。
                }
                if (res.size() >= hardCap) {
                    break outer;
                }
            }
            if (res.size() >= max
                    && (ringSq > maxRadiusSq || (ringSq > 1 && foundWithinY))) {
                break;
            }
        }
        return res;
    }

    /** 扫一个 chunk 的全部 section(近 Y 优先),返回是否有玩家 Y 阈值内的命中。
     *  凑够 {@code max} 后:同层命中记 foundWithinY;层外命中在本 chunk 已见
     *  同层命中时直接返回(层外的不再要)。 */
    private static boolean scanWholeChunk(ChunkAccess chunk, int minY, Predicate<BlockState> filter,
                                          List<Hit> res, int max, int yLevelThreshold, int playerY,
                                          int[] order, BlockPos center) {
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        boolean foundWithinY = false;
        for (int y0 : order) {
            if (y0 < 0 || y0 >= sections.length) continue;
            LevelChunkSection section = sections[y0];
            if (section == null || section.hasOnlyAir()) continue;
            // 调色板短路:该 section 调色板里没有目标就整节跳过(纯加速)。
            if (!section.maybeHas(filter)) continue;
            int yReal = y0 << 4;
            var states = section.getStates();
            for (int yy = 0; yy < 16; yy++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = states.get(x, yy, z);
                        if (!filter.test(state)) continue;
                        int y = yReal | yy;
                        if (res.size() >= max) {
                            if (Math.abs(y - playerY) < yLevelThreshold) {
                                foundWithinY = true;
                            } else if (foundWithinY) {
                                return true;
                            }
                        }
                        int wx = baseX | x;
                        int wy = y + minY;
                        int wz = baseZ | z;
                        double dx = wx - center.getX();
                        double dy = wy - center.getY();
                        double dz = wz - center.getZ();
                        res.add(new Hit(new BlockPos(wx, wy, wz), state,
                                Math.sqrt(dx * dx + dy * dy + dz * dz)));
                    }
                }
            }
        }
        return foundWithinY;
    }

    /**
     * Scan ONE section of an already-resolved chunk (palette short-circuit
     * included), appending sphere-clipped matches to {@code out}. Public so
     * the budget-sliced {@code ScanBlocksJob} can meter exactly this unit of
     * work per permit.
     */
    public static void scanChunkSection(Level level, ChunkAccess chunk,
                                        int chunkX, int sectionY, int chunkZ,
                                        BlockPos center, int radius, double radiusSq,
                                        Predicate<BlockState> filter,
                                        List<Hit> out) {
        int idx = level.getSectionIndexFromSectionY(sectionY);
        if (idx < 0 || idx >= chunk.getSectionsCount()) return;
        LevelChunkSection section = chunk.getSection(idx);
        if (section == null || section.hasOnlyAir()) return;
        // Palette short-circuit: skip all 4096 inner blocks when the
        // section's palette holds no target.
        if (!section.maybeHas(filter)) return;
        scanSection(section, chunkX, sectionY, chunkZ, center, radius, radiusSq, filter, out);
    }

    private static void scanSection(LevelChunkSection section,
                                    int chunkX, int sectionY, int chunkZ,
                                    BlockPos center, int radius, double radiusSq,
                                    Predicate<BlockState> filter,
                                    List<Hit> out) {
        int baseX = SectionPos.sectionToBlockCoord(chunkX);
        int baseY = SectionPos.sectionToBlockCoord(sectionY);
        int baseZ = SectionPos.sectionToBlockCoord(chunkZ);
        for (int dx = 0; dx < 16; dx++) {
            int worldX = baseX + dx;
            int ddx = worldX - center.getX();
            if (ddx < -radius || ddx > radius) continue;
            for (int dy = 0; dy < 16; dy++) {
                int worldY = baseY + dy;
                int ddy = worldY - center.getY();
                if (ddy < -radius || ddy > radius) continue;
                for (int dz = 0; dz < 16; dz++) {
                    int worldZ = baseZ + dz;
                    int ddz = worldZ - center.getZ();
                    if (ddz < -radius || ddz > radius) continue;
                    double distSq = (double) ddx * ddx + (double) ddy * ddy + (double) ddz * ddz;
                    if (distSq > radiusSq) continue;
                    BlockState state = section.getBlockState(dx, dy, dz);
                    if (!filter.test(state)) continue;
                    out.add(new Hit(new BlockPos(worldX, worldY, worldZ), state, Math.sqrt(distSq)));
                }
            }
        }
    }
}
