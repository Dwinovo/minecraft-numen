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
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 找方块的两样东西:<b>扫一节</b>的原语({@link #scanChunkSection}),和把它按环序串起来的
 * <b>离线环形扫</b>({@link #captureRings} + {@link #scanRings},goto 的 FIND 模式用)。
 *
 * <p>扫一节这份实现是全仓唯一的一份——现扫的 {@link ScanBlocksJob} 按预算一节一节切,
 * 这里的环形扫一口气走完,两边调的是同一个方法。由近及远怎么定义、什么时候可以不看了,
 * 判据都在 {@link SearchGeometry}。
 *
 * <h2>为什么不是逐格读</h2>
 * 朴素搜索是 {@code (2r+1)³} 次 {@code getBlockState}(r=12 就 ~15k 次)。这里改成遍历
 * 与包围盒相交的 chunk section,先问 {@link LevelChunkSection#maybeHas(Predicate)}——
 * 只看该节调色板的 2-10 项,没有目标就整节 4096 格一次跳过。稀疏目标(矿)能快 50-200 倍。
 */
public final class BlockScanner {

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

    /** 捕获护栏:环半径超过该值(chunk)一律夹住。正常服务端视距远小于此,纯防御。 */
    private static final int MAX_RING_RADIUS_CHUNKS = 64;

    /**
     * 主线程捕获的环序 chunk 引用:{@code rings.get(k)} = 切比雪夫第 k 个方环上的已加载
     * chunk。环的形状用 {@link RingSpiral},和现扫的 {@link ScanBlocksJob} 同一种——
     * 于是 {@link SearchGeometry#ringFloorDistance} 的距离下界对两边都成立,同一片地
     * 不会给出两种"最近"。
     *
     * <p>未加载的 chunk 只是不在表里,不截断后面的环:加载区中间有洞时,跨过洞继续找。
     */
    public record RingCapture(List<List<ChunkAccess>> rings, BlockPos center) {}

    /** 主线程:以 {@code center} 所在 chunk 为圆心逐环捕获已加载 chunk 引用,
     *  不触发任何加载/生成。交给 {@link #scanRings} 在后台线程读。 */
    public static RingCapture captureRings(Level level, BlockPos center, int maxChunkRadius) {
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        int maxRing = Math.clamp(maxChunkRadius, 0, MAX_RING_RADIUS_CHUNKS);
        List<List<ChunkAccess>> rings = new ArrayList<>(maxRing + 1);
        for (int ring = 0; ring <= maxRing; ring++) {
            int perimeter = RingSpiral.perimeter(ring);
            List<ChunkAccess> loaded = new ArrayList<>(perimeter);
            for (int i = 0; i < perimeter; i++) {
                int[] d = RingSpiral.offset(ring, i);
                ChunkAccess chunk = loadedChunk(level, centerChunkX + d[0], centerChunkZ + d[1]);
                if (chunk != null) loaded.add(chunk);
            }
            rings.add(loaded);
        }
        return new RingCapture(rings, center.immutable());
    }

    /**
     * 后台线程:按环序由近及远扫描捕获的 chunk,收够就停。判据全部取自
     * {@link SearchGeometry}——哪一节先看({@code sectionOrder}),什么时候可以不看了
     * ({@code canStop} 的精确界:攒够的 {@code max} 个已经比下一环最近的可能还近)。
     * 现扫的 {@link ScanBlocksJob} 读的是同一份,同一片地两条路给同一个"最近"。
     *
     * <p>扫一节的活走 {@link #scanChunkSection},与现扫共用一份实现,球面裁剪、
     * 调色板短路、Hit 的造法都只有一处。结果无序,调用方自行按距离排序截断。
     *
     * <p>撕裂的调色板读跳过<b>那一节</b>:主线程随时可能给某一节的调色板扩容,
     * 后台读到一半会炸。代价是偶尔漏一节,换的是不必把这活压回主线程去排队。
     */
    public static List<Hit> scanRings(Level level, RingCapture cap, Set<Block> targets,
                                      int max, int maxChunkRadius) {
        if (targets.isEmpty()) return List.of();
        Predicate<BlockState> filter = state -> targets.contains(state.getBlock());
        BlockPos center = cap.center();
        int radius = maxChunkRadius * 16;
        double radiusSq = (double) radius * radius;
        int minSection = level.getMinSection();
        int[] order = SearchGeometry.sectionOrder(minSection,
                minSection + level.getSectionsCount() - 1,
                SectionPos.blockToSectionCoord(center.getY()));
        // 收集硬顶:环序天然由近及远,最先入表的就是最近的一批。目标铺天盖地时,超过
        // 4×max 的部分反正会被调用方的距离裁剪丢弃,继续扫只是给主线程的合并/校验层
        // 制造成千上万条注定扔掉的条目。
        int hardCap = max * 4;
        List<Hit> res = new ArrayList<>();
        SearchGeometry.NearestBound bound = new SearchGeometry.NearestBound(max);
        int fed = 0;
        for (int ring = 0; ring < cap.rings().size(); ring++) {
            for (ChunkAccess chunk : cap.rings().get(ring)) {
                for (int sy : order) {
                    try {
                        scanChunkSection(level, chunk, chunk.getPos().x, sy, chunk.getPos().z,
                                center, radius, radiusSq, filter, res);
                    } catch (Throwable concurrentPaletteRead) {
                        // 主线程正在改这一节的调色板:跳过这一节。
                    }
                }
                while (fed < res.size()) {
                    bound.offer(res.get(fed++).distance());
                }
                if (res.size() >= hardCap) {
                    return res;
                }
            }
            if (SearchGeometry.canStop(ring, bound)) {
                break;
            }
        }
        return res;
    }

    /**
     * 扫一节:已解析好的 chunk 里的一个 section,调色板短路 + 球面裁剪,命中追加进
     * {@code out}。全仓找方块最终都落到这里——{@link ScanBlocksJob} 一个配额换一节,
     * {@link #scanRings} 一口气走完一串。公开是因为前者要按这个粒度计费。
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
