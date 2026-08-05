package com.dwinovo.numen.core.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
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
 * Shared block search with a chunk-section palette short-circuit: ring-ordered
 * capture/scan for goto's find mode ({@code MoveToCompanionTask}) and the
 * per-section primitive {@code ScanBlocksJob} slices under its budget.
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
     * forces a load, generates, or bounces to the main thread. Every scan in this package reads terrain
     * through here: {@code getChunk} with a status would block the server thread on chunk I/O or
     * generation, and a scan is a perception query — it reports what is loaded and says so, it does not
     * make the world bigger to answer.
     */
    static ChunkAccess loadedChunk(Level level, int cx, int cz) {
        return level instanceof ServerLevel serverLevel
                ? serverLevel.getChunkSource().getChunkNow(cx, cz)
                : null;
    }

    /** One match: world position, its state, and Euclidean distance from the search centre. */
    public record Hit(BlockPos pos, BlockState state, double distance) {}

    /**
     * 身边小盒范围内、离 {@code eye} 最近的指定方块;超出 {@code maxDist} 或
     * 没有则 null。同步逐格读,只适合以身体为中心的小半径(必在加载区内)——
     * 远程找方块走 {@code ScanBlocksJob} 的预算切片。
     */
    public static BlockPos nearestBlock(Level level, BlockPos base, Vec3 eye,
                                        int hr, int vr, double maxDist, Block target) {
        BlockPos best = null;
        double bestD = maxDist * maxDist;
        for (BlockPos p : BlockPos.betweenClosed(base.offset(-hr, -vr, -hr), base.offset(hr, vr, hr))) {
            if (!level.getBlockState(p).is(target)) {
                continue;
            }
            double d = eye.distanceToSqr(Vec3.atCenterOf(p));
            if (d < bestD) {
                bestD = d;
                best = p.immutable();
            }
        }
        return best;
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
     * 后台线程:按环序由近及远扫描捕获的 chunk。每个 chunk 内 section 的访问序取自
     * {@link SearchGeometry#sectionOrder}(离玩家 Y 最近的先看),与现扫的
     * {@link ScanBlocksJob} 同一份判据。
     *
     * <p>收工条件仍是本地的一套:已凑够 {@code max} 个命中,且(超出
     * {@code maxChunkRadius} 环,或已扫过第 1 环且有玩家 Y±{@code yLevelThreshold}
     * 内的命中)。它是近似的,而且和 {@link SearchGeometry#canStop} 的精确界不是一个
     * 答案——这里的环是欧氏整数环({@link #captureRings} 的 {@code xoff²+zoff²}),
     * 精确界算的是切比雪夫方环。两者归一要连着环的定义一起做。
     *
     * <p>目标稀缺时一路扫到捕获截断处(加载区边缘)。结果无序,调用方自行按距离排序
     * 截断。撕裂的调色板读跳过该 chunk。
     */
    public static List<Hit> scanRings(Level level, RingCapture cap, Set<Block> targets,
                                      int max, int yLevelThreshold, int maxChunkRadius) {
        if (targets.isEmpty()) return List.of();
        Predicate<BlockState> filter = state -> targets.contains(state.getBlock());
        BlockPos center = cap.center();
        int minY = level.getMinBuildHeight();
        int playerY = center.getY() - minY;
        int playerSection = playerY >> 4;
        // section 索引空间(0..count),与 scanWholeChunk 里的 sections[y0] 对齐。
        int[] order = SearchGeometry.sectionOrder(0, level.getSectionsCount() - 1, playerSection);
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
