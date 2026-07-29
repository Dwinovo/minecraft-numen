package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.List;
import java.util.Objects;

/** Typed descriptor for a bounded multi-block construction job. */
public final class BuildTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "build";

    public final List<Target> targets;
    public final boolean replaceExisting;
    /** 是否消耗背包材料:随能力画像而定(创造免耗材,生存逐格真扣)。 */
    public final boolean consumeMaterials;
    /**
     * 料不齐时允许分段施工:<b>能建多少建多少</b>,收工报还差什么。
     *
     * <p>按调用入口分,不一刀切。小活(手写格集)背包装得下,整批拒绝的原子性
     * 更值钱——半成品比没开工糟。整幢图纸装不下:满背包 36 格顶天两千来块,而
     * 一栋房子上百种方块、几千格,<b>一趟本来就运不完</b>,拒绝等于永远开不了工。
     *
     * <p>分段之所以不留废墟,是因为续建是精确的:每一遍的待建集都从"图纸与世界
     * 当下的差集"重算,已经建对的格自动跳过。补齐材料后原样再发一次同一个调用,
     * 就从断点接上——不需要记住计划,因为世界本身就是计划的进度。
     */
    public final boolean allowPartial;

    private int placed;
    private int broken;
    private int completed;

    // 注:曾有 layerHeight(分层施工的层高门)。施工模型改为"低层优先的确定
    // 顺序 + 分遍补漏"之后,层高不再有任何裁决作用,留着就是个调了不起作用
    // 的旋钮——比缺一个功能更糟,故一并撤除。

    public BuildTaskRecord(String toolCallId, long deadlineGameTime,
                           List<Target> targets, boolean replaceExisting) {
        this(toolCallId, deadlineGameTime, targets, replaceExisting, true, false);
    }

    public BuildTaskRecord(String toolCallId, long deadlineGameTime, List<Target> targets,
                           boolean replaceExisting, boolean consumeMaterials) {
        this(toolCallId, deadlineGameTime, targets, replaceExisting, consumeMaterials, false);
    }

    public BuildTaskRecord(String toolCallId, long deadlineGameTime, List<Target> targets,
                           boolean replaceExisting, boolean consumeMaterials, boolean allowPartial) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.targets = List.copyOf(targets);
        this.replaceExisting = replaceExisting;
        this.consumeMaterials = consumeMaterials;
        this.allowPartial = allowPartial;
    }

    public int placed() {
        return placed;
    }

    public void placedOne() {
        placed++;
    }

    public int broken() {
        return broken;
    }

    public void brokeOne() {
        broken++;
    }

    public int completed() {
        return completed;
    }

    public void completed(int completed) {
        this.completed = completed;
    }

    /**
     * {@code task_status} 的进度面:<b>只报进度,不报状况</b>。
     *
     * <p>进度是"还剩多少"——单调、有分母、幂等,拉多少次都是同一个答案。状况是
     * "出了什么事"(有人在拆、材料见底)——离散、有时效、错过就没了,该走事件
     * 队列推给她,不该等人来问。两者混在一格里,进度会变得不可预测,状况会丢掉
     * 时序,而且只有轮询才拿得到——偏偏状况最不该等人问。
     */
    @Override
    public String describe() {
        return TOOL_NAME + " " + completed + "/" + targets.size();
    }

    public record Target(BlockState desiredState, Item item, BlockPos pos, String label,
                         Direction facing, Direction.Axis axis, Boolean topHalf) {
        public Target(Block block, Item item, BlockPos pos, String label,
                      Direction facing, Direction.Axis axis, Boolean topHalf) {
            this(applyHints(block.defaultBlockState(), facing, axis, topHalf),
                    item, pos, label, facing, axis, topHalf);
        }

        public Target {
            desiredState = Objects.requireNonNull(desiredState, "desiredState");
            // 建出来的树叶就是"手放树叶":persistent 归一为 true,否则自然树的
            // 蓝图照放会当场腐烂,放一片烂一片永远建不完
            if (desiredState.hasProperty(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT)) {
                desiredState = desiredState.setValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT,
                        true);
            }
            item = Objects.requireNonNull(item, "item");
            pos = Objects.requireNonNull(pos, "pos").immutable();
            if (facing != null && facingOf(desiredState) == null) {
                throw new IllegalArgumentException(desiredState.getBlock().getName().getString()
                        + " does not support facing");
            }
            if (axis != null && axisOf(desiredState) == null) {
                throw new IllegalArgumentException(desiredState.getBlock().getName().getString()
                        + " does not support axis");
            }
            if (topHalf != null && topHalfOf(desiredState) == null) {
                throw new IllegalArgumentException(desiredState.getBlock().getName().getString()
                        + " does not support top/bottom half");
            }
            label = label == null || label.isBlank()
                    ? desiredState.getBlock().getName().getString()
                    : label;
        }

        public Block block() {
            return desiredState.getBlock();
        }

        public boolean matches(BlockState state) {
            return BuildValidity.valid(state, desiredState, false);
        }

        public boolean acceptsPlacedState(BlockState state) {
            return BuildValidity.valid(state, desiredState, true);
        }

        public String shortPos() {
            return pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }
    }

    private static BlockState applyHints(BlockState state, Direction facing,
                                         Direction.Axis axis, Boolean topHalf) {
        if (facing != null) {
            if (state.hasProperty(BlockStateProperties.FACING)) {
                state = state.setValue(BlockStateProperties.FACING, facing);
            } else if (facing.getAxis().isHorizontal()
                    && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            }
        }
        if (axis != null) {
            if (state.hasProperty(BlockStateProperties.AXIS)) {
                state = state.setValue(BlockStateProperties.AXIS, axis);
            } else if (axis.isHorizontal() && state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
                state = state.setValue(BlockStateProperties.HORIZONTAL_AXIS, axis);
            }
        }
        if (topHalf != null) {
            if (state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
                state = state.setValue(BlockStateProperties.SLAB_TYPE,
                        topHalf ? SlabType.TOP : SlabType.BOTTOM);
            }
            if (state.hasProperty(BlockStateProperties.HALF)) {
                state = state.setValue(BlockStateProperties.HALF,
                        topHalf ? Half.TOP : Half.BOTTOM);
            }
        }
        return state;
    }

    private static Direction facingOf(BlockState s) {
        if (s.hasProperty(BlockStateProperties.FACING)) {
            return s.getValue(BlockStateProperties.FACING);
        }
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return s.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        return null;
    }

    private static Direction.Axis axisOf(BlockState s) {
        if (s.hasProperty(BlockStateProperties.AXIS)) {
            return s.getValue(BlockStateProperties.AXIS);
        }
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
            return s.getValue(BlockStateProperties.HORIZONTAL_AXIS);
        }
        return null;
    }

    private static Boolean topHalfOf(BlockState s) {
        if (s.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            SlabType t = s.getValue(BlockStateProperties.SLAB_TYPE);
            return t == SlabType.DOUBLE ? null : t == SlabType.TOP;
        }
        if (s.hasProperty(BlockStateProperties.HALF)) {
            return s.getValue(BlockStateProperties.HALF) == Half.TOP;
        }
        return null;
    }
}