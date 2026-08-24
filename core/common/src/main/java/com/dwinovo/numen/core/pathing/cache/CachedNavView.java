package com.dwinovo.numen.core.pathing.cache;

import com.dwinovo.numen.core.pathing.util.BlockEntityAware;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

/**
 * A {@link BlockGetter} over a {@link LoadedChunks} snapshot — the search-side world view.
 * Loaded chunk → read its LIVE section palette; not in the snapshot (unloaded) → AIR
 * (an optimistic miss).
 *
 * <p><b>Caching is a single last-chunk pointer, not a per-cell memo.</b> A* expands locally, so consecutive block reads almost
 * always land in the same chunk — {@link #prev} short-circuits those to a direct palette
 * read with zero per-read allocation; only a chunk boundary costs one snapshot lookup.
 * A per-position {@code Long2ObjectOpenHashMap} memo (the previous design) instead balloons
 * to millions of entries over a multi-second search and repeatedly resizes, and the
 * resulting GC churn stop-the-worlds the (integrated) server thread — every entity stutters
 * in lockstep. The last-chunk cache has O(1) footprint and never allocates on the hot path.
 *
 * <p>Single-threaded per instance: one search runs on one worker, so the mutable
 * {@link #prev} needs no synchronisation.
 */
public final class CachedNavView implements BlockGetter, BlockEntityAware {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final LoadedChunks loaded;
    private final Level level;

    /** Last chunk read from — see class doc. Only ever holds a snapshot chunk (or null). */
    private LevelChunk prev;

    public CachedNavView(LoadedChunks loaded, Level level) {
        this.loaded = loaded;
        this.level = level;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return read(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Was the chunk containing block-column ({@code blockX},{@code blockZ}) captured in the
     *  snapshot? {@code false} means every read there was the optimistic AIR miss — prices
     *  computed from it are guesses, not measurements. */
    public boolean isLoaded(int blockX, int blockZ) {
        return loaded.at(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ)) != null;
    }

    private BlockState read(int x, int y, int z) {
        if (y < level.getMinY() || y >= level.getMinY() + level.getHeight()) {
            return AIR;
        }
        int chunkX = SectionPos.blockToSectionCoord(x);
        int chunkZ = SectionPos.blockToSectionCoord(z);
        // Great cache locality: A* expands locally, so reads usually land in the same chunk as the previous one.
        LevelChunk chunk = prev;
        if (chunk == null || chunk.getPos().x != chunkX || chunk.getPos().z != chunkZ) {
            chunk = loaded.at(chunkX, chunkZ);
            if (chunk != null) {
                prev = chunk;   // cache hits only; unloaded stays a cheap re-miss
            }
        }
        if (chunk == null) {
            return AIR;   // unloaded / outside the snapshot — optimistic miss → AIR
        }
        try {
            int idx = level.getSectionIndex(y);
            LevelChunkSection[] sections = chunk.getSections();
            if (idx < 0 || idx >= sections.length) {
                return AIR;
            }
            LevelChunkSection section = sections[idx];
            return section.hasOnlyAir() ? AIR : section.getBlockState(x & 15, y & 15, z & 15);
        } catch (RuntimeException race) {
            // An off-thread read raced a main-thread palette resize (rare). Yield AIR; the executor
            // re-costs against the live world and replans, so a one-off bad cell self-corrects.
            return AIR;
        }
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public boolean hasBlockEntity(BlockPos pos) {
        return loaded.hasBlockEntity(pos);   // from the main-thread snapshot — safe off-thread
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        // Can't reconstruct a live block entity off-thread. The only search-path caller, the don't-grief
        // check, now goes through hasBlockEntity instead, so returning null here is safe.
        return null;
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
