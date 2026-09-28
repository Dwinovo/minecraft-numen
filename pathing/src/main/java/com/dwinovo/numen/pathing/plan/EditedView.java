package com.dwinovo.numen.pathing.plan;

import java.util.List;

import com.dwinovo.numen.pathing.world.Recall;
import com.dwinovo.numen.pathing.world.Semantics;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
 * 下一步的前提看到的就是身体此刻真正面对的世界。叠在另一份改动之上时,把那份的改动抄过来、直接读它底下的视图:
 * 前提函数每一步要读几百格,读一格只经一层。
 */
public class EditedView implements WorldView, Recall.Source {

    private final WorldView base;
    /** 改过的格;一件改动都没有时不建表(规划一步时大多数草稿一件也不改)。 */
    private Long2ObjectOpenHashMap<BlockState> changed;

    EditedView(WorldView base) {
        if (base instanceof EditedView under) {
            this.base = under.base;
            if (under.changed != null) {
                this.changed = new Long2ObjectOpenHashMap<>(under.changed);
            }
        } else {
            this.base = base;
        }
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
                // 倒下的水在这一步里就收回了
                case Edit.Catch caught -> {
                }
            }
        }
        return view;
    }

    // ==================== 改动 ====================

    /** 挖掉:只剩这一格原来含着的液体。 */
    void dig(BlockPos pos) {
        set(pos, getBlockState(pos).getFluidState().createLegacyBlock());
    }

    /** 放下一块 {@code block}。 */
    void place(BlockPos pos, Block block) {
        set(pos, block.defaultBlockState());
    }

    /** 倒一桶水:这一格成了水源。 */
    void pour(BlockPos pos) {
        set(pos, Blocks.WATER.defaultBlockState());
    }

    /** 开关一扇门;门的另一半照原版一起翻转。 */
    void toggle(BlockPos pos) {
        BlockState state = getBlockState(pos);
        set(pos, Semantics.toggled(state));
        if (state.getBlock() instanceof DoorBlock) {
            BlockPos other = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState half = getBlockState(other);
            if (half.is(state.getBlock())) {
                set(other, Semantics.toggled(half));
            }
        }
    }

    private void set(BlockPos pos, BlockState state) {
        if (changed == null) {
            changed = new Long2ObjectOpenHashMap<>(4);
        }
        changed.put(pos.asLong(), state);
    }

    /** 一件改动都没叠时,就是底下那份视图:照它交出记事本;叠了改动的世界不记。 */
    @Override
    public Recall recall() {
        return changed == null && base instanceof Recall.Source source ? source.recall() : null;
    }

    /** 这一格改过。 */
    boolean changed(BlockPos pos) {
        return changed != null && changed.containsKey(pos.asLong());
    }

    // ==================== 视图 ====================

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (changed == null) {
            return base.getBlockState(pos);
        }
        BlockState state = changed.get(pos.asLong());
        return state != null ? state : base.getBlockState(pos);
    }

    /** 这一段里改过哪一格,就不敢说整段是空气;没改过的照底下的视图答。 */
    @Override
    public boolean airSection(int x, int y, int z) {
        if (changed != null) {
            long section = SectionPos.asLong(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(y),
                    SectionPos.blockToSectionCoord(z));
            for (long cell : changed.keySet()) {
                if (SectionPos.blockToSection(cell) == section) {
                    return false;
                }
            }
        }
        return base.airSection(x, y, z);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return changed(pos) ? null : base.getBlockEntity(pos);
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
