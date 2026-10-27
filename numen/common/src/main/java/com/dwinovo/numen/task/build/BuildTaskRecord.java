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
    private int replaced;
    private int broken;
    private int completed;

    // 注:曾有 layerHeight(分层施工的层高门)。施工模型改为"低层优先的确定
    // 顺序 + 分遍补漏"之后,层高不再有任何裁决作用,留着就是个调了不起作用
    // 的旋钮——比缺一个功能更糟,故一并撤除。

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
     * 素面格升格走原生放置:方块没有任何 blockstate 属性时,
     * "照图直写"与"玩家动作"的产物语义完全等价,唯一区别是后者跑物品放置钩子——
     * 领地可拦、Visual Workbench 一类原地换方块的模组照常接管。升格改的是 Target 的
     * itemPlace 字段:车道选择、宽容对账(按自述名)、按手扣料三处读的都是它,单一真源。
     * 有属性的方块不升格(哪怕目标恰是默认态,如朝北的熔炉):原生放置按视线推导
     * 状态,给不出图纸点名的精确值。
     */
    private static List<Target> promotePlainCells(List<Target> targets) {
        List<Target> out = new java.util.ArrayList<>(targets.size());
        for (Target t : targets) {
            boolean plain = !t.itemPlace()
                    && !t.desiredState().isAir()
                    && t.desiredState().getProperties().isEmpty()
                    // 物品放出来的必须就是要的方块:盆栽(potted_*)无属性但 item 是花盆,原生放置只给空盆
                    && t.item() instanceof net.minecraft.world.item.BlockItem bi
                    && bi.getBlock() == t.desiredState().getBlock();
            out.add(plain ? t.asItemPlace() : t);
        }
        return List.copyOf(out);
    }

    /**
     * 建完让世界落定之后,与图纸不同的格数——站不住掉了的,和形状按真实邻居重算了的。
     * 允许不同,但不许无声不同:这个数进回执。
     */
    private int settledAway;

    public int settledAway() {
        return settledAway;
    }

    public void settledAway(int count) {
        this.settledAway = count;
    }

    /** 这些目标里有没有哪一格的档位会顶掉挡路的东西——建造寻路问的是这一句(拆一块钻出去许不许)。 */
    public boolean mayReplace() {
        return targets.stream().anyMatch(t -> t.mode().mayReplace());
    }

    public int placed() {
        return placed;
    }

    /** 放下一格;{@code replacing} 是先拆掉了挡着的东西再放。 */
    public void placedOne(boolean replacing) {
        placed++;
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
     * @param itemPlace 原生车道:这一格由<b>物品自己</b>像真右键那样落位,而不是照图直写。
     *                  只给 {@code build place}——那是"放一个工作台"这类玩家动作,
     *                  朝向随她的视线,模组钩在物品放置上的转换照常发生。
     * @param mask      这一格自己的让路档位;没写是 null,按 {@link ReplaceMode#REPLACE_EMPTY}(见 {@link #mode})
     */
    public record Target(BlockState desiredState, Item item, BlockPos pos, String label,
                         boolean itemPlace, ReplaceMode mask) {

        public Target(BlockState desiredState, Item item, BlockPos pos, String label) {
            this(desiredState, item, pos, label, false, null);
        }

        public Target(Block block, Item item, BlockPos pos, String label) {
            this(block.defaultBlockState(), item, pos, label, false, null);
        }

        /** 这一格自己的让路档位。 */
        public Target withMask(ReplaceMode mask) {
            return new Target(desiredState, item, pos, label, itemPlace, mask);
        }

        /** 让路档位:自己写了的那一档,没写是"连空也清"——原语不写 {@code --mask} 就是 {@code carve}。 */
        public ReplaceMode mode() {
            return mask != null ? mask : ReplaceMode.REPLACE_EMPTY;
        }

        /** 同一格摆到别处:位置与方块换成摆好之后的,别的(记账物品、车道、档位)照旧。 */
        public Target placed(BlockPos at, BlockState state) {
            return new Target(state, item, at, label, itemPlace, mask);
        }

        /**
         * 进原生车道。放不出来的东西——清空格、液体、没有物品形态的方块——进不了,
         * 原样返回:车道的前提是"手里有一件能放的东西"。
         */
        public Target asItemPlace() {
            if (desiredState.isAir()
                    || desiredState.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
                    || !(item instanceof net.minecraft.world.item.BlockItem)) {
                return this;
            }
            return new Target(desiredState, item, pos, label, true, mask);
        }

        public Target {
            desiredState = Objects.requireNonNull(desiredState, "desiredState");
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

        /** 要不要花料——{@link #materialCount()} 的派生问法,不另立判据。 */
        public boolean costsMaterial() {
            return materialCount() > 0;
        }

        public boolean matches(BlockState state) {
            if (itemPlace) {
                // 原生格的对账不看状态位:没提朝向的格子,朝向就不是工程量——游戏按
                // 玩家规则给什么就是什么。方块种类按"自述名"对,不只按注册名:有模组
                // 在放置时把原版设施原地换成自家实现(注册名变了,名字没变),按注册名
                // 对账会判不符,拆了重放,和模组拉锯到天荒地老。
                return !state.isAir() && (state.getBlock() == desiredState.getBlock()
                        || state.getBlock().getDescriptionId()
                                .equals(desiredState.getBlock().getDescriptionId()));
            }
            return BuildValidity.valid(state, desiredState, false);
        }

        public boolean acceptsPlacedState(BlockState state) {
            return BuildValidity.valid(state, desiredState, true);
        }

        public String shortPos() {
            return pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }
    }

}