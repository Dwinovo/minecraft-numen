package com.dwinovo.numen.core.task.build;
import com.dwinovo.numen.core.build.BuildValidity;
import com.dwinovo.numen.core.build.Built;
import com.dwinovo.numen.core.build.Layout;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一件多格施工的活:一份摆到世界里的施工图({@link Layout})交给执行器去砌。原语当场执行、按设计或蓝图文件
 * {@code build at},派的都是它。
 */
public final class BuildTaskRecord extends TaskRecord {

    public final List<Target> targets;
    /** 是否消耗背包材料:随能力画像而定(创造免耗材,生存逐格真扣)。 */
    public final boolean consumeMaterials;
    /**
     * 料不齐时允许分段施工:<b>能建多少建多少</b>,收工报还差什么。
     *
     * <p>按施工图的来源分,不一刀切。原语与设计是她自己写的一栋,整份一次预检、缺料一格不放更值钱——半成品比没开工糟。
     * 蓝图文件装不下:满背包 36 格顶天两千来块,而一栋房子上百种方块、几千格,<b>一趟本来就运不完</b>,拒绝等于永远开不了工。
     *
     * <p>分段之所以不留废墟,是因为续建是精确的:每一遍的待建集都从"图纸与世界当下的差集"重算,已经建对的格自动跳过。
     * 补齐材料后原样再发一次同一行 {@code build at},就从断点接上——不需要记住计划,因为世界本身就是计划的进度。
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
    /**
     * 待生成的摆设实体:展示框、盔甲架、画。
     *
     * <p>它们不是方块,进不了 {@code targets},但拆了这栋房子就不完整——钉在墙上的
     * 画、立在院里的盔甲架都是设计的一部分。整栋盖完之后一次生成:实体要挂在墙上,
     * 墙得先有。
     */
    public final List<EntitySpawn> entities;
    /**
     * 按位置索引的<b>逐格料单</b>:这一格不是"一件某物",而是这几叠。
     *
     * <p>一格一件是特例而不是通则,这一点容易想反。带花的花盆是<b>花盆加那株花两件
     * 东西</b>——正因如此它没有自己的物品,而"按方块的物品收一件"这条路在这里没有答案。
     * 通行的做法之一是就此整格丢掉(建出来院子里少二十一个花盆);另一条是老老实实收
     * 两件。后者才对。旗帜是同一条路的另一头:一叠,但要求组件一致。
     *
     * <p>这张表<b>整个盖过</b>默认的"{@code item() × materialCount()}"。放在边表而不是
     * {@code Target} 里,理由和方块实体数据一样:只有极少数格用得上,为它给每一个构造点
     * 加一个字段是让百分之一的情形去改百分之百的代码。
     */
    public final Map<Long, List<CellNeed>> cellNeeds;
    /**
     * 摆图时就落不了地、根本没进目标集的格数(流体、活塞头、推不出物品的方块)。
     *
     * <p>要单独记一笔并交代出去,理由和"跳过的格从分母去掉"是同一条:一张一千格的
     * 图纸掉了二百格,若这二百格连目标集都没进,任务会理直气壮地报"八百格全部达标",
     * 而设计缺了五分之一,没有一个字提到过。摆图时的掉格也是掉格。
     */
    public final int droppedAtLoad;
    /**
     * 这件活盖的是哪一栋({@code build at} 派的);当场执行的原语不是一栋房子,是 null。放下与拆掉的每一格随手记进
     * 这一栋的记录({@link Built}),活怎么收场、服务器停不停,记下的都是世界里真发生的。
     */
    public final Built.Site site;

    private int placed;
    private int replaced;
    private int broken;
    private int removed;
    private int completed;

    // 注:曾有 layerHeight(分层施工的层高门)。施工模型改为"低层优先的确定
    // 顺序 + 分遍补漏"之后,层高不再有任何裁决作用,留着就是个调了不起作用
    // 的旋钮——比缺一个功能更糟,故一并撤除。

    /** 命令派的活:名字与调用 id 取自那次调用({@code build at}、当场执行的原语)。 */
    public BuildTaskRecord(ServerSource source, long deadlineGameTime, Layout layout, boolean consumeMaterials,
                           boolean allowPartial, Built.Site site) {
        this(source.taskName(), source.toolCallId(), deadlineGameTime, layout, consumeMaterials, allowPartial, site);
    }

    /**
     * @param name 这件活叫什么(任务记录、{@code task_finished} 里写的名字)
     */
    public BuildTaskRecord(String name, String toolCallId, long deadlineGameTime, Layout layout,
                           boolean consumeMaterials, boolean allowPartial, Built.Site site) {
        super(name, toolCallId, deadlineGameTime);
        this.entities = layout.entities();
        this.targets = promotePlainCells(layout.targets(), layout.blockEntityData());
        this.consumeMaterials = consumeMaterials;
        this.allowPartial = allowPartial;
        this.blockEntityData = layout.blockEntityData();
        this.cellNeeds = layout.cellNeeds();
        this.droppedAtLoad = layout.dropped();
        this.site = site;
    }

    /**
     * 素面格升格走原生放置:方块没有任何 blockstate 属性、这一格也不带方块实体数据时,
     * "照图直写"与"玩家动作"的产物语义完全等价,唯一区别是后者跑物品放置钩子——
     * 领地可拦、Visual Workbench 一类原地换方块的模组照常接管。升格改的是 Target 的
     * itemPlace 字段:车道选择、宽容对账(按自述名)、按手扣料三处读的都是它,单一真源。
     * 有属性的方块不升格(哪怕目标恰是默认态,如朝北的熔炉):原生放置按视线推导
     * 状态,给不出图纸点名的精确值。
     */
    private static List<Target> promotePlainCells(List<Target> targets,
                                                  Map<Long, CompoundTag> blockEntityData) {
        List<Target> out = new java.util.ArrayList<>(targets.size());
        for (Target t : targets) {
            boolean plain = !t.itemPlace()
                    && !t.desiredState().isAir()
                    && t.desiredState().getProperties().isEmpty()
                    && !blockEntityData.containsKey(t.pos().asLong())
                    // 物品放出来的必须就是图纸要的方块:盆栽(potted_*)无属性但 item 是花盆,
                    // 原生放置只给空盆,盆+花两件的料单格必须留在直写道。
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

    /**
     * 一格要的一叠料。
     *
     * <p>两种口径合在一条路上,因为它们回答的是同一个问题——"这一格该收什么":
     * <ul>
     *   <li>{@code exact = false}:按物品类型收。哪一块橡木板都一样。</li>
     *   <li>{@code exact = true}:组件也要一致。带花纹的旗帜、框里那把附魔剑,
     *       少比一个组件就等于把手工活白送。</li>
     * </ul>
     */
    public record CellNeed(net.minecraft.world.item.ItemStack stack, boolean exact) {
        public boolean matches(net.minecraft.world.item.ItemStack other) {
            return exact
                    ? net.minecraft.world.item.ItemStack.isSameItemSameComponents(stack, other)
                    : other.is(stack.getItem());
        }
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

    /** 拆掉一格;{@code removal} 是拆掉这一栋从前由她放下、设计里已经没有的那一格。 */
    public void brokeOne(boolean removal) {
        broken++;
        if (removal) {
            removed++;
        }
    }

    /** 拆掉的格里,拆的是这一栋从前由她放下、设计里已经没有的有几格。 */
    public int removed() {
        return removed;
    }

    public int completed() {
        return completed;
    }

    public void completed(int completed) {
        this.completed = completed;
    }

    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、{@code task status} 印的都是它。只报进度,不报状况:
     * 进度是"还剩多少"——单调、有分母、幂等;状况是"出了什么事"(有人在拆、材料见底),该走事件队列推给她。
     */
    @Override
    public String describe() {
        return "搭建 " + completed + "/" + targets.size();
    }

    /** 一只待生成的摆设实体:落位点、图纸旋转、剥干净的 NBT。 */
    public record EntitySpawn(double x, double y, double z,
                              net.minecraft.world.level.block.Rotation rotation,
                              CompoundTag nbt) {

        /**
         * 这只摆设要花哪件材料。
         *
         * <p>摆设不能免费:一张带五十个盔甲架的图纸凭空给五十个盔甲架,和"框里的剑
         * 不给"是自相矛盾的——框白送而框里的东西要玩家自己放,说不通。白名单只有
         * 四种,所以这里是个封闭的对照表,不必去猜。
         *
         * @return 对应物品;不在白名单里返回空气(不该出现)
         */
        public Item item() {
            return switch (nbt.getString("id")) {
                case "minecraft:item_frame" -> net.minecraft.world.item.Items.ITEM_FRAME;
                case "minecraft:glow_item_frame" -> net.minecraft.world.item.Items.GLOW_ITEM_FRAME;
                case "minecraft:armor_stand" -> net.minecraft.world.item.Items.ARMOR_STAND;
                case "minecraft:painting" -> net.minecraft.world.item.Items.PAINTING;
                default -> net.minecraft.world.item.Items.AIR;
            };
        }

        /**
         * 这只摆设身上带的东西要收哪几叠——每一叠按<b>组件全等</b>收。
         *
         * <p>躯壳一件料,身上的东西另算:框白送而框里的剑也白送,那就是凭空造物品;
         * 框收料而框里的剑不给,玩家又会觉得图纸没还原。收什么放什么,账才是平的。
         */
        public java.util.List<net.minecraft.world.item.ItemStack> payload(
                net.minecraft.core.HolderLookup.Provider registries) {
            return com.dwinovo.numen.core.build.BlueprintSafety.payloadStacks(nbt, registries);
        }
    }

    /**
     * @param itemPlace 原生车道:这一格由<b>物品自己</b>像真右键那样落位,而不是照图直写。
     *                  只给 {@code build place}——那是"放一个工作台"这类玩家动作,
     *                  朝向随她的视线,模组钩在物品放置上的转换照常发生。
     * @param mask      这一格自己的让路档位;没写是 null,按 {@link ReplaceMode#REPLACE_EMPTY}(见 {@link #mode})
     * @param removes   拆除格:这一栋从前由她在这里放下的那种方块。只有世界里这一格现在仍是它才拆,别人换过的不碰;
     *                  不是拆除格是 null
     */
    public record Target(BlockState desiredState, Item item, BlockPos pos, String label,
                         boolean itemPlace, ReplaceMode mask, Block removes) {

        public Target(BlockState desiredState, Item item, BlockPos pos, String label) {
            this(desiredState, item, pos, label, false, null, null);
        }

        public Target(Block block, Item item, BlockPos pos, String label) {
            this(block.defaultBlockState(), item, pos, label, false, null, null);
        }

        /** 拆掉这一栋从前由她放下的 {@code block}:目标是空气,清场的那一档,只在它还是 {@code block} 时动手。 */
        public static Target removal(BlockPos pos, Block block) {
            return new Target(Blocks.AIR.defaultBlockState(), Items.AIR, pos, "air", false,
                    ReplaceMode.REPLACE_EMPTY, block);
        }

        /** 这一格自己的让路档位。 */
        public Target withMask(ReplaceMode mask) {
            return new Target(desiredState, item, pos, label, itemPlace, mask, removes);
        }

        /** 让路档位:自己写了的那一档,没写是"连空也清"——原语不写 {@code --mask} 就是 {@code carve}。 */
        public ReplaceMode mode() {
            return mask != null ? mask : ReplaceMode.REPLACE_EMPTY;
        }

        /** 同一格摆到别处:位置与方块换成摆好之后的,别的(记账物品、车道、档位)照旧。 */
        public Target placed(BlockPos at, BlockState state) {
            return new Target(state, item, at, label, itemPlace, mask, removes);
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
            return new Target(desiredState, item, pos, label, true, mask, removes);
        }

        public Target {
            desiredState = Objects.requireNonNull(desiredState, "desiredState");
            // 归一在这一处做完:每一个目标格无论从原语还是从图纸来,都必须过这道口,
            // 所以运行态(作物生长阶段、含水、活塞伸出、堆肥进度、锅里装的东西)
            // 在这里一次清干净,而不是让每条入口各清各的。
            desiredState = com.dwinovo.numen.core.build.BuildStates.normalize(desiredState);
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