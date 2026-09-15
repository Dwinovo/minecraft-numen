package com.dwinovo.numen.core.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * 读地形的原语:只读已加载的 chunk({@link #loadedChunk})、一条命中({@link Hit}),和<b>小盒里找最近</b>
 * ({@link #nearestBlock})。远处找方块是 {@link BlockSearch} 的事——它经共享索引读,调色板里没有目标的
 * 整节一次跳过。
 *
 * <p>{@link #nearestBlock} 是逐格读:它问的是"手边够得着的有没有",盒子只有十几格见方
 * 且必在加载区内,搭一次环序搜索的架子比直接读还贵。
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
     * 身边小盒范围内、离 {@code eye} 最近的满足 {@code match} 的方块;超出
     * {@code maxDist} 或没有则 null。同步逐格读,只适合以身体为中心的小半径
     * (必在加载区内)——远程找方块走 {@code BlockSearch} 的预算切片。
     *
     * <p>谓词带位置:有的判据要问方块实体(见 {@code CraftOps} 的行为探测),
     * 光有状态答不了。空气格不问谓词,直接跳过。
     */
    public static BlockPos nearestBlock(Level level, BlockPos base, Vec3 eye,
                                        int hr, int vr, double maxDist,
                                        java.util.function.BiPredicate<BlockPos, BlockState> match) {
        BlockPos best = null;
        double bestD = maxDist * maxDist;
        for (BlockPos p : BlockPos.betweenClosed(base.offset(-hr, -vr, -hr), base.offset(hr, vr, hr))) {
            BlockState state = level.getBlockState(p);
            if (state.isAir() || !match.test(p, state)) {
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
}
