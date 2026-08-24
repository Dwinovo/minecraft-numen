package com.dwinovo.numen.core.pathing.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * 活世界的"只读已加载"视图:读到未加载区块一律返回空气/空流体,
 * <b>绝不触发同步区块加载或生成</b>({@code getChunkNow} 只查内存)。
 * 执行期逐 tick 的成本复核与格集重算读的世界经此钳制——路径末端
 * 伸进未生成地形时,每 tick 的读取不会把服务端线程卡在原地生成
 * 区块上。已加载区块的读取与直读 level 完全一致。
 */
public final class LoadedOnlyView implements BlockGetter {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final ServerLevel level;

    private LoadedOnlyView(ServerLevel level) {
        this.level = level;
    }

    /**
     * 包一层钳制视图;非服务端世界(测试壳)直接返回原视图,不钳制。
     */
    public static BlockGetter of(Level level) {
        return level instanceof ServerLevel server ? new LoadedOnlyView(server) : level;
    }

    /** (方块坐标)所在 chunk 此刻是否已加载在内存中。 */
    public boolean isLoaded(int x, int z) {
        return chunkAt(x, z) != null;
    }

    private LevelChunk chunkAt(int blockX, int blockZ) {
        return level.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return AIR;
        }
        LevelChunk chunk = chunkAt(pos.getX(), pos.getZ());
        return chunk == null ? AIR : chunk.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return Fluids.EMPTY.defaultFluidState();
        }
        LevelChunk chunk = chunkAt(pos.getX(), pos.getZ());
        return chunk == null ? Fluids.EMPTY.defaultFluidState() : chunk.getFluidState(pos);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        LevelChunk chunk = chunkAt(pos.getX(), pos.getZ());
        return chunk == null ? null : chunk.getBlockEntity(pos);
    }

    @Override
    public int getHeight() {
        return level.getHeight();
    }

    @Override
    public int getMinY() {
        return level.getMinY();
    }
}
