package com.dwinovo.numen.core.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * 读地形的原语:只读已加载的 chunk({@link #loadedChunk})与一条命中({@link Hit})。找方块是 {@link BlockSearch}
 * 的事——它经共享索引读,调色板里没有目标的整节一次跳过。
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
}
