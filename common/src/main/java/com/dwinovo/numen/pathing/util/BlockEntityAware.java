package com.dwinovo.numen.pathing.util;

import net.minecraft.core.BlockPos;

/**
 * A world view that can answer "is there a block entity here?" WITHOUT a live world read where it
 * matters. The cache view ({@code CachedNavView}) answers from a snapshot captured on the main thread,
 * so the don't-grief check ({@link BlockHelper#shouldAvoidBreaking}) is safe to run on a worker thread;
 * the live view ({@code NavSnapshot}) just asks the level. A plain {@code BlockGetter} that doesn't
 * implement this falls back to {@code getBlockEntity != null}.
 */
public interface BlockEntityAware {
    boolean hasBlockEntity(BlockPos pos);
}
