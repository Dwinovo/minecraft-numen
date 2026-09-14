package com.dwinovo.numen.core.pathing.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 任务层与挖掘器共用的方块工具:脚位、可收获。一格能不能穿、能不能站归
 * {@link com.dwinovo.numen.core.pathing.spec.CellClass};挖掘与放置的成本判定归
 * {@link com.dwinovo.numen.core.pathing.moves.MovementHelper};这一格许不许动归权限层
 * ({@link com.dwinovo.numen.permission.Permission})。
 */
public final class BlockHelper {

    private BlockHelper() {}

    /**
     * The body's feet cell for pathing — the
     * position nudged up 0.1251 (so sinking on soul sand / farmland doesn't read a block
     * low) and, when that cell is a SLAB, taken as the cell ABOVE it. The slab adjustment
     * is what reconciles standing on a bottom slab (feet at slab.y+0.5) with the move graph,
     * where a move onto a slab targets the cell ABOVE the slab.
     */
    public static BlockPos playerFeet(BlockGetter level, double x, double y, double z) {
        BlockPos f = BlockPos.containing(x, y + 0.1251, z);
        if (level.getBlockState(f).getBlock() instanceof SlabBlock) {
            return f.above();
        }
        return f;
    }

    /**
     * Can {@code inv}'s tools actually HARVEST {@code state}'s drops — i.e. break it and get the
     * item, not just destroy it? True when the block drops without a tool, or ANY inventory slot
     * holds the correct tool. Mining a {@code requiresCorrectToolForDrops} block with the wrong
     * tool removes it for nothing, so the cost model vetoes it and the break/mine tools refuse it.
     * Single source of truth — paired with {@code switchToBestTool}, which can swap a backpack
     * tool into the hand. DELIBERATELY scans the WHOLE inventory, not just the
     * hotbar (a real player's quick-switch set): a companion can dig into its own pack, so the
     * gate + cost + execution all scan the whole inventory together.
     */
    public static boolean canHarvest(Container inv, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isCorrectToolForDrops(state)) {
                return true;
            }
        }
        return false;
    }
}
