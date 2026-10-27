package com.dwinovo.numen.task.build;
import com.dwinovo.numen.api.task.Activity;
import com.dwinovo.numen.build.BuildValidity;

import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.task.TaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一件多格施工的活:一串要成的格(世界坐标)交给执行器去砌。{@code numen.build.place} 派的就是它。
 */
public final class BuildTaskRecord extends TaskRecord<BuildCompanionTask.Placed> {

    public final List<Target> targets;
    /** 是否消耗背包材料:随能力画像而定(创造免耗材,生存逐格真扣)。 */
    public final boolean consumeMaterials;
    /** 按格找目标({@link #targetAt});第一次问时建。 */
    private Map<Long, Target> byCell;

    private int placed;
    private int setDirectly;
    private int replaced;
    private int broken;
    private int completed;

    /** 一次调用派的活:名字与调用 id 取自那次调用。 */
    public BuildTaskRecord(ServerCall call, long deadlineGameTime, List<Target> targets, boolean consumeMaterials) {
        this(call.fn(), call.callId(), deadlineGameTime, targets, consumeMaterials);
    }

    /**
     * @param name 这件活叫什么(任务记录、{@code task_finished} 里写的名字)
     */
    public BuildTaskRecord(String name, String toolCallId, long deadlineGameTime, List<Target> targets,
                           boolean consumeMaterials) {
        super(name, toolCallId, deadlineGameTime);
        this.targets = promotePlainCells(targets);
        this.consumeMaterials = consumeMaterials;
    }

    /** 这一格的目标;不在这件活里是 null。 */
    public Target targetAt(BlockPos pos) {
        if (byCell == null) {
            Map<Long, Target> index = new java.util.HashMap<>(targets.size() * 2);
            targets.forEach(t -> index.put(t.pos().asLong(), t));
            byCell = index;
        }
        return byCell.get(pos.asLong());
    }

    /**
     * 素面格升格:方块没有任何 blockstate 属性时,物品放下去就是要的,没有哪个状态位要点名。升格改的是 Target 的
     * itemPlace 字段,对账由此宽容(按方块的自述名):手放时物品放置钩子能把原版设施原地换成自家实现(Visual Workbench
     * 一类),注册名变了、名字没变。有属性的方块不升格(哪怕目标恰是默认态,如朝北的熔炉):对账照状态位来。
     */
    private static List<Target> promotePlainCells(List<Target> targets) {
        List<Target> out = new java.util.ArrayList<>(targets.size());
        for (Target t : targets) {
            boolean plain = !t.itemPlace()
                    && !t.desiredState().isAir()
                    && t.desiredState().getProperties().isEmpty()
                    // 物品放出来的必须就是要的方块:盆栽(potted_*)无属性但 item 是花盆,物品只给空盆
                    && t.item() instanceof net.minecraft.world.item.BlockItem bi
                    && bi.getBlock() == t.desiredState().getBlock();
            out.add(plain ? t.asItemPlace() : t);
        }
        return List.copyOf(out);
    }

    public int placed() {
        return placed;
    }

    /** 放下的格里,手放不出那个样子、直接写进去的有几格。 */
    public int setDirectly() {
        return setDirectly;
    }

    /** 放下一格;{@code replacing} 是先拆掉了挡着的东西再放,{@code directly} 是手放不出那个样子、直接写进去的。 */
    public void placedOne(boolean replacing, boolean directly) {
        placed++;
        if (directly) {
            setDirectly++;
        }
        if (replacing) {
            replaced++;
        }
    }

    /** 放下的格里,先拆掉挡着的东西才放的有几格。 */
    public int replaced() {
        return replaced;
    }

    public int broken() {
        return broken;
    }

    /** 拆掉一格。 */
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
     * 一行人话 —— 这是<b>给模型看的</b>:{@code <current_task>} 与 task status 印的是它,主人看的是 {@link #activity()}。只报进度,不报状况:
     * 进度是"还剩多少"——单调、有分母、幂等;状况是"出了什么事"(有人在拆、材料见底),该走事件队列推给她。
     */
    @Override
    public String describe() {
        return "搭建 " + completed + "/" + targets.size();
    }

    /** 给主人看的一句(头顶气泡、档案页),客户端按当前语言翻;{@link #describe()} 是给模型的。 */
    @Override
    public Activity activity() {
        return Activity.of("numen.activity.build", completed, targets.size());
    }

    /**
     * @param itemPlace 素面格:物品放下去就是要的,对账按方块的自述名宽容(见 {@link #matches})。
     *                  放法不看它:每一格都由 {@link BuildPlacer} 手放或直接写。
     * @param mask      这一格自己的让路档位;没写是 null,按 {@link ReplaceMode#REPLACE_EMPTY}(见 {@link #mode})
     * @param heldWater 要的方块本来含着水(waterlogged),归一时被去掉了;方块照放,回执的 {@code liquid} 一类如实说去掉了几格的水
     */
    public record Target(BlockState desiredState, Item item, BlockPos pos, String label,
                         boolean itemPlace, ReplaceMode mask, boolean heldWater) {

        public Target(BlockState desiredState, Item item, BlockPos pos, String label) {
            this(desiredState, item, pos, label, false, null, false);
        }

        public Target(Block block, Item item, BlockPos pos, String label) {
            this(block.defaultBlockState(), item, pos, label, false, null, false);
        }

        /** 让路档位:自己写了的那一档,没写是"连空也清"——原语不写 {@code --mask} 就是 {@code carve}。 */
        public ReplaceMode mode() {
            return mask != null ? mask : ReplaceMode.REPLACE_EMPTY;
        }

        /** 同一格摆到别处:位置与方块换成摆好之后的,别的(记账物品、车道、档位)照旧。 */
        public Target placed(BlockPos at, BlockState state) {
            return new Target(state, item, at, label, itemPlace, mask, heldWater);
        }

        /**
         * 升格成素面格。清空格、液体、没有物品形态的方块没有"物品放下去就是它",原样返回。
         */
        public Target asItemPlace() {
            if (desiredState.isAir()
                    || liquid()
                    || !(item instanceof net.minecraft.world.item.BlockItem)) {
                return this;
            }
            return new Target(desiredState, item, pos, label, true, mask, heldWater);
        }

        public Target {
            desiredState = Objects.requireNonNull(desiredState, "desiredState");
            heldWater |= desiredState.hasProperty(BlockStateProperties.WATERLOGGED)
                    && desiredState.getValue(BlockStateProperties.WATERLOGGED);
            // 归一在这一处做完:每一个目标格无论从原语还是从图纸来,都必须过这道口,
            // 所以运行态(作物生长阶段、含水、活塞伸出、堆肥进度、锅里装的东西)
            // 在这里一次清干净,而不是让每条入口各清各的。
            desiredState = com.dwinovo.numen.build.BuildStates.normalize(desiredState);
            item = Objects.requireNonNull(item, "item");
            pos = Objects.requireNonNull(pos, "pos").immutable();
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
            if (desiredState == null || desiredState.isAir() || noItem() || liquid()) {
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
            // 一格四根蜡烛就是四根:少收三根等于白送三根
            if (desiredState.hasProperty(BlockStateProperties.CANDLES)) {
                return desiredState.getValue(BlockStateProperties.CANDLES);
            }
            // 藤蔓与发光地衣按贴了几个面算:一格贴三面是三份料
            if (desiredState.is(net.minecraft.world.level.block.Blocks.VINE)
                    || desiredState.is(net.minecraft.world.level.block.Blocks.GLOW_LICHEN)) {
                int faces = 0;
                for (var side : new net.minecraft.world.level.block.state.properties.BooleanProperty[]{
                        BlockStateProperties.NORTH, BlockStateProperties.EAST,
                        BlockStateProperties.SOUTH, BlockStateProperties.WEST,
                        BlockStateProperties.UP, BlockStateProperties.DOWN}) {
                    if (desiredState.hasProperty(side) && desiredState.getValue(side)) {
                        faces++;
                    }
                }
                return Math.max(1, faces);
            }
            // 高草与大蕨类一格一件:tall_grass / large_fern 自己就是物品,方块自述
            // 给的正是它。按"两株矮的"算两件会让玩家照清单备双份,多出来的那一半
            // 永远用不掉。
            return 1;
        }

        /**
         * 没有哪件物品能直接放出这个方块(盆栽、海带的茎……):它进目标集,轮到时不放,回执归在 {@code no_item} 下
         * ({@link com.dwinovo.numen.build.BuildStates#noItemReason})。判据只此一处,{@code BuildCellRules} 与 {@link #materialCount} 都问它。
         */
        public boolean noItem() {
            return item == net.minecraft.world.item.Items.AIR && !desiredState.isAir() && !liquid();
        }

        /** 要的是液体(水、岩浆):她不放,单列 {@code liquid} 一类;和"没有物品"不是一回事。 */
        public boolean liquid() {
            return desiredState.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock;
        }

        /** 要不要花料——{@link #materialCount()} 的派生问法,不另立判据。 */
        public boolean costsMaterial() {
            return materialCount() > 0;
        }

        public boolean matches(BlockState state) {
            if (itemPlace) {
                // 素面格的对账不看状态位:没提朝向的格子,朝向就不是工程量。方块种类按"自述名"对,
                // 不只按注册名:有模组在放置时把原版设施原地换成自家实现(注册名变了,名字没变),
                // 按注册名对账会判不符,拆了重放,和模组拉锯到天荒地老。
                return !state.isAir() && (state.getBlock() == desiredState.getBlock()
                        || state.getBlock().getDescriptionId()
                                .equals(desiredState.getBlock().getDescriptionId()));
            }
            return BuildValidity.valid(state, desiredState);
        }
    }

}
