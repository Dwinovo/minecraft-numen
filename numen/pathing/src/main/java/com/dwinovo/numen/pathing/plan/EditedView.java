package com.dwinovo.numen.pathing.plan;

import java.util.List;

import com.dwinovo.numen.pathing.world.Recall;
import com.dwinovo.numen.pathing.world.Semantics;

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
 * 下一步的前提看到的就是身体此刻真正面对的世界。叠在另一份改动之上时,直接读它底下的视图、改过的格共用那份的表:
 * 前提函数每一步要读几百格,读一格只经几层、每层一次哈希查找。改动表一层一层叠(持久的):底下被叠过的一层冻住不再变,
 * 要改的视图在它上面另起一层只记自己的改动——一个节点展开的几十次前提判断,绝大多数一格也不改,改的也只有那么几格,
 * 都不必抄整张表。
 */
public class EditedView implements WorldView, Recall.Source {

    private final WorldView base;
    /** 改过的格,最上面一层;一件改动都没有时为 null(规划一步时大多数草稿一件也不改)。 */
    private Changes changed;

    EditedView(WorldView base) {
        if (base instanceof EditedView under) {
            this.base = under.base;
            this.changed = under.changed;
            if (changed != null) {
                changed.freeze();
            }
        } else {
            this.base = base;
        }
    }

    /** {@code base} 做完 {@code edits} 之后的样子;没有改动就是 {@code base} 本身。 */
    public static WorldView after(WorldView base, List<Edit> edits) {
        return edits.isEmpty() ? base : over(base, edits);
    }

    /** 同 {@link #after},总交出一份叠着改动的视图(改动可以是空的):要问"哪些格改过"({@link #changed(int, int, int, int)})的用它。 */
    public static EditedView over(WorldView base, List<Edit> edits) {
        EditedView view = new EditedView(base);
        view.changed = new Changes(view.changed, edits.size());
        for (Edit edit : edits) {
            switch (edit) {
                case Edit.Dig dig -> view.dig(dig.pos());
                case Edit.Place place -> view.place(place.pos(), place.block());
                case Edit.Door door -> view.toggle(door.pos(), door.state());
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

    /**
     * 开关一扇门:开关之前它是 {@code before},开关之后是它翻过来的样子;门的另一半照原版跟着同一开合。按开关之前的样子翻,
     * 不按这份视图此刻读到的翻:同一步的改动叠两遍(活世界里那一步已经做了,接着搜时又叠一遍)结果一样。
     */
    void toggle(BlockPos pos, BlockState before) {
        BlockState after = Semantics.toggled(before);
        set(pos, after);
        if (before.getBlock() instanceof DoorBlock) {
            BlockPos other = before.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState half = getBlockState(other);
            if (half.is(before.getBlock())) {
                set(other, half.setValue(DoorBlock.OPEN, after.getValue(DoorBlock.OPEN)));
            }
        }
    }

    final void set(BlockPos pos, BlockState state) {
        if (changed == null || changed.frozen()) {
            changed = new Changes(changed, 2);
        }
        changed.put(pos.asLong(), state);
    }

    /** 记事本是底下那份视图的:记的是没叠改动的快照,叠了改动的格子由 {@link #changed(int, int, int, int)} 告诉结论哪些不能用。 */
    @Override
    public Recall recall() {
        return base instanceof Recall.Source source ? source.recall() : null;
    }

    @Override
    public boolean changed(int x, int yMin, int yMax, int z) {
        return changed != null && changed.within(x, yMin, yMax, z);
    }

    /** 改过的每一格改成了什么,按格各报一次。 */
    public void forEachChanged(java.util.function.BiConsumer<BlockPos, BlockState> visitor) {
        if (changed != null) {
            changed.forEach(visitor);
        }
    }

    /** 这一格改过。 */
    boolean changed(BlockPos pos) {
        return changed != null && changed.get(pos.asLong()) != null;
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
            if (changed.within(section)) {
                return false;
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

    /**
     * 改过的格与改成了什么,一层。开放寻址的哈希表(槽里的方块状态为 null 是空槽):读一格要先问它,读得比改得多几个数量级,
     * 一个节点叠着最近几步的十几件改动,线性找一遍太贵。{@link #parent} 是底下那一层:被别的视图叠过的层冻住({@link #freeze}),
     * 不再写;要改的视图在它上面另起一层。
     */
    private static final class Changes {

        private static final long GOLDEN = 0x9E3779B97F4A7C15L;

        private final Changes parent;
        private boolean frozen;
        private long[] cells;
        private BlockState[] states;
        private int size;

        /** @param expected 预计要记几格;不够会长大 */
        Changes(Changes parent, int expected) {
            this.parent = parent;
            int capacity = 4;
            while (capacity < 2 * expected) {
                capacity <<= 1;
            }
            this.cells = new long[capacity];
            this.states = new BlockState[capacity];
        }

        void freeze() {
            frozen = true;
        }

        boolean frozen() {
            return frozen;
        }

        private static int slot(long cell, int mask) {
            return (int) ((cell * GOLDEN) >>> 32) & mask;
        }

        /** 改过的每一格改成了什么(同一格只报最上面一层的)。 */
        void forEach(java.util.function.BiConsumer<BlockPos, BlockState> visitor) {
            for (Changes layer = this; layer != null; layer = layer.parent) {
                for (int i = 0; i < layer.cells.length; i++) {
                    if (layer.states[i] != null && get(layer.cells[i]) == layer.states[i]) {
                        visitor.accept(BlockPos.of(layer.cells[i]), layer.states[i]);
                    }
                }
            }
        }

        /** 这一格改成了什么,自己这层没有就问底下;没改过为 null。 */
        BlockState get(long cell) {
            for (Changes layer = this; layer != null; layer = layer.parent) {
                if (layer.size == 0) {
                    continue;
                }
                int mask = layer.cells.length - 1;
                for (int i = slot(cell, mask); layer.states[i] != null; i = (i + 1) & mask) {
                    if (layer.cells[i] == cell) {
                        return layer.states[i];
                    }
                }
            }
            return null;
        }

        void put(long cell, BlockState state) {
            int mask = cells.length - 1;
            int i = slot(cell, mask);
            while (states[i] != null) {
                if (cells[i] == cell) {
                    states[i] = state;
                    return;
                }
                i = (i + 1) & mask;
            }
            if (2 * (size + 1) > cells.length) {
                grow();
                put(cell, state);
                return;
            }
            cells[i] = cell;
            states[i] = state;
            size++;
        }

        private void grow() {
            long[] oldCells = cells;
            BlockState[] oldStates = states;
            cells = new long[oldCells.length * 2];
            states = new BlockState[oldCells.length * 2];
            size = 0;
            for (int i = 0; i < oldCells.length; i++) {
                if (oldStates[i] != null) {
                    put(oldCells[i], oldStates[i]);
                }
            }
        }

        /** 改过的格里有没有在 {@code (x, z)} 这一列 {@code yMin} 到 {@code yMax} 之间的。 */
        boolean within(int x, int yMin, int yMax, int z) {
            for (Changes layer = this; layer != null; layer = layer.parent) {
                for (int i = 0; i < layer.cells.length; i++) {
                    if (layer.states[i] == null) {
                        continue;
                    }
                    long cell = layer.cells[i];
                    int y = BlockPos.getY(cell);
                    if (y >= yMin && y <= yMax && BlockPos.getX(cell) == x && BlockPos.getZ(cell) == z) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** 改过的格里有没有落在区段 {@code section}({@link SectionPos#asLong} 编码)里的。 */
        boolean within(long section) {
            for (Changes layer = this; layer != null; layer = layer.parent) {
                for (int i = 0; i < layer.cells.length; i++) {
                    if (layer.states[i] != null && SectionPos.blockToSection(layer.cells[i]) == section) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
