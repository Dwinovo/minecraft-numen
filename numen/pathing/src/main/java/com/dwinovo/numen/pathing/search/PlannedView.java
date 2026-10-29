package com.dwinovo.numen.pathing.search;

import java.util.List;
import java.util.function.Predicate;

import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.EditedView;
import com.dwinovo.numen.pathing.world.CellFlags;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;

/**
 * 接着一条还没走完的路线往下搜时搜索读的世界:派发那一刻的快照,叠上身体走到那条路线终点之前还要做的改动——她到那里时世界就是这样。
 * 搭到半空的桥、往上垫的台阶、挖开的隧道在快照里都还没有,只叠最后一步的话,接着搜的那一次看不见来路,以为退不回去、下不来。
 * 叠法与搜索展开节点、执行往前核是同一种({@link EditedView}),改动取自路线({@link Route#edits(int, int)})。
 *
 * <p>没改过的格照快照的快路径读(标志字成层现成的);改过的格按叠完的方块状态推出标志字。估价按层扫方块({@link #forEachBlock})时,
 * 改过的格的新状态也报一遍,原来的状态照快照报:多报只会让估价更低,仍是下界。
 */
final class PlannedView implements SearchView {

    private final SearchView base;
    private final EditedView after;

    private PlannedView(SearchView base, EditedView after) {
        this.base = base;
        this.after = after;
    }

    /** {@code base} 做完 {@code edits} 之后的样子;没有改动就是 {@code base} 本身。 */
    static SearchView of(SearchView base, List<Edit> edits) {
        return edits.isEmpty() ? base : new PlannedView(base, EditedView.over(base, edits));
    }

    private boolean changed(int x, int yMin, int yMax, int z) {
        return after.changed(x, yMin, yMax, z);
    }

    @Override
    public boolean isLoaded(int x, int z) {
        return base.isLoaded(x, z);
    }

    @Override
    public BlockState loadedBlockState(BlockPos pos) {
        return base.isLoaded(pos.getX(), pos.getZ()) ? after.getBlockState(pos) : null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return after.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return after.getFluidState(pos);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return after.getBlockEntity(pos);
    }

    @Override
    public int cellFlags(int x, int y, int z) {
        if (!changed(x, y, y, z)) {
            return base.cellFlags(x, y, z);
        }
        return base.isLoaded(x, z) ? CellFlags.of(after.getBlockState(new BlockPos(x, y, z))) : CellFlags.UNLOADED;
    }

    @Override
    public int cellFlags(int x, int y, int z, int n, byte[] into, int at) {
        if (!changed(x, y, y + n - 1, z)) {
            return base.cellFlags(x, y, z, n, into, at);
        }
        return SearchView.super.cellFlags(x, y, z, n, into, at);
    }

    @Override
    public boolean airSection(int x, int y, int z) {
        return after.airSection(x, y, z);
    }

    @Override
    public void forEachBlock(int fromY, int toY, Predicate<BlockState> wanted, BlockVisitor visitor) {
        base.forEachBlock(fromY, toY, wanted, visitor);
        after.forEachChanged((pos, state) -> {
            if (pos.getY() >= fromY && pos.getY() < toY && wanted.test(state)) {
                visitor.accept(state, pos.getX(), pos.getY(), pos.getZ());
            }
        });
    }

    @Override
    public int getHeight() {
        return base.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return base.getMinBuildHeight();
    }

    @Override
    public WorldBorder border() {
        return base.border();
    }

    @Override
    public boolean ultraWarm() {
        return base.ultraWarm();
    }
}
