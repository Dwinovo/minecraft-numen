package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Typed descriptor for a bounded multi-block construction job. */
public final class BuildTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "build";

    public final List<Target> targets;
    /**
     * 目标格上已经有东西时怎么办(四档见 {@link ReplaceMode})。
     *
     * <p>工具层现在只发两种:让路的走 {@link ReplaceMode#REPLACE_EMPTY}(顶掉挡路的,
     * 并把图纸里的空气格当清空指令),不让路的走 {@code replaceExisting=false} 那条
     * <b>开工前置</b>——那不是这四档里的任何一档:它是整单拒绝,不是逐格跳过。
     * 中间两档已经实现并受测,等图纸层把档位开放给玩家时直接可用。
     */
    public final ReplaceMode replaceMode;
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
    /**
     * 方块实体数据,按目标格位置索引:箱子里的东西、告示牌的字、旗帜的花纹。
     *
     * <p>放在边表而不是 {@code Target} 里,因为它只有图纸才有,而且只有极少数格
     * 用得上——为它给每一格都加一个字段,是让百分之一的情形去改百分之百的构造点。
     *
     * <p>不做旋转:图纸转 90° 时方块的朝向会跟着转,但箱子里第 3 格的物品不该跟着
     * 挪位。带方向语义的方块实体数据(比如活塞头指向)本来就该由方块状态承载。
     */
    public final Map<Long, CompoundTag> blockEntityData;

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
        this(toolCallId, deadlineGameTime, targets,
                replaceExisting ? ReplaceMode.REPLACE_EMPTY : ReplaceMode.DONT_REPLACE,
                replaceExisting, consumeMaterials, allowPartial);
    }

    public BuildTaskRecord(String toolCallId, long deadlineGameTime, List<Target> targets,
                           ReplaceMode replaceMode, boolean replaceExisting,
                           boolean consumeMaterials, boolean allowPartial) {
        this(toolCallId, deadlineGameTime, targets, replaceMode, replaceExisting,
                consumeMaterials, allowPartial, Map.of());
    }

    public BuildTaskRecord(String toolCallId, long deadlineGameTime, List<Target> targets,
                           ReplaceMode replaceMode, boolean replaceExisting,
                           boolean consumeMaterials, boolean allowPartial,
                           Map<Long, CompoundTag> blockEntityData) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.targets = List.copyOf(targets);
        this.replaceMode = replaceMode;
        this.replaceExisting = replaceExisting;
        this.consumeMaterials = consumeMaterials;
        this.allowPartial = allowPartial;
        this.blockEntityData = Map.copyOf(blockEntityData);
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
            // 归一在这一处做完:每一个目标格无论从工具还是从图纸来,都必须过这道口,
            // 所以运行态(作物生长阶段、含水、活塞伸出、堆肥进度、锅里装的东西)
            // 在这里一次清干净,而不是让每条入口各清各的。
            desiredState = BuildStates.normalize(desiredState);
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

        /**
         * 这一格要花<b>几件</b>材料——盘点、报价与实扣的唯一真源。
         *
         * <p>清空格与液体格不费料。双格方块(门、床、高草、向日葵)在图纸里是上下
         * 两格、各带一个同样的物品,逐格计费就会一扇门吃掉两扇门的料,所以只让
         * "下半"计费,上半随之而生。
         *
         * <p>反过来也有一格要<b>多件</b>的:双层砖是两块半砖摞出来的,雪层、海龟蛋、
         * 海泡菜按层数/个数算。此前这里是个布尔量"要不要花一件",一格恒定一件——
         * 而屋面把双层砖当立面用,约三成屋面格子因此少报一件。清单与实扣不同源的
         * 后果和门那件事一模一样,只是方向相反:玩家按清单备齐了,照样建到一半停下。
         */
        public int materialCount() {
            if (desiredState == null || desiredState.isAir()) {
                return 0;
            }
            if (desiredState.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                return 0;
            }
            if (desiredState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && desiredState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) {
                return 0;
            }
            if (desiredState.hasProperty(BlockStateProperties.BED_PART)
                    && desiredState.getValue(BlockStateProperties.BED_PART)
                    == net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
                return 0;
            }
            if (desiredState.hasProperty(BlockStateProperties.SLAB_TYPE)
                    && desiredState.getValue(BlockStateProperties.SLAB_TYPE)
                    == net.minecraft.world.level.block.state.properties.SlabType.DOUBLE) {
                return 2;
            }
            if (desiredState.getBlock() instanceof net.minecraft.world.level.block.SnowLayerBlock) {
                return desiredState.getValue(BlockStateProperties.LAYERS);
            }
            if (desiredState.getBlock() instanceof net.minecraft.world.level.block.TurtleEggBlock) {
                return desiredState.getValue(BlockStateProperties.EGGS);
            }
            if (desiredState.getBlock() instanceof net.minecraft.world.level.block.SeaPickleBlock) {
                return desiredState.getValue(BlockStateProperties.PICKLES);
            }
            // 高草与大蕨类没有自己的方块物品,是两株矮的长成的(替代料见 BuildPalette)
            if (desiredState.is(net.minecraft.world.level.block.Blocks.TALL_GRASS)
                    || desiredState.is(net.minecraft.world.level.block.Blocks.LARGE_FERN)) {
                return 2;
            }
            return 1;
        }

        /** 要不要花料——{@link #materialCount()} 的派生问法,不另立判据。 */
        public boolean costsMaterial() {
            return materialCount() > 0;
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