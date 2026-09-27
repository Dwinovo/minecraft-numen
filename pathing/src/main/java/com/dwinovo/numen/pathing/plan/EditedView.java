package com.dwinovo.numen.pathing.plan;

import java.util.List;

import com.dwinovo.numen.pathing.world.Semantics;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;

/**
 * 做了一些改动之后的世界:把改动叠在一个只读视图上,自己也是只读视图。一件改动让世界变成什么样只写在这里——挖掉的格
 * 只剩它原来含着的液体,放下的格是那种方块的默认状态,开关过的门连同另一半一起翻转。
 *
 * <p>规划一步时,草稿在它上面叠这一步设想的改动;搜索展开一个节点时,在快照上叠"走到这个节点的那一步"做过的改动,
 * 下一步的前提看到的就是身体此刻真正面对的世界。
 */
public final class EditedView implements WorldView {

    private final WorldView base;
    private final Long2ObjectOpenHashMap<BlockState> changed = new Long2ObjectOpenHashMap<>(4);

    EditedView(WorldView base) {
        this.base = base;
    }

    /** {@code base} 做完 {@code edits} 之后的样子;没有改动就是 {@code base} 本身。 */
    public static WorldView after(WorldView base, List<Edit> edits) {
        if (edits.isEmpty()) {
            return base;
        }
        EditedView view = new EditedView(base);
        for (Edit edit : edits) {
            switch (edit) {
                case Edit.Dig dig -> view.dig(dig.pos());
                case Edit.Place place -> view.place(place.pos(), place.block());
                case Edit.Door door -> view.toggle(door.pos());
            }
        }
        return view;
    }

    // ==================== 改动 ====================

    /** 挖掉:只剩这一格原来含着的液体。 */
    void dig(BlockPos pos) {
        changed.put(pos.asLong(), getBlockState(pos).getFluidState().createLegacyBlock());
    }

    /** 放下一块 {@code block}。 */
    void place(BlockPos pos, Block block) {
        changed.put(pos.asLong(), block.defaultBlockState());
    }

    /** 开关一扇门;门的另一半照原版一起翻转。 */
    void toggle(BlockPos pos) {
        BlockState state = getBlockState(pos);
        changed.put(pos.asLong(), Semantics.toggled(state));
        if (state.getBlock() instanceof DoorBlock) {
            BlockPos other = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState half = getBlockState(other);
            if (half.is(state.getBlock())) {
                changed.put(other.asLong(), Semantics.toggled(half));
            }
        }
    }

    /** 这一格改过。 */
    boolean changed(BlockPos pos) {
        return changed.containsKey(pos.asLong());
    }

    // ==================== 视图 ====================

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = changed.get(pos.asLong());
        return state != null ? state : base.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return changed.containsKey(pos.asLong()) ? null : base.getBlockEntity(pos);
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
}
