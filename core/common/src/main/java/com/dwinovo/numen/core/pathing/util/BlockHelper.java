package com.dwinovo.numen.core.pathing.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 任务层与挖掘器共用的方块工具:脚位、可收获、硬禁挖。一格能不能穿、能不能站归
 * {@link com.dwinovo.numen.core.pathing.spec.CellClass};挖掘与放置的成本判定归
 * {@link com.dwinovo.numen.core.pathing.moves.MovementHelper}。
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

    /**
     * 命中 do_not_break 方块标签的方块:硬禁挖的唯一真源,任何开关
     * 也不解除。默认成员是设施类(床/门/活板门/栅栏门,见
     * ModBlockTagData);工作台/熔炉/箱子/陷阱箱等常规功能方块不在
     * 硬禁内,它们走 NavSettings.blocksToAvoidBreaking 软清单
     * (挖掘成本 ×10,无路可走仍会破坏)。数据包可往此标签追加任何要
     * 硬禁挖的方块;带方块实体的方块(漏斗/潜影盒/刷怪笼/信标等)默认
     * 与泥土一样可破坏、无惩罚,除非数据包把它们加进此标签。
     */
    public static boolean shouldAvoidBreaking(BlockGetter level, BlockPos pos) {
        // 标签成员测试只读不可变 BlockState holder,off-thread 搜索可安全调用。
        BlockState state = level.getBlockState(pos);
        return state.is(com.dwinovo.numen.core.init.InitTag.DO_NOT_BREAK);
    }
}
