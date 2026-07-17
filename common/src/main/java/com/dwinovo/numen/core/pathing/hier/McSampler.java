package com.dwinovo.numen.core.pathing.hier;

import com.dwinovo.numen.core.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

/**
 * {@link CellSampler} over a pathing world view (the same frozen snapshot the
 * search reads). Tick-thread only (mutable cursor).
 *
 * <p>The uniformity probe reads a sparse 3×3×3 grid: all air → AIR, all
 * solid-and-diggable → SOLID_BREAKABLE, else exact scan. A probe miss only
 * errs in the sound directions (see {@link CellSampler.Uniform}).
 */
final class McSampler implements CellSampler {

    private final BlockGetter view;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    McSampler(BlockGetter view) {
        this.view = view;
    }

    @Override
    public boolean passable(int x, int y, int z) {
        return BlockHelper.canWalkThrough(view, cursor.set(x, y, z));
    }

    @Override
    public boolean breakable(int x, int y, int z) {
        cursor.set(x, y, z);
        return BlockHelper.isBreakable(view, cursor)
                && !BlockHelper.isHazard(view, cursor)
                && !BlockHelper.shouldAvoidBreaking(view, cursor);
    }

    private static final int[] PROBE = {0, 8, 15};

    @Override
    public Uniform uniform(int sx, int sy, int sz) {
        int bx = sx << 4;
        int by = sy << 4;
        int bz = sz << 4;
        boolean allAir = true;
        boolean allSolidBreakable = true;
        for (int px : PROBE) {
            for (int py : PROBE) {
                for (int pz : PROBE) {
                    int x = bx + px;
                    int y = by + py;
                    int z = bz + pz;
                    boolean air = view.getBlockState(cursor.set(x, y, z)).isAir();
                    if (!air) {
                        allAir = false;
                        if (passable(x, y, z) || !breakable(x, y, z)) {
                            allSolidBreakable = false;
                        }
                    } else {
                        allSolidBreakable = false;
                    }
                    if (!allAir && !allSolidBreakable) {
                        return Uniform.MIXED_OR_UNKNOWN;
                    }
                }
            }
        }
        return allAir ? Uniform.AIR : Uniform.SOLID_BREAKABLE;
    }
}
