package com.dwinovo.numen.pathing.search;

import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;

import com.dwinovo.numen.pathing.plan.WorldView;
import com.dwinovo.numen.pathing.world.CellFlags;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 搜索读的世界:派发那一刻的只读快照({@link WorldSnapshot}),外加哪些列在快照里。快照之外的列读出来是空气,那不是
 * 测量,所以搜索碰到它们就停在边上,不往里走。
 */
public interface SearchView extends WorldView {

    /** {@code (x, z)} 这一列所在的区块在快照里。 */
    boolean isLoaded(int x, int z);

    /** 同 {@link WorldView#cellFlags},这一列不在快照里的格带 {@link CellFlags#UNLOADED}。 */
    @Override
    default int cellFlags(int x, int y, int z) {
        BlockState state = loadedBlockState(new BlockPos(x, y, z));
        return state == null ? CellFlags.UNLOADED : CellFlags.of(state);
    }

    /** 一格的方块状态;这一列不在快照里为 null(读{@link #getBlockState}是空气,不是测量)。读一格问一次,比先问 {@link #isLoaded} 再读省一趟查区块。 */
    default BlockState loadedBlockState(BlockPos pos) {
        return isLoaded(pos.getX(), pos.getZ()) ? getBlockState(pos) : null;
    }

    /**
     * 快照里第 {@code fromY} 层(含)到 {@code toY} 层(不含)之间,状态让 {@code wanted} 答是的每一格,回调它的状态与所在的层(格的 y)。
     * 估价靠它知道每一层有什么台阶、水、梯子,只问要用到的那几层。{@code wanted} 会对每个相关区段调色板里列着的每种状态问一遍
     * (调色板可能多列几种已经没有的),只有答是的状态才逐格找;高度范围正好是一个区段,每个区段的调色板就只问一遍。
     */
    void forEachBlock(int fromY, int toY, Predicate<BlockState> wanted, ObjIntConsumer<BlockState> visitor);

    /**
     * {@code (x, y, z)} 所在的区段里可能有状态让 {@code wanted} 答是的方块:问的是区段的调色板,答"否"就是一格也没有,答"是"只是也许有
     * (全局调色板总答也许有)。估价靠它跳过整段不含水的区段。
     */
    boolean sectionMayHold(int x, int y, int z, Predicate<BlockState> wanted);

    /**
     * 快照边界上的列:在快照里、四个正方向上紧挨着不在快照里的列。目标在快照之外时,路线要从这些列出去,估价从目标往外算时
     * 它们带着各自到目标的直线下界当种子({@link Columns})。整个世界都在快照里(没有边界)就一列也不回调。
     */
    void forEachBoundaryColumn(ColumnVisitor visitor);

    /** {@link #forEachBoundaryColumn} 逐列回调的接收方。 */
    @FunctionalInterface
    interface ColumnVisitor {
        void accept(int x, int z);
    }
}
