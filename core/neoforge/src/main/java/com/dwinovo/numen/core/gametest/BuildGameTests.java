package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 建造与蓝图:{@code build} 的形状与材料账、蓝图读写与方块实体、屋顶技法、整栋房子。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class BuildGameTests {

    /** 小屋独立批次前置(薄墙环几何对并发搜索池最敏感,单独跑)。 */
    @BeforeBatch(batch = "numen_build_cottage")
    public static void prepareCottageBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 建造批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_build")
    public static void prepareBuildBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 建造用例的公共骨架:floor20 平地、rel(2,2,2) 出生、按需发圆石,派 build
     * 任务(不传分层——走生产默认的自动分层),判据两条:每格就位 + 同伴回到
     * 地面(建完人还挂在结构上不算交付)。
     */
    private static void runBuildCase(GameTestHelper helper, String name,
                                     List<BlockPos> relCells, int cobbleStacks) {
        ServerLevel level = helper.getLevel();
        BlockPos spawn = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                name, UUID.randomUUID(), level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        for (int i = 0; i < cobbleStacks; i++) {
            companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        }
        List<BuildTaskRecord.Target> targets = new ArrayList<>(relCells.size());
        for (BlockPos rel : relCells) {
            targets.add(new BuildTaskRecord.Target(Blocks.COBBLESTONE, Items.COBBLESTONE,
                    helper.absolutePos(rel), "cobblestone"));
        }
        var ctx = TaskDispatch.ctx("gametest-build", companion);
        long deadline = ctx.deadline(Math.max(1200L, targets.size() * 400L));
        TaskDispatch.setTask(companion,
                new BuildTaskRecord(ctx.toolCallId(), deadline, targets, com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_EMPTY), null, reply -> {});

        List<BlockPos> cells = targets.stream().map(BuildTaskRecord.Target::pos).toList();
        helper.succeedWhen(() -> {
            for (BlockPos cell : cells) {
                helper.assertTrue(level.getBlockState(cell).is(Blocks.COBBLESTONE),
                        "structure incomplete at " + cell.toShortString());
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 形状 DSL:空心圆柱(半径 3、高 4 的塔筒)。几何由 build_shape 的展开器
     *  生成,走常规建造任务(消耗材料),验"搭积木"路线的地基。 */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_build")
    public static void build_shape_cylinder(GameTestHelper helper) {
        BlockPos center = new BlockPos(10, 2, 10);
        List<BlockPos> rel = com.dwinovo.numen.core.build.BuildShapes.shapeCells(
                "cylinder", true, center.getX(), center.getY(), center.getZ(),
                null, null, null, 3, 4);
        runBuildCase(helper, "gametest_mason2", rel, 2);
    }

    /**
     * 分段施工 + 精确续建:料只给一半,建到没料;补齐后<b>原样再发一次同一个调用</b>,
     * 从断点接上,最终逐格全中。
     *
     * <p>这是整幢图纸能不能盖的前提。满背包顶天两千来块,而一栋房子几千格、上百
     * 种方块——<b>一趟本来就运不完</b>,"料不齐就整批拒绝"等于大房子永远开不了工。
     *
     * <p>之所以分段不留废墟:待建集每一遍都从"图纸与世界当下的差集"重算,已经建
     * 对的格自动跳过。计划不需要存——<b>世界本身就是进度</b>。这条一旦坏掉,续建
     * 会变成在旧墙上叠新墙,所以必须有回归锁。
     */
    /**
     * 素面格落进高草下半格:原生右键道的邻居链会把上半截草打碎——
     * 直写时代留孤儿草悬在工作台头顶(#63)。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_build")
    public static void plain_cell_into_tall_grass_breaks_the_top(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lower = helper.absolutePos(new BlockPos(6, 2, 6));
        level.setBlock(lower, Blocks.TALL_GRASS.defaultBlockState(), 3);
        level.setBlock(lower.above(), Blocks.TALL_GRASS.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 3);
        NumenPlayer companion = spawnAt(helper, "gametest_mower", new BlockPos(2, 2, 2), false);
        companion.getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
        var ctx = TaskDispatch.ctx("gametest-tallgrass", companion);
        TaskDispatch.setTask(companion, new BuildTaskRecord(ctx.toolCallId(), ctx.deadline(4000L),
                List.of(new BuildTaskRecord.Target(Blocks.CRAFTING_TABLE, Items.CRAFTING_TABLE,
                        lower, "crafting_table")), com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_EMPTY, true, true),
                null, reply -> {});
        helper.succeedWhen(() -> {
            helper.assertTrue(level.getBlockState(lower).is(Blocks.CRAFTING_TABLE), "工作台没放上");
            helper.assertTrue(!level.getBlockState(lower.above()).is(Blocks.TALL_GRASS),
                    "孤儿草还悬在工作台头顶: " + level.getBlockState(lower.above()));
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 方向 → 栅栏臂属性(CrossCollisionBlock 的映射表是 protected,用公开常量自建)。 */
    private static net.minecraft.world.level.block.state.properties.BooleanProperty fenceArm(
            net.minecraft.core.Direction d) {
        return switch (d) {
            case NORTH -> net.minecraft.world.level.block.state.properties.BlockStateProperties.NORTH;
            case SOUTH -> net.minecraft.world.level.block.state.properties.BlockStateProperties.SOUTH;
            case EAST -> net.minecraft.world.level.block.state.properties.BlockStateProperties.EAST;
            case WEST -> net.minecraft.world.level.block.state.properties.BlockStateProperties.WEST;
            default -> throw new IllegalArgumentException(String.valueOf(d));
        };
    }

    /**
     * 收尾连接重算:直写落位不惊动邻居,体积生成的栅栏本是一排孤柱;建筑完整后
     * 按真实邻居补算连接形状——三根一排,中间那根必须向两侧邻居伸臂。
     * 方向按坐标差现算,不咬绝对东西(结构旋转免疫)。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_build")
    public static void volume_fences_connect_after_build(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_fencer", new BlockPos(2, 2, 2), false);
        List<BuildTaskRecord.Target> targets = new ArrayList<>();
        for (int x = 6; x <= 8; x++) {
            targets.add(new BuildTaskRecord.Target(Blocks.OAK_FENCE, Items.OAK_FENCE,
                    helper.absolutePos(new BlockPos(x, 2, 8)), "oak_fence"));
        }
        companion.getInventory().add(new ItemStack(Items.OAK_FENCE, 3));
        var ctx = TaskDispatch.ctx("gametest-fence-row", companion);
        TaskDispatch.setTask(companion, new BuildTaskRecord(ctx.toolCallId(), ctx.deadline(4000L),
                targets, com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_EMPTY, true, true), null, reply -> {});
        BlockPos mid = helper.absolutePos(new BlockPos(7, 2, 8));
        BlockPos west = helper.absolutePos(new BlockPos(6, 2, 8));
        BlockPos east = helper.absolutePos(new BlockPos(8, 2, 8));
        helper.succeedWhen(() -> {
            BlockState m = level.getBlockState(mid);
            helper.assertTrue(m.is(Blocks.OAK_FENCE), "中间那格不是栅栏");
            for (BlockPos nb : new BlockPos[]{west, east}) {
                var d = net.minecraft.core.Direction.fromDelta(
                        nb.getX() - mid.getX(), 0, nb.getZ() - mid.getZ());
                var prop = fenceArm(d);
                helper.assertTrue(m.getValue(prop), "中间栅栏没向 " + d + " 伸臂: " + m);
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 边界贴旧墙:世界里已有的栅栏,新栅栏要伸手去贴,旧的也要伸回来。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_build")
    public static void built_fence_grabs_existing_neighbour(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(6, 2, 6), Blocks.OAK_FENCE);
        NumenPlayer companion = spawnAt(helper, "gametest_edger", new BlockPos(2, 2, 2), false);
        BlockPos oldPos = helper.absolutePos(new BlockPos(6, 2, 6));
        BlockPos newPos = helper.absolutePos(new BlockPos(7, 2, 6));
        List<BuildTaskRecord.Target> targets = List.of(
                new BuildTaskRecord.Target(Blocks.OAK_FENCE, Items.OAK_FENCE,
                        newPos, "oak_fence"));
        companion.getInventory().add(new ItemStack(Items.OAK_FENCE, 1));
        var ctx = TaskDispatch.ctx("gametest-fence-edge", companion);
        TaskDispatch.setTask(companion, new BuildTaskRecord(ctx.toolCallId(), ctx.deadline(4000L),
                targets, com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_EMPTY, true, true), null, reply -> {});
        helper.succeedWhen(() -> {
            BlockState built = level.getBlockState(newPos);
            BlockState old = level.getBlockState(oldPos);
            helper.assertTrue(built.is(Blocks.OAK_FENCE), "新栅栏没立起来");
            var toOld = net.minecraft.core.Direction.fromDelta(
                    oldPos.getX() - newPos.getX(), 0, oldPos.getZ() - newPos.getZ());
            helper.assertTrue(built.getValue(
                    fenceArm(toOld)),
                    "新栅栏没伸手贴旧邻居: " + built);
            helper.assertTrue(old.getValue(
                    fenceArm(toOld.getOpposite())),
                    "旧栅栏没伸回来: " + old);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 素面格升格原生放置:无属性无数据的格子(工作台)按玩家动作落位——升格发生在
     * record 构造时,车道/对账/扣料同读 itemPlace 一个字段;带属性的格子(栅栏)不升格。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_build")
    public static void plain_cell_promoted_to_item_place(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_joiner", new BlockPos(2, 2, 2), false);
        BlockPos tablePos = helper.absolutePos(new BlockPos(6, 2, 6));
        List<BuildTaskRecord.Target> targets = List.of(
                new BuildTaskRecord.Target(Blocks.CRAFTING_TABLE, Items.CRAFTING_TABLE,
                        tablePos, "crafting_table"),
                new BuildTaskRecord.Target(Blocks.OAK_FENCE, Items.OAK_FENCE,
                        helper.absolutePos(new BlockPos(8, 2, 6)), "oak_fence"));
        companion.getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
        companion.getInventory().add(new ItemStack(Items.OAK_FENCE, 1));
        var ctx = TaskDispatch.ctx("gametest-plain-cell", companion);
        BuildTaskRecord record = new BuildTaskRecord(ctx.toolCallId(), ctx.deadline(4000L),
                targets, com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_EMPTY, true, true);
        helper.assertTrue(record.targets.get(0).itemPlace(), "素面格(工作台)没升格成原生放置");
        helper.assertFalse(record.targets.get(1).itemPlace(), "带属性格(栅栏)不该升格");
        TaskDispatch.setTask(companion, record, null, reply -> {});
        helper.succeedWhen(() -> {
            helper.assertTrue(level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE),
                    "工作台没放出来");
            helper.assertTrue(companion.getInventory().countItem(Items.CRAFTING_TABLE) == 0,
                    "原生车道没按手扣料");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_build")
    public static void survival_build_resumes_after_restock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_hauler", new BlockPos(2, 2, 2), false);

        List<BuildTaskRecord.Target> targets = new ArrayList<>();
        for (int x = 6; x <= 10; x++) {
            for (int z = 6; z <= 10; z++) {
                targets.add(new BuildTaskRecord.Target(Blocks.COBBLESTONE, Items.COBBLESTONE,
                        helper.absolutePos(new BlockPos(x, 2, z)), "cobblestone"));
            }
        }
        final int total = targets.size();          // 25 格
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 9));   // 只够一小半

        java.util.function.Consumer<String> go = tag -> {
            var ctx = TaskDispatch.ctx(tag, companion);
            TaskDispatch.setTask(companion, new BuildTaskRecord(ctx.toolCallId(),
                    ctx.deadline(4000L), targets, com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_EMPTY, true, true), null, reply -> {});
        };
        go.accept("gametest-resume-1");

        java.util.concurrent.atomic.AtomicBoolean restocked =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        helper.succeedWhen(() -> {
            int built = 0;
            for (BuildTaskRecord.Target t : targets) {
                if (t.matches(level.getBlockState(t.pos()))) built++;
            }
            if (!restocked.get()) {
                // 等第一趟自己停下来(车道空了),再看它到底建了多少
                helper.assertTrue(com.dwinovo.numen.task.CompanionTickDispatcher
                                .currentTaskFor(companion.getUUID()) == null,
                        "first run still going");
                helper.assertTrue(built > 0 && built < total,
                        "first run should stop part-way on 9 cobblestone, built " + built + "/" + total);
                companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 32));
                restocked.set(true);
                go.accept("gametest-resume-2");
                helper.fail("restocked; waiting for the second run");
            }
            helper.assertTrue(built == total,
                    "restocked repeat must finish the job, built " + built + "/" + total);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * {@code air} 要能清空,液体要<b>说清是能力边界</b>,一格几件料要算准。
     *
     * <p>这三条都是实测账单逼出来的:60 次 build 调用被拒 22 次,其中 15 次
     * {@code unknown item: air}、3 次 {@code unknown item: water}。根子是 block_id
     * 走了物品注册表——那个入口把 AIR 当未知物品拒掉(对吃/丢/取是对的),于是两处
     * {@code if (item == AIR)} 的分支成了永远到不了的死代码,而工具描述里白纸黑字
     * 写着 air 能清空。模型照文档写、被拒、换个写法再被拒,一次建造烧掉四轮往返。
     *
     * <p>液体则相反:那是真的不做,所以错误信息必须说是边界,不能回一句"未知方块"
     * ——名字明明是对的,模型只会以为自己拼错了。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_build")
    public static void build_block_ids_and_material_counts(GameTestHelper helper) {
        for (String id : new String[]{"air", "minecraft:air", "dirt_path", "farmland", "tall_grass"}) {
            com.dwinovo.numen.core.build.BuildPalette.parse(id);
        }
        for (String liquid : new String[]{"water", "minecraft:water", "lava"}) {
            String msg = "";
            try {
                com.dwinovo.numen.core.build.BuildPalette.parse(liquid);
            } catch (IllegalArgumentException e) {
                msg = String.valueOf(e.getMessage());
            }
            helper.assertTrue(msg.contains("liquid"),
                    liquid + " must be refused as a capability boundary, not as a bad name; got \"" + msg + "\"");
        }
        // 方块状态跟在方块名后面,和 /setblock 同一套语法;调色板里的逗号分项,
        // 而状态里的逗号属于状态——切分必须认方括号,否则混合料一带状态就被劈开
        var stairs = com.dwinovo.numen.core.build.BuildPalette
                .parse("spruce_stairs[facing=south,half=top]").first().state();
        helper.assertTrue(stairs.is(Blocks.SPRUCE_STAIRS)
                        && stairs.getValue(net.minecraft.world.level.block.StairBlock.FACING)
                                == net.minecraft.core.Direction.SOUTH
                        && stairs.getValue(net.minecraft.world.level.block.StairBlock.HALF)
                                == net.minecraft.world.level.block.state.properties.Half.TOP,
                "the block state must come through the block_id, got " + stairs);
        var statefulMix = com.dwinovo.numen.core.build.BuildPalette
                .parse("oak_slab[type=top]*3, stone_brick_slab[type=top]");
        helper.assertTrue(!statefulMix.isSingle() && statefulMix.first().state()
                        .getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
                                == net.minecraft.world.level.block.state.properties.SlabType.TOP,
                "a weighted mix of stateful blocks must survive the split");
        String stateMsg = "";
        try {
            com.dwinovo.numen.core.build.BuildPalette.parse("stone[facing=north]");
        } catch (IllegalArgumentException e) {
            stateMsg = String.valueOf(e.getMessage());
        }
        helper.assertTrue(stateMsg.contains("facing"),
                "a property the block does not have must be refused by name; got \"" + stateMsg + "\"");

        // 没有自己物品的方块要拿替代料算账,否则文档里教的 dirt_path 根本放不下去
        helper.assertTrue(com.dwinovo.numen.core.build.BuildPalette.parse("dirt_path")
                        .pick(BlockPos.ZERO).item() == Items.DIRT,
                "dirt_path has no item of its own; it must be billed as dirt");

        // 一格几件料:双层砖是两块半砖摞出来的,门/床的上半不重复计
        record Case(BlockState state, int want, String why) {}
        var slab = Blocks.STONE_BRICK_SLAB;
        for (Case c : List.of(
                new Case(slab.defaultBlockState().setValue(
                        net.minecraft.world.level.block.SlabBlock.TYPE,
                        net.minecraft.world.level.block.state.properties.SlabType.DOUBLE), 2,
                        "a double slab is two slabs"),
                new Case(slab.defaultBlockState(), 1, "a single slab is one"),
                new Case(Blocks.STONE.defaultBlockState(), 1, "a plain block is one"),
                new Case(Blocks.SNOW.defaultBlockState().setValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.LAYERS, 5), 5,
                        "snow is billed per layer"),
                // 高草一格一件:tall_grass 自己就是物品。按"两株矮的"算两件
                // 会让玩家照清单备双份。
                new Case(Blocks.TALL_GRASS.defaultBlockState(), 1,
                        "tall grass is one item of its own now"),
                new Case(Blocks.WATER.defaultBlockState(), 0, "liquids cost nothing"),
                new Case(Blocks.AIR.defaultBlockState(), 0, "clearing costs nothing"))) {
            var t = new BuildTaskRecord.Target(c.state(), Items.STONE, BlockPos.ZERO, "x");
            helper.assertTrue(t.materialCount() == c.want(),
                    c.why() + " — expected " + c.want() + ", got " + t.materialCount());
        }

        // 贴附件整体推到第二趟:骨架先立完,再回头挂灯摆花
        List<BuildTaskRecord.Target> mixed = new ArrayList<>(List.of(
                new BuildTaskRecord.Target(Blocks.TORCH.defaultBlockState(), Items.TORCH,
                        new BlockPos(1, 1, 0), "torch"),
                new BuildTaskRecord.Target(Blocks.RED_CARPET.defaultBlockState(), Items.RED_CARPET,
                        new BlockPos(2, 1, 0), "carpet"),
                new BuildTaskRecord.Target(Blocks.STONE, Items.STONE,
                        new BlockPos(0, 9, 0), "stone")));
        mixed.sort(com.dwinovo.numen.core.task.build.BuildOrder.BUILD_ORDER);
        helper.assertTrue(mixed.get(0).desiredState().getBlock() == Blocks.STONE,
                "everything that stands on its own goes first, even nine layers up; got "
                        + mixed.stream().map(BuildTaskRecord.Target::label).toList());
        helper.succeed();
    }

    /**
     * 图纸带来的方块实体数据只搬装饰性的那部分,<b>容器内容一律不搬</b>。
     *
     * <p>方块实体与实体走同一条线,两个方向都得钉住。搬:告示牌的字、旗帜的花纹——不搬的话社区图纸建出来是
     * 一屋子白板,外形全对内容全丢,玩家一眼看得出来。不搬:箱子里的东西——图纸是
     * 文件,可以任意编辑、可以从网上下载,照搬容器内容意味着一张塞满钻石的图纸
     * 建出来就是白送。这不是保守,是这条线必须画在这里。
     */
    @GameTest(template = "floor16", timeoutTicks = 1400, batch = "numen_build")
    public static void blueprint_block_entity_contents_survive(GameTestHelper helper) {
        // 白名单先在纯函数层验:同一份数据,告示牌留字、箱子什么都不留
        var signData = new net.minecraft.nbt.CompoundTag();
        signData.putString("id", "minecraft:oak_sign");
        var front = new net.minecraft.nbt.CompoundTag();
        front.putBoolean("has_glowing_text", false);
        signData.put("front_text", front);
        var keptSign = com.dwinovo.numen.core.build.BlueprintSafety.safeBlockEntityData(
                Blocks.OAK_SIGN.defaultBlockState(), signData);
        helper.assertTrue(keptSign != null && keptSign.contains("front_text"),
                "a sign's text is the whole point of carrying its data");
        // 牌子上真正要防的是<b>能执行的东西</b>,不是无意义的外来键:一块牌子上塞个 Items
        // 谁都读不到,而一个 clickEvent 能跑命令。逐个威胁按名检查(见
        // safe_block_entity_data_is_a_datapack_tag),而不是拉一张"外来键"黑名单——
        // 那张名单是开放集合,每来一个新方块实体就得被咬一次。
        var signWithItem = signData.copy();
        signWithItem.put("front_item", new net.minecraft.nbt.CompoundTag());
        helper.assertTrue(com.dwinovo.numen.core.build.BlueprintSafety.safeBlockEntityData(
                        Blocks.OAK_SIGN.defaultBlockState(), signWithItem) == null,
                "a sign that holds an itemstack (some mods add this) carries goods, so it is out");

        var chestData = new net.minecraft.nbt.CompoundTag();
        chestData.putString("id", "minecraft:chest");
        var stack = new net.minecraft.nbt.CompoundTag();
        stack.putByte("Slot", (byte) 0);
        stack.putString("id", "minecraft:diamond");
        stack.putInt("count", 64);
        var items = new net.minecraft.nbt.ListTag();
        items.add(stack);
        chestData.put("Items", items);
        helper.assertTrue(com.dwinovo.numen.core.build.BlueprintSafety.safeBlockEntityData(
                        Blocks.CHEST.defaultBlockState(), chestData) == null,
                "a blueprint full of diamonds must not print diamonds");

        // 实体同一条线,但结论不同:摆设收躯壳,活物不收,而身上带的东西<b>照搬照收</b>
        // ——按组件全等收料,交什么得什么。剥掉是另一种不诚实:图纸摆好的武器架建出来
        // 空着,而玩家也没省下什么。
        var frame = new net.minecraft.nbt.CompoundTag();
        frame.putString("id", "minecraft:item_frame");
        frame.putByte("Facing", (byte) 3);
        var held = new net.minecraft.nbt.CompoundTag();
        held.putString("id", "minecraft:diamond_sword");
        held.putInt("count", 1);
        frame.put("Item", held);
        var keptFrame = com.dwinovo.numen.core.build.BlueprintSafety.safeEntityData(
                frame, helper.getLevel().registryAccess());
        helper.assertTrue(keptFrame != null && keptFrame.contains("Facing"),
                "an item frame is part of the building; its shell must be spawned");
        helper.assertTrue(keptFrame != null && keptFrame.contains("Item"),
                "what hangs in it stays — but it becomes a requirement for that exact item");
        helper.assertTrue(com.dwinovo.numen.core.build.BlueprintSafety
                        .payloadStacks(keptFrame, helper.getLevel().registryAccess()).size() == 1,
                "and that requirement has to be readable, or nobody ever gets charged for it");

        var cow = new net.minecraft.nbt.CompoundTag();
        cow.putString("id", "minecraft:cow");
        helper.assertTrue(com.dwinovo.numen.core.build.BlueprintSafety.safeEntityData(
                        cow, helper.getLevel().registryAccess()) == null,
                "a cow stored in a blueprint is not a design; copying it conjures livestock");

        // 再在世界里走一遍:旗帜的花纹要真的落到方块实体上
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_banner", new BlockPos(2, 2, 2), true);
        BlockPos at = helper.absolutePos(new BlockPos(7, 2, 7));
        var patterns = new net.minecraft.nbt.ListTag();
        var one = new net.minecraft.nbt.CompoundTag();
        one.putString("color", "red");
        one.putString("pattern", "minecraft:stripe_top");
        patterns.add(one);
        var bannerData = new net.minecraft.nbt.CompoundTag();
        bannerData.putString("id", "minecraft:banner");
        bannerData.put("patterns", patterns);

        var targets = List.of(new BuildTaskRecord.Target(Blocks.WHITE_BANNER, Items.WHITE_BANNER,
                at, "banner"));
        var ctx = TaskDispatch.ctx("gametest-be", companion);
        TaskDispatch.setTask(companion, new BuildTaskRecord(ctx.toolCallId(),
                ctx.deadline(4000L), targets,
                com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_EMPTY, false, false,
                java.util.Map.of(at.asLong(), bannerData)), null, reply -> {});

        helper.succeedWhen(() -> {
            helper.assertTrue(level.getBlockState(at).is(Blocks.WHITE_BANNER),
                    "the banner itself is not placed yet");
            var be = level.getBlockEntity(at);
            helper.assertTrue(be instanceof net.minecraft.world.level.block.entity.BannerBlockEntity,
                    "banner has no block entity");
            var saved = be.saveWithoutMetadata(level.registryAccess());
            helper.assertTrue(saved.contains("patterns"),
                    "the blueprint's banner pattern must survive placement, got " + saved);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 让路的四档:每一档让到什么程度。
     *
     * <p>当前工具层只发最高和最低两档,中间两档走不到——<b>正因为走不到才要测</b>,
     * 不然等图纸层把档位开放给玩家时,它们已经烂了而没人知道。
     *
     * <p>"软"用 {@code canBeReplaced()} 判(草、花、雪层、火):原版自己判断"能不能
     * 直接盖上去"用的就是它,不必另立一套近似判据。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void replace_modes_let_through_what_they_say(GameTestHelper helper) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState soft = Blocks.SHORT_GRASS.defaultBlockState();
        BlockState solid = Blocks.STONE.defaultBlockState();
        BlockState torch = Blocks.TORCH.defaultBlockState();

        record Row(com.dwinovo.numen.core.task.build.ReplaceMode mode, BlockState current,
                   BlockState desired, boolean want, String why) {}
        for (Row row : List.of(
                // 最低档:只往空地和软方块上补,既有建筑一格不碰,也不清空
                new Row(com.dwinovo.numen.core.task.build.ReplaceMode.DONT_REPLACE, air, solid, true,
                        "empty ground is always fair game"),
                new Row(com.dwinovo.numen.core.task.build.ReplaceMode.DONT_REPLACE, soft, solid, true,
                        "grass is replaceable, vanilla lets you build straight over it"),
                new Row(com.dwinovo.numen.core.task.build.ReplaceMode.DONT_REPLACE, solid, solid, false,
                        "this mode exists so an existing building is never touched"),
                new Row(com.dwinovo.numen.core.task.build.ReplaceMode.DONT_REPLACE, solid, air, false,
                        "no mode below the top one clears anything"),
                // 中档:实心可以压实心,但细软件不能顶掉墙
                new Row(com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_SOLID, solid, solid, true,
                        "structure may push through structure"),
                new Row(com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_SOLID, solid, torch, false,
                        "a torch must not knock out a wall"),
                new Row(com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_SOLID, soft, torch, true,
                        "but it may go where there was only grass"),
                new Row(com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_SOLID, solid, air, false,
                        "still no clearing"),
                // 高档:挡路的一律顶掉,但空气格只当"不管这一格"
                new Row(com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_ANY, solid, torch, true,
                        "anything in the way gives way"),
                new Row(com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_ANY, solid, air, false,
                        "an air cell here means 'leave this one alone', not 'dig it out'"),
                // 顶档:连该空的地方也挖空
                new Row(com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_EMPTY, solid, air, true,
                        "this is the mode where an air cell is a dig order"),
                new Row(com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_EMPTY, solid, torch, true,
                        "and everything else gives way too"))) {
            boolean got = row.mode().allows(row.current(), row.desired());
            helper.assertTrue(got == row.want(), row.mode() + ": " + row.why()
                    + " — expected " + row.want() + ", got " + got);
        }
        helper.succeed();
    }

    /**
     * 图纸里的<b>运行态</b>不能照抄进世界。
     *
     * <p>图纸是某个世界某一刻的快照,里面混着大量世界自己算出来的东西:作物长到第
     * 几节、方块含不含水、活塞伸没伸出去、堆肥桶攒了多少、锅里装的什么。照字面摆
     * 下去就会凭空长出一片熟麦子、凭空造出水来、摆一口装着岩浆的锅。
     *
     * <p>判据和 {@code BuildValidity} 那张"作者属性"白名单是同一条的两面:那边管
     * 比对时忽略什么,这边管落位前清掉什么。所以归一必须在<b>每个目标格都要过的
     * 那道口</b>上做一次,而不是让工具入口和图纸入口各清各的。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void blueprint_runtime_state_is_normalized(GameTestHelper helper) {
        BlockState ripe = Blocks.WHEAT.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_7, 7);
        BlockState wet = Blocks.OAK_STAIRS.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true);
        BlockState full = Blocks.COMPOSTER.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL_COMPOSTER, 5);

        var wheat = new BuildTaskRecord.Target(ripe, Items.WHEAT_SEEDS, BlockPos.ZERO, "wheat");
        helper.assertTrue(wheat.desiredState().getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_7) == 0,
                "a blueprint's ripe wheat must be planted as a seedling, not conjured fully grown");

        var stair = new BuildTaskRecord.Target(wet, Items.OAK_STAIRS, BlockPos.ZERO, "stair");
        helper.assertTrue(!stair.desiredState().getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED),
                "waterlogging is derived from the water around a block; copying it conjures water"
                        + " out of nothing, and she does not place water at all");

        var composter = new BuildTaskRecord.Target(full, Items.COMPOSTER, BlockPos.ZERO, "composter");
        helper.assertTrue(composter.desiredState().getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL_COMPOSTER) == 0,
                "how full a composter is, is runtime state — it must be placed empty");

        var cauldron = new BuildTaskRecord.Target(Blocks.LAVA_CAULDRON.defaultBlockState(),
                Items.CAULDRON, BlockPos.ZERO, "cauldron");
        helper.assertTrue(cauldron.desiredState().is(Blocks.CAULDRON),
                "a cauldron's contents are runtime state; a blueprint must not hand out free lava");

        var leaves = new BuildTaskRecord.Target(Blocks.OAK_LEAVES.defaultBlockState(),
                Items.OAK_LEAVES, BlockPos.ZERO, "leaves");
        helper.assertTrue(leaves.desiredState().getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT),
                "placed leaves are hand-placed leaves; without this they rot as fast as she builds");

        // 蜂巢的蜜、洞穴藤蔓的果、龟蛋的孵化进度:三条都是"照抄就白送"。藤蔓那条
        // 最直接——一格花一颗发光浆果,玩家伸手一摘把那颗原样收回、藤蔓还留着。
        BlockState honeyed = Blocks.BEE_NEST.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL_HONEY, 5);
        var nest = new BuildTaskRecord.Target(honeyed, Items.BEE_NEST, BlockPos.ZERO, "nest");
        helper.assertTrue(nest.desiredState().getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL_HONEY) == 0,
                "stored honey is runtime state; copying it lets the player shear free honeycomb");

        BlockState berried = Blocks.CAVE_VINES.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.BERRIES, true);
        var vine = new BuildTaskRecord.Target(berried, Items.GLOW_BERRIES, BlockPos.ZERO, "vine");
        helper.assertTrue(!vine.desiredState().getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.BERRIES),
                "a berried cave vine hands the berry straight back — the whole wall would be free");
        helper.succeed();
    }

    /**
     * 双格方块的<b>次半</b>不进目标集,由主半自己造出来。
     *
     * <p>床是这条规则的由来。床的两半同 y,施工顺序在同高时按 z 递增,facing=north
     * 时<b>床头先落位</b>——而床的 {@code setPlacedBy} 会往"朝向再往外一格"再写一块
     * 床头,那一格在目标集之外:不记账、不算脚手架、收工不清。一张床收一件料,世界里
     * 留下三块床方块。若那一格恰好是已砌好的内墙(床头贴墙是最常见的摆法),它被覆写,
     * 下一遍判成"被拆了"重建,来回死转,只被零进展遍上限兜住。
     *
     * <p>所以判据落在加载期:次半从来不是我们放的,不该占一格待办,也不该占分母。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_build")
    public static void blueprint_secondary_halves_are_not_targets(GameTestHelper helper)
            throws Exception {
        ServerLevel level = helper.getLevel();
        BlockState foot = Blocks.RED_BED.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART,
                        net.minecraft.world.level.block.state.properties.BedPart.FOOT)
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties
                        .HORIZONTAL_FACING, net.minecraft.core.Direction.NORTH);
        BlockState head = foot.setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART,
                net.minecraft.world.level.block.state.properties.BedPart.HEAD);
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState();
        BlockState upper = lower.setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);

        // 一张四格的图纸:床脚 + 床头 + 门下半 + 门上半
        var root = new net.minecraft.nbt.CompoundTag();
        var size = new net.minecraft.nbt.ListTag();
        size.add(net.minecraft.nbt.IntTag.valueOf(3));
        size.add(net.minecraft.nbt.IntTag.valueOf(2));
        size.add(net.minecraft.nbt.IntTag.valueOf(3));
        root.put("size", size);
        var palette = new net.minecraft.nbt.ListTag();
        for (BlockState s : java.util.List.of(foot, head, lower, upper)) {
            palette.add(net.minecraft.nbt.NbtUtils.writeBlockState(s));
        }
        root.put("palette", palette);
        var blocks = new net.minecraft.nbt.ListTag();
        blocks.add(cellTag(0, 0, 1, 0));   // 床脚 @ z=1
        blocks.add(cellTag(0, 0, 0, 1));   // 床头 @ z=0(朝北,z 更小,会先落位)
        blocks.add(cellTag(2, 0, 0, 2));   // 门下半
        blocks.add(cellTag(2, 1, 0, 3));   // 门上半
        root.put("blocks", blocks);
        var dir = com.dwinovo.numen.core.blueprint.BlueprintStore.dir(level.getServer());
        net.minecraft.nbt.NbtIo.writeCompressed(root, dir.resolve("fixture_halves.nbt"));

        BlockPos anchor = helper.absolutePos(new BlockPos(2, 2, 2));
        var loaded = com.dwinovo.numen.core.blueprint.BlueprintStore.load(
                level, "fixture_halves", anchor, 0);
        helper.assertTrue(loaded.targets().size() == 2,
                "only the primary halves belong in the target set, got "
                        + loaded.targets().size() + " cells");
        for (var t : loaded.targets()) {
            helper.assertTrue(!com.dwinovo.numen.core.build.BuildStates
                            .isSecondaryHalf(t.desiredState()),
                    "a bed head / upper door half must never be a target: " + t.desiredState());
        }
        // 次半不算掉格:它是被主半代建的,不是缺了一块设计
        helper.assertTrue(loaded.dropped() == 0,
                "secondary halves are built by their primary, not dropped: " + loaded.dropped());
        // 料还是一张床一件、一扇门一件——次半本来就记 0 件,剔掉不改报价
        int beds = 0;
        int doors = 0;
        for (var t : loaded.targets()) {
            if (t.item() == Items.RED_BED) beds += t.materialCount();
            if (t.item() == Items.OAK_DOOR) doors += t.materialCount();
        }
        helper.assertTrue(beds == 1 && doors == 1,
                "one bed and one door, got bed x" + beds + " door x" + doors);
        helper.succeed();
    }

    /**
     * 图纸里的摆设真的挂上了墙——<b>锚点这条路必须走通,而它有一道 16 格闸门</b>。
     *
     * <p>挂件(展示框、画)钉在哪面墙上,是它自己 NBT 里的一个绝对方块坐标,不是由
     * 位置推出来的。读档时这个锚点要过一道校验:锚点离实体位置超过 16 格就判成坏档、
     * 丢掉不用——而丢掉之后重算碰撞箱会拿一个空坐标去算中心点,当场抛异常,这只摆设
     * 静默消失,只在日志里留一行。所以位置与锚点<b>必须同源写入</b>,只写一个不行。
     *
     * <p>这条只能在游戏里验:纯函数层看不出闸门,拼出来的 NBT 看着完全正确。测试
     * 故意把源世界的锚点写成十万格开外——加载器该把它连位置一起剥掉,由落位方按落位
     * 坐标重写。剥漏了或者重写漏了,这里就一只摆设都看不见。
     */
    @GameTest(template = "floor16", timeoutTicks = 1200, batch = "numen_build")
    public static void blueprint_fixtures_hang_where_they_belong(GameTestHelper helper)
            throws Exception {
        ServerLevel level = helper.getLevel();
        var root = new net.minecraft.nbt.CompoundTag();
        var size = new net.minecraft.nbt.ListTag();
        size.add(net.minecraft.nbt.IntTag.valueOf(3));
        size.add(net.minecraft.nbt.IntTag.valueOf(2));
        size.add(net.minecraft.nbt.IntTag.valueOf(3));
        root.put("size", size);
        var palette = new net.minecraft.nbt.ListTag();
        palette.add(net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        root.put("palette", palette);
        var blocks = new net.minecraft.nbt.ListTag();
        blocks.add(cellTag(1, 0, 1, 0));   // 挂展示框的那面墙
        root.put("blocks", blocks);

        // 展示框挂在 (1,0,1) 这块石头的南面,所以它自己在 (1,0,2)
        var frame = new net.minecraft.nbt.CompoundTag();
        frame.putString("id", "minecraft:item_frame");
        frame.putByte("Facing", (byte) net.minecraft.core.Direction.SOUTH.get3DDataValue());
        // 源世界的锚点与位置:十万格开外。两个键都该被剥掉重写
        frame.putInt("TileX", 100000);
        frame.putInt("TileY", 64);
        frame.putInt("TileZ", 100000);
        var strayPos = new net.minecraft.nbt.ListTag();
        strayPos.add(net.minecraft.nbt.DoubleTag.valueOf(100000.5));
        strayPos.add(net.minecraft.nbt.DoubleTag.valueOf(64.5));
        strayPos.add(net.minecraft.nbt.DoubleTag.valueOf(100000.5));
        frame.put("Pos", strayPos);
        // 框里的物品:必须被剥掉,图纸不能凭空造钻石
        var loot = new net.minecraft.nbt.CompoundTag();
        loot.putString("id", "minecraft:diamond");
        loot.putInt("count", 1);
        frame.put("Item", loot);

        var stand = new net.minecraft.nbt.CompoundTag();
        stand.putString("id", "minecraft:armor_stand");
        var strayStand = new net.minecraft.nbt.ListTag();
        strayStand.add(net.minecraft.nbt.DoubleTag.valueOf(100000.5));
        strayStand.add(net.minecraft.nbt.DoubleTag.valueOf(64.0));
        strayStand.add(net.minecraft.nbt.DoubleTag.valueOf(100000.5));
        stand.put("Pos", strayStand);

        var entities = new net.minecraft.nbt.ListTag();
        entities.add(entityTag(1.5, 0.5, 2.5, frame));
        entities.add(entityTag(2.5, 0.0, 2.5, stand));
        root.put("entities", entities);
        var dir = com.dwinovo.numen.core.blueprint.BlueprintStore.dir(level.getServer());
        net.minecraft.nbt.NbtIo.writeCompressed(root, dir.resolve("fixture_hangers.nbt"));

        BlockPos anchor = helper.absolutePos(new BlockPos(4, 2, 4));
        var loaded = com.dwinovo.numen.core.blueprint.BlueprintStore.load(
                level, "fixture_hangers", anchor, 0);
        helper.assertTrue(loaded.entities().size() == 2,
                "expected the frame and the stand to load, got " + loaded.entities().size());
        for (var spawn : loaded.entities()) {
            helper.assertTrue(!spawn.nbt().contains("TileX") && !spawn.nbt().contains("Pos"),
                    "the source world's anchor and position must be stripped, not carried over");
        }
        // 框里那颗钻石不白送,也不静默丢掉:它变成一笔"要一模一样那件东西"的料
        var carried = loaded.entities().stream()
                .flatMap(s -> s.payload(level.registryAccess()).stream()).toList();
        helper.assertTrue(carried.size() == 1 && carried.get(0).is(Items.DIAMOND),
                "the frame's diamond must show up as a material requirement, got " + carried);
        // 免耗材同伴不付料,所以框里那颗钻石照放——收什么放什么,这一档收的是零
        NumenPlayer companion = spawnAt(helper, "gametest_hanger", new BlockPos(1, 2, 1), true);
        var ctx = TaskDispatch.ctx("gametest-fixtures", companion);
        TaskDispatch.setTask(companion, new BuildTaskRecord(ctx.toolCallId(),
                ctx.deadline(1000L), loaded.targets(),
                com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_EMPTY, false, true,
                loaded.blockEntityData(), loaded.entities()), null, reply -> {});

        Vec3 want = new Vec3(anchor.getX() + 1.5, anchor.getY() + 0.5, anchor.getZ() + 2.5);
        net.minecraft.world.phys.AABB near = new net.minecraft.world.phys.AABB(
                want.x - 2, want.y - 2, want.z - 2, want.x + 2, want.y + 2, want.z + 2);
        helper.succeedWhen(() -> {
            var frames = level.getEntities(
                    net.minecraft.world.entity.EntityType.ITEM_FRAME, near, e -> true);
            helper.assertTrue(!frames.isEmpty(),
                    "the item frame never made it onto the wall — its anchor was rejected"
                            + " and it vanished without a trace");
            var hung = frames.get(0);
            helper.assertTrue(hung.getItem().is(Items.DIAMOND),
                    "this companion pays nothing, so the frame keeps what the blueprint had in it;"
                            + " a paying one must own that exact item instead");
            helper.assertTrue(hung.position().distanceTo(want) < 1.5,
                    "the frame hung at " + hung.position() + " but belongs at " + want);
            var stands = level.getEntities(
                    net.minecraft.world.entity.EntityType.ARMOR_STAND, near, e -> true);
            helper.assertTrue(!stands.isEmpty(), "the armour stand never got placed");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 图纸带来的"手工活"不能白送:旗帜的花纹按<b>带花纹的那面旗帜</b>收料,陶罐的
     * 纹样干脆不搬。
     *
     * <p>这一族的判据必须是<b>组件全等</b>而不是物品类型。按类型收的话,一面白旗就能
     * 换来文件里那面绣了六层的旗——花纹是玩家在织布机上一层层染出来的活,那份活就这么
     * 没了。同一个道理往下推,陶罐的纹样碎片是刷沙刷砾石考古刷出来的稀有掉落,四片碎片
     * 收一件普通陶罐的料更离谱;而按组件精确收又要求玩家先有一只一模一样的罐子——那还
     * 不如让他自己拼,所以纹样这一项不搬,摆一只素罐。
     *
     * <p>牌子上的字照搬:那是纯文本,玩家自己写也是白写的,搬过来不产出任何东西。
     * 三者的差别不在"是不是装饰",而在<b>还原它等不等于凭空产出</b>。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_build")
    public static void blueprint_handiwork_is_not_free(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();

        // 一面绣了花纹的旗帜:方块实体里那份 patterns 该原样进白名单
        var patterns = new net.minecraft.nbt.ListTag();
        var layer = new net.minecraft.nbt.CompoundTag();
        layer.putString("pattern", "minecraft:stripe_top");
        layer.putString("color", "red");
        patterns.add(layer);
        var bannerData = new net.minecraft.nbt.CompoundTag();
        bannerData.putString("id", "minecraft:banner");
        bannerData.put("patterns", patterns);

        BlockState banner = Blocks.WHITE_BANNER.defaultBlockState();
        var safeBanner = com.dwinovo.numen.core.build.BlueprintSafety
                .safeBlockEntityData(banner, bannerData);
        helper.assertTrue(safeBanner != null && safeBanner.contains("patterns"),
                "a banner's patterns are part of the design and must be carried over");

        // 而料要收的是"带着这些花纹的那面旗帜",不是一面白旗
        var exact = com.dwinovo.numen.core.build.BuildStates
                .strictItem(banner, safeBanner, registries);
        helper.assertTrue(exact != null && exact.is(Items.WHITE_BANNER),
                "the requirement should be a white banner stack, got " + exact);
        helper.assertTrue(!net.minecraft.world.item.ItemStack.isSameItemSameComponents(
                        exact, new net.minecraft.world.item.ItemStack(Items.WHITE_BANNER)),
                "a plain white banner must NOT satisfy it — that hands out the loom work for free");
        helper.assertTrue(net.minecraft.world.item.ItemStack.isSameItem(
                        exact, new net.minecraft.world.item.ItemStack(Items.WHITE_BANNER)),
                "it is still a white banner by type; only the components differ");

        // 陶罐的纹样不搬:碎片是稀有掉落,还原它就是凭空产出
        var potData = new net.minecraft.nbt.CompoundTag();
        potData.putString("id", "minecraft:decorated_pot");
        var sherds = new net.minecraft.nbt.ListTag();
        sherds.add(net.minecraft.nbt.StringTag.valueOf("minecraft:heart_pottery_sherd"));
        potData.put("sherds", sherds);
        helper.assertTrue(com.dwinovo.numen.core.build.BlueprintSafety.safeBlockEntityData(
                        Blocks.DECORATED_POT.defaultBlockState(), potData) == null,
                "pottery sherds are rare archaeology drops; a blueprint must not conjure them");

        // 牌子上的字照搬——纯文本,还原它不产出任何东西
        var signData = new net.minecraft.nbt.CompoundTag();
        signData.putString("id", "minecraft:oak_sign");
        signData.put("front_text", new net.minecraft.nbt.CompoundTag());
        helper.assertTrue(com.dwinovo.numen.core.build.BlueprintSafety.safeBlockEntityData(
                        Blocks.OAK_SIGN.defaultBlockState(), signData) != null,
                "sign text is free to write by hand, so carrying it over conjures nothing");

        // 摆设身上带的东西读得出来,且组件跟着来
        var frame = new net.minecraft.nbt.CompoundTag();
        frame.putString("id", "minecraft:item_frame");
        var sword = new net.minecraft.nbt.CompoundTag();
        sword.putString("id", "minecraft:diamond_sword");
        sword.putInt("count", 1);
        frame.put("Item", sword);
        var carried = com.dwinovo.numen.core.build.BlueprintSafety.payloadStacks(frame, registries);
        helper.assertTrue(carried.size() == 1 && carried.get(0).is(Items.DIAMOND_SWORD),
                "what a frame carries must be read out as its own requirement, got " + carried);
        helper.succeed();
    }

    /**
     * 材料记账上<b>有意</b>不随大流的那几行,钉成断言。
     *
     * <p>"没有自己的物品"这件事,通行做法是整格丢掉——方块的 {@code asItem()} 是空气,
     * 那一格就不进清单也不放。这条规则对水和活塞头是对的,对<b>带花的花盆和作物</b>是
     * 错的:用户那栋日式小屋里有二十一个花盆加一片小麦胡萝卜,按那条规则建出来就少了
     * 这些,而院子里少二十一个花盆是一眼就看得见的。我们改成按"种下去该花的那件东西"
     * 计价(空盆、种子、果实),多建二十二格。
     *
     * <p>反方向也有:一格四根蜡烛、贴三面的藤蔓,通行做法是收一件。那是白送。我们按
     * 根数与面数收。
     *
     * <p>两个方向合起来是同一条判据:<b>一格该收多少,取决于人手工要花多少</b>,不取决于
     * 那个方块的 {@code asItem()} 恰好是什么。这条测试的意义不是"证明我们对",是把这几行
     * 分歧写在明处——哪天有人照通行做法"修正"回去,得先来改这里的字。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_build")
    public static void material_accounting_divergences_are_deliberate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos probe = helper.absolutePos(new BlockPos(1, 2, 1));

        // 一、"这个方块该收什么料"这个问题,答案<b>问方块自己</b>,不查写死的表。
        // 中键取方块是原版给每个方块留的自述接口:小麦答种子、洞穴藤蔓答发光浆果、
        // 竹笋答竹子、连枝的瓜藤答瓜种、带花的花盆答盆里那株花。这十三条此前是一行行
        // 写死的,每行都是被咬过一次才补上,而且只认原版——模组的作物一个都不认。
        //
        // 这一组断言钉的不是"某个方块该收某件料",而是<b>这些答案不该由我们回答</b>。
        record Ask(net.minecraft.world.level.block.Block block,
                   net.minecraft.world.item.Item pay, String what) {}
        for (Ask s : List.of(
                new Ask(Blocks.KELP_PLANT, Items.KELP, "kelp"),
                new Ask(Blocks.BAMBOO_SAPLING, Items.BAMBOO, "a bamboo shoot"),
                new Ask(Blocks.ATTACHED_PUMPKIN_STEM, Items.PUMPKIN_SEEDS, "an attached pumpkin stem"),
                new Ask(Blocks.ATTACHED_MELON_STEM, Items.MELON_SEEDS, "an attached melon stem"),
                new Ask(Blocks.CAVE_VINES_PLANT, Items.GLOW_BERRIES, "a cave vine plant"),
                new Ask(Blocks.WHEAT, Items.WHEAT_SEEDS, "wheat"),
                new Ask(Blocks.CARROTS, Items.CARROT, "carrots"),
                new Ask(Blocks.POTATOES, Items.POTATO, "potatoes"),
                new Ask(Blocks.BEETROOTS, Items.BEETROOT_SEEDS, "beetroots"),
                new Ask(Blocks.CAVE_VINES, Items.GLOW_BERRIES, "cave vines"),
                new Ask(Blocks.SWEET_BERRY_BUSH, Items.SWEET_BERRIES, "a berry bush"),
                new Ask(Blocks.MELON_STEM, Items.MELON_SEEDS, "a melon stem"),
                new Ask(Blocks.TRIPWIRE, Items.STRING, "tripwire"),
                new Ask(Blocks.REDSTONE_WIRE, Items.REDSTONE, "redstone wire"),
                new Ask(Blocks.TALL_GRASS, Items.TALL_GRASS, "tall grass"),
                new Ask(Blocks.LARGE_FERN, Items.LARGE_FERN, "a large fern"))) {
            BlockState state = s.block().defaultBlockState();
            helper.assertTrue(com.dwinovo.numen.core.build.BuildStates
                            .materialItem(state, level, probe) == s.pay(),
                    s.what() + " should cost " + s.pay() + " — and that answer must come from"
                            + " asking the block, not from a table of ours");
        }

        // 二、方块自述给不出正确答案的那种,才进表。耕地与土径自述的是自己(那两件
        // 物品确实存在),但人手上没有"一块耕地"可放——那是拿锄头在土上刨出来的。
        for (net.minecraft.world.level.block.Block b : List.of(Blocks.FARMLAND, Blocks.DIRT_PATH)) {
            helper.assertTrue(b.asItem() != Items.DIRT,
                    b + " self-reports " + b.asItem() + "; if that ever became dirt,"
                            + " this override could go");
            helper.assertTrue(com.dwinovo.numen.core.build.BuildStates
                            .materialItem(b.defaultBlockState(), level, probe) == Items.DIRT,
                    b + " is worked from dirt by hand, so dirt is what it costs");
        }

        // 三、一格不止一件:这几行按"人手工要花多少"收,不按一件收
        record Many(BlockState state, net.minecraft.world.item.Item pay, int count, String what) {}
        BlockState fourCandles = Blocks.CANDLE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties
                        .CANDLES, 4);
        BlockState threeFacedVine = Blocks.VINE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.NORTH, true)
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.EAST, true)
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.UP, true);
        BlockState doubleSlab = Blocks.STONE_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE,
                        net.minecraft.world.level.block.state.properties.SlabType.DOUBLE);
        BlockState fiveSnow = Blocks.SNOW.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LAYERS, 5);
        for (Many m : List.of(
                new Many(fourCandles, Items.CANDLE, 4, "four candles in one cell cost four candles"),
                new Many(threeFacedVine, Items.VINE, 3, "vines on three faces cost three"),
                new Many(doubleSlab, Items.STONE_SLAB, 2, "a double slab is two slabs"),
                new Many(fiveSnow, Items.SNOW, 5, "five snow layers cost five"),
                // 高草一格一件:tall_grass 自己就是物品,不再按"两株矮的"算
                new Many(Blocks.TALL_GRASS.defaultBlockState(), Items.TALL_GRASS, 1,
                        "tall grass is one item of its own now"))) {
            var target = new BuildTaskRecord.Target(m.state(), m.pay(), BlockPos.ZERO,
                    "x");
            helper.assertTrue(target.materialCount() == m.count(),
                    m.what() + " — got " + target.materialCount());
        }
        helper.succeed();
    }

    /**
     * 哪些方块的方块实体数据可以随图纸走,判据是<b>数据包标签</b>,不是代码里的 if。
     *
     * <p>标签本身就是那句授权,所以整合包能声明自己那些装饰性方块实体也安全,而不必来改
     * 我们的代码。这条测试要钉住三件事:标签真的被加载了(不是空的)、默认只放牌子和旗帜
     * 进来、以及<b>标签之外一律不搬</b>——判据只有这一处,不许再有第二处偷偷放行。
     *
     * <p>还要钉住那道硬底线:装着东西的键一概不过,数据包也降不了。参照实现读的是世界里
     * 活着的方块实体,里面不可能有外来键;我们读的是文件,手改一张图纸就能往一块牌子上塞
     * 一个 Items。牌子自己会忽略它,但"哪些方块实体读哪些键"是开放集合,不该赌。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_build")
    public static void safe_block_entity_data_is_a_datapack_tag(GameTestHelper helper) {
        var tag = com.dwinovo.numen.core.init.InitTag.SAFE_BLOCK_ENTITY_DATA;

        // 标签真的加载了。空标签会让"什么都不搬"看起来像通过,而那是最坏的假绿
        helper.assertTrue(Blocks.OAK_SIGN.defaultBlockState().is(tag),
                "the safe-block-entity-data tag is missing or empty — datagen did not run?");
        helper.assertTrue(Blocks.OAK_WALL_SIGN.defaultBlockState().is(tag)
                        && Blocks.OAK_HANGING_SIGN.defaultBlockState().is(tag),
                "naming #minecraft:all_signs must cover wall and hanging signs too — that is"
                        + " the point of referencing the vanilla tag instead of listing members");
        helper.assertTrue(Blocks.WHITE_BANNER.defaultBlockState().is(tag)
                        && Blocks.WHITE_WALL_BANNER.defaultBlockState().is(tag),
                "banners carry their patterns, and the tag must cover wall banners as well");

        // 标签之外一律不搬,而且判据只有这一处
        for (net.minecraft.world.level.block.Block outside : List.of(
                Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.FURNACE,
                Blocks.SHULKER_BOX, Blocks.HOPPER, Blocks.DISPENSER, Blocks.BREWING_STAND,
                Blocks.LECTERN, Blocks.JUKEBOX, Blocks.BEEHIVE, Blocks.SPAWNER,
                Blocks.DECORATED_POT, Blocks.COMMAND_BLOCK)) {
            helper.assertTrue(!outside.defaultBlockState().is(tag),
                    outside + " must not be in the safe tag by default");
            var data = new net.minecraft.nbt.CompoundTag();
            data.putString("id", "minecraft:whatever");
            data.putString("anything", "at all");
            helper.assertTrue(com.dwinovo.numen.core.build.BlueprintSafety.safeBlockEntityData(
                            outside.defaultBlockState(), data) == null,
                    "outside the tag nothing rides along, not one key: " + outside);
        }

        // 硬底线用原版自己的判据:只有管理员能设置 NBT 的那一档,一律不搬。命令方块、
        // 结构方块、拼图方块都在里面,而且不必我们列名单——原版正是拿这个标志决定
        // "这份 NBT 能不能由非管理员设置",我们的处境一模一样:图纸是文件,谁都能编辑。
        for (net.minecraft.world.level.block.Block opOnly : List.of(
                Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK,
                Blocks.STRUCTURE_BLOCK, Blocks.JIGSAW)) {
            var data = new net.minecraft.nbt.CompoundTag();
            data.putString("Command", "/give @s diamond 64");
            helper.assertTrue(com.dwinovo.numen.core.build.BlueprintSafety.safeBlockEntityData(
                            opOnly.defaultBlockState(), data) == null,
                    opOnly + " sets NBT only for operators; a blueprint must never carry it");
        }

        // 牌子的文本可以挂点击事件,而点击事件能跑命令——带事件的整份丢掉。图纸里一块
        // 写着"点我领奖"的牌子就是一个可执行的口子,而我们无从判断哪一行是作者的本意。
        var trapped = new net.minecraft.nbt.CompoundTag();
        trapped.putString("id", "minecraft:oak_sign");
        var front = new net.minecraft.nbt.CompoundTag();
        var lines = new net.minecraft.nbt.ListTag();
        lines.add(net.minecraft.nbt.StringTag.valueOf(
                "{\"text\":\"click me\",\"clickEvent\":"
                        + "{\"action\":\"run_command\",\"value\":\"/give @s diamond 64\"}}"));
        front.put("messages", lines);
        trapped.put("front_text", front);
        helper.assertTrue(com.dwinovo.numen.core.build.BlueprintSafety.safeBlockEntityData(
                        Blocks.OAK_SIGN.defaultBlockState(), trapped) == null,
                "a sign whose text carries a clickEvent is an executable hole, not decoration");

        // 干净的牌子照搬
        var plain = new net.minecraft.nbt.CompoundTag();
        plain.putString("id", "minecraft:oak_sign");
        var text = new net.minecraft.nbt.CompoundTag();
        var plainLines = new net.minecraft.nbt.ListTag();
        plainLines.add(net.minecraft.nbt.StringTag.valueOf("\"welcome home\""));
        text.put("messages", plainLines);
        plain.put("front_text", text);
        var kept = com.dwinovo.numen.core.build.BlueprintSafety
                .safeBlockEntityData(Blocks.OAK_SIGN.defaultBlockState(), plain);
        helper.assertTrue(kept != null && kept.contains("front_text"),
                "plain sign text is the whole point of carrying this data");
        helper.succeed();
    }

    /**
     * 一件物品身上只有<b>四样</b>组件可以随图纸走:附魔、药水成分、耐久、自定义名。
     *
     * <p>白名单而非黑名单,因为组件是开放集合——容器内容、捆绑包内容、方块实体数据、
     * 上膛的弹药、自定义数据,还有模组自己加的。列"哪些危险"每来一个新组件就漏一次;
     * 列"哪些安全"一次定完。这四样的共性是<b>它们不装东西</b>。
     *
     * <p>还要钉住一条更要紧的:剥要剥在<b>数据本身</b>上,不是只剥在计价上。只剥计价那
     * 一边、落位照放原始那一份,就等于文件里塞一个装满钻石的潜影盒 → 按空盒收料 → 放进
     * 框里是满的。收什么放什么。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_build")
    public static void only_four_item_components_ride_along(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();

        // 一个装了东西的潜影盒挂在展示框里
        var box = new net.minecraft.nbt.CompoundTag();
        box.putString("id", "minecraft:shulker_box");
        box.putInt("count", 1);
        var components = new net.minecraft.nbt.CompoundTag();
        var contents = new net.minecraft.nbt.ListTag();
        var diamond = new net.minecraft.nbt.CompoundTag();
        diamond.putString("id", "minecraft:diamond");
        diamond.putInt("count", 64);
        var slot = new net.minecraft.nbt.CompoundTag();
        slot.putInt("slot", 0);
        slot.put("item", diamond);
        contents.add(slot);
        components.put("minecraft:container", contents);
        components.putString("minecraft:custom_name", "\"Loot Box\"");
        box.put("components", components);

        var frame = new net.minecraft.nbt.CompoundTag();
        frame.putString("id", "minecraft:item_frame");
        frame.put("Item", box);

        var safe = com.dwinovo.numen.core.build.BlueprintSafety.safeEntityData(frame, registries);
        helper.assertTrue(safe != null, "the frame itself is part of the building");
        // 剥在数据本身上:落位读的这份里已经没有那箱钻石了
        var carriedNbt = safe.getCompound("Item").getCompound("components");
        helper.assertTrue(!carriedNbt.contains("minecraft:container"),
                "a container component must be stripped from the DATA, not just from the price"
                        + " — otherwise the frame goes up holding 64 diamonds nobody paid for");
        helper.assertTrue(carriedNbt.contains("minecraft:custom_name"),
                "a custom name carries nothing, so it stays");

        // 计价那一边读的是同一份
        var priced = com.dwinovo.numen.core.build.BlueprintSafety.payloadStacks(safe, registries);
        helper.assertTrue(priced.size() == 1 && priced.get(0).is(Items.SHULKER_BOX),
                "the frame's contents are one shulker box to pay for, got " + priced);
        helper.assertTrue(priced.get(0).get(
                        net.minecraft.core.component.DataComponents.CONTAINER) == null,
                "and it is an empty one — charge and placement must read the same stack");
        helper.succeed();
    }

    /**
     * 同一份任务派<b>两次</b>,第二次必须什么都不做——这是"重发同一个调用就是续建"这句
     * 承诺的唯一证据。
     *
     * <p>我们把这句话写进了工具描述、教给了模型:缺料就补料、然后<b>重发一模一样的调用</b>,
     * 已经立着的部分自动跳过。整条路上有四个地方会把它变成谎言:方块的幂等靠逐格拿世界
     * 当对照(这条最稳),而<b>摆设是实体</b>——没有"已经在那儿了吗"这一步,建完再发一次
     * 就多一份,同一面墙上两个展示框叠在一起;主动跳过的格若被算成待办,第二遍会去重放;
     * 双格方块的次半若还在目标集里,第二遍又会触发一次代建;精确料(带花纹的旗、框里那把
     * 剑)的比对若和第一遍不同源,第二遍会再收一次钱。
     *
     * <p>判据设计成<b>会花钱就会露馅</b>:生存同伴,每种料只给"够一遍 + 恰好多一件"。
     * 第二遍只要动了任何一格、生成了任何一只摆设,多出来那一件就会被扣掉;而如果它连
     * 多的那件都不够(比如重放了两格),任务会直接以缺料失败。第二份任务的
     * {@code placed()} 与 {@code broken()} 都必须是 0——不是"结果看起来一样",而是
     * <b>一次动作都没发生</b>。
     */
    @GameTest(template = "floor16", timeoutTicks = 6000, batch = "numen_blueprint")
    public static void blueprint_second_run_changes_nothing(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        writeSmallHouse(level, "fixture_twice");
        BlockPos anchor = helper.absolutePos(new BlockPos(4, 2, 4));
        var loaded = com.dwinovo.numen.core.blueprint.BlueprintStore.load(
                level, "fixture_twice", anchor, 0);
        helper.assertTrue(loaded.targets().size() == 4,
                "three stones and one bed foot — the bed head is built by its foot, got "
                        + loaded.targets().size());

        // 生存同伴:每种料给"够一遍 + 恰好多一件"。第二遍动一下就会吃掉多的那件。
        NumenPlayer companion = spawnAt(helper, "gametest_twice", new BlockPos(1, 2, 1), false);
        record Give(net.minecraft.world.item.Item item, int forOnePass) {}
        List<Give> supplies = List.of(
                new Give(Items.STONE, 3),
                new Give(Items.RED_BED, 1),
                new Give(Items.ITEM_FRAME, 1),
                new Give(Items.ARMOR_STAND, 1),
                new Give(Items.DIAMOND, 1));
        for (Give g : supplies) {
            companion.getInventory().add(new ItemStack(g.item(), g.forOnePass() + 1));
        }

        var ctx = TaskDispatch.ctx("gametest-twice-1", companion);
        var first = new BuildTaskRecord(ctx.toolCallId(), ctx.deadline(3000L), loaded.targets(),
                com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_EMPTY, true, true,
                loaded.blockEntityData(), loaded.entities());
        first.cellNeeds(loaded.cellNeeds());
        TaskDispatch.setTask(companion, first, null, reply -> {});

        var second = new BuildTaskRecord[1];
        net.minecraft.world.phys.AABB site = new net.minecraft.world.phys.AABB(
                anchor.getX() - 2, anchor.getY() - 2, anchor.getZ() - 2,
                anchor.getX() + 6, anchor.getY() + 4, anchor.getZ() + 6);

        helper.startSequence()
                // 第一遍:逐格对上,两只摆设都在
                .thenWaitUntil(() -> {
                    for (BuildTaskRecord.Target t : loaded.targets()) {
                        helper.assertTrue(t.matches(level.getBlockState(t.pos())),
                                "first run has not finished " + t.pos().toShortString()
                                        + " (want " + t.desiredState() + ")");
                    }
                    helper.assertTrue(level.getEntities(
                                    net.minecraft.world.entity.EntityType.ITEM_FRAME,
                                    site, e -> true).size() == 1,
                            "first run should hang exactly one item frame");
                    helper.assertTrue(level.getEntities(
                                    net.minecraft.world.entity.EntityType.ARMOR_STAND,
                                    site, e -> true).size() == 1,
                            "first run should place exactly one armour stand");
                })
                // 床头是床脚的落位回调造出来的,不是我们放的——它也得真的在
                .thenExecute(() -> helper.assertTrue(
                        level.getBlockState(anchor.offset(2, 0, 0)).is(Blocks.RED_BED),
                        "the bed head must exist even though it was never a target cell"))
                // 第一遍是真的在生存模式下逐格砌出来的,不是本来就在那儿
                .thenExecute(() -> helper.assertTrue(
                        first.placed() == loaded.targets().size(),
                        "the first run should have placed all " + loaded.targets().size()
                                + " cells itself, got " + first.placed()))
                // 派第二次:同一份目标集、同一份摆设
                .thenExecute(() -> {
                    var ctx2 = TaskDispatch.ctx("gametest-twice-2", companion);
                    second[0] = new BuildTaskRecord(ctx2.toolCallId(), ctx2.deadline(3000L),
                            loaded.targets(), com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_EMPTY,
                            true, true, loaded.blockEntityData(), loaded.entities());
                    second[0].cellNeeds(loaded.cellNeeds());
                    TaskDispatch.setTask(companion, second[0], null, reply -> {});
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    // 先证明第二份任务<b>真的跑了</b>。派发若被静默拒掉(比如上一个任务还
                    // 占着),下面那些 0 会全部成立而什么都没测到——那是最坏的一种绿。
                    helper.assertTrue(second[0].completed() == loaded.targets().size(),
                            "the second run must have actually executed and seen all "
                                    + loaded.targets().size() + " cells as already done, but its"
                                    + " completed count is " + second[0].completed()
                                    + " — if it is 0 the dispatch never happened and this whole"
                                    + " test proves nothing");
                    // 一次动作都没发生:不是"结果看起来一样"
                    helper.assertTrue(second[0].placed() == 0,
                            "the second run placed " + second[0].placed()
                                    + " cell(s); resending the same call must be a no-op");
                    helper.assertTrue(second[0].broken() == 0,
                            "the second run broke " + second[0].broken() + " block(s)");
                    // 摆设没多出来——这一条没有幂等检查的话必然翻倍
                    helper.assertTrue(level.getEntities(
                                    net.minecraft.world.entity.EntityType.ITEM_FRAME,
                                    site, e -> true).size() == 1,
                            "a second run must not hang a second item frame on the same wall");
                    helper.assertTrue(level.getEntities(
                                    net.minecraft.world.entity.EntityType.ARMOR_STAND,
                                    site, e -> true).size() == 1,
                            "a second run must not place a second armour stand");
                    // 多留的那一件料还在:第二遍一分钱没花
                    for (Give g : supplies) {
                        int left = 0;
                        for (int i = 0; i < 36; i++) {
                            ItemStack stack = companion.getInventory().getItem(i);
                            if (!stack.isEmpty() && stack.is(g.item())) {
                                left += stack.getCount();
                            }
                        }
                        helper.assertTrue(left == 1,
                                "one spare " + g.item() + " was set aside; the second run should"
                                        + " have spent nothing, but " + left + " remain");
                    }
                })
                .thenSucceed();
    }

    /**
     * <b>半途缺料 → 补料 → 重发同一个调用 → 从断点接上</b>。这是玩家真会走的那条路。
     *
     * <p>上一条用例测的是"全建完再重发",那时世界里每一格都已达标,判定简单。这一条难在
     * 中途停下的那个状态:世界里<b>一半达标一半没有</b>,而任务已经失败退出。第二次派发要
     * 从这个混合状态里正确地认出"哪些还欠着",而这条路上每个记账口径都会被考一遍——预检
     * 的报缺、逐格闸门、实扣、以及跳过格与待办格混在一起时的分母。
     *
     * <p>判据是<b>总账</b>:给的料 = 报价 + 每种各一件备用。两遍跑完之后,备用的那一件
     * 必须一件不少地还在——多扣一件说明重放了格子,少建一格说明续建漏了。中间还要断言
     * 第一遍<b>真的停在了半途</b>(建了但没建完),否则这条用例退化成上一条。
     *
     * <p>顺带压住摆设的数量:第一遍失败退出时它们不该生成(生成在收工那一步),
     * 而第二遍补上之后必须各只有一只。
     */
    @GameTest(template = "floor16", timeoutTicks = 12000, batch = "numen_blueprint")
    public static void blueprint_restock_and_resend_continues(GameTestHelper helper)
            throws Exception {
        ServerLevel level = helper.getLevel();
        writeSmallHouse(level, "fixture_partial");
        BlockPos anchor = helper.absolutePos(new BlockPos(4, 2, 4));
        var loaded = com.dwinovo.numen.core.blueprint.BlueprintStore.load(
                level, "fixture_partial", anchor, 0);

        NumenPlayer companion = spawnAt(helper, "gametest_restock", new BlockPos(1, 2, 1), false);
        // 第一批只给两块石头——够砌墙的一部分,床与摆设一件料都没有
        companion.getInventory().add(new ItemStack(Items.STONE, 2));

        var ctx = TaskDispatch.ctx("gametest-restock-1", companion);
        var first = new BuildTaskRecord(ctx.toolCallId(), ctx.deadline(6000L), loaded.targets(),
                com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_EMPTY, true, true,
                loaded.blockEntityData(), loaded.entities());
        first.cellNeeds(loaded.cellNeeds());
        // 注:dispatchAsync 的 reply 是<b>派发受理</b>回执("已受理,后台执行中"),不是
        // 最终结果——拿它当完工信号会立刻通过而什么都没等到。用进度本身当信号。
        TaskDispatch.setTask(companion, first, null, reply -> {});

        var second = new BuildTaskRecord[1];
        net.minecraft.world.phys.AABB site = new net.minecraft.world.phys.AABB(
                anchor.getX() - 2, anchor.getY() - 2, anchor.getZ() - 2,
                anchor.getX() + 6, anchor.getY() + 4, anchor.getZ() + 6);

        helper.startSequence()
                // 她把手上两块石头砌出去
                .thenWaitUntil(() -> helper.assertTrue(first.placed() >= 2,
                        "she should lay the two stones she has, placed=" + first.placed()))
                // 再等三个零进展遍走完(每遍之间有挪窝冷却),任务缺料失败退出
                .thenIdle(400)
                .thenExecute(() -> {
                    // 停在半途:砌了东西,但没砌完——否则这条用例退化成上一条
                    helper.assertTrue(first.placed() == 2,
                            "with two stones exactly two cells should be laid, got "
                                    + first.placed());
                    long done = loaded.targets().stream()
                            .filter(t -> t.matches(level.getBlockState(t.pos()))).count();
                    helper.assertTrue(done == 2,
                            "exactly two of the " + loaded.targets().size()
                                    + " cells should be standing, got " + done);
                    // 收工那一步没跑,所以摆设一只都还没有
                    helper.assertTrue(level.getEntities(
                                    net.minecraft.world.entity.EntityType.ITEM_FRAME,
                                    site, e -> true).isEmpty(),
                            "a run that ran out of materials must not have spawned fixtures yet");
                    helper.assertTrue(level.getEntities(
                                    net.minecraft.world.entity.EntityType.ARMOR_STAND,
                                    site, e -> true).isEmpty(),
                            "nor the armour stand");
                })
                // 补料:剩下的报价 + 每种各一件备用。重发一模一样的调用。
                .thenExecute(() -> {
                    companion.getInventory().add(new ItemStack(Items.STONE, 1 + 1));
                    companion.getInventory().add(new ItemStack(Items.RED_BED, 1 + 1));
                    companion.getInventory().add(new ItemStack(Items.ITEM_FRAME, 1 + 1));
                    companion.getInventory().add(new ItemStack(Items.ARMOR_STAND, 1 + 1));
                    companion.getInventory().add(new ItemStack(Items.DIAMOND, 1 + 1));
                    var ctx2 = TaskDispatch.ctx("gametest-restock-2", companion);
                    second[0] = new BuildTaskRecord(ctx2.toolCallId(), ctx2.deadline(6000L),
                            loaded.targets(), com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_EMPTY,
                            true, true, loaded.blockEntityData(), loaded.entities());
                    second[0].cellNeeds(loaded.cellNeeds());
                    TaskDispatch.setTask(companion, second[0], null, reply -> {});
                })
                // 第二遍把剩下的补齐
                .thenWaitUntil(() -> {
                    for (BuildTaskRecord.Target t : loaded.targets()) {
                        helper.assertTrue(t.matches(level.getBlockState(t.pos())),
                                "restocked run has not finished " + t.pos().toShortString());
                    }
                    helper.assertTrue(level.getEntities(
                                    net.minecraft.world.entity.EntityType.ITEM_FRAME,
                                    site, e -> true).size() == 1,
                            "the restocked run should hang the item frame");
                    helper.assertTrue(level.getEntities(
                                    net.minecraft.world.entity.EntityType.ARMOR_STAND,
                                    site, e -> true).size() == 1,
                            "the restocked run should place the armour stand");
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    // 第二遍只补了欠的那两格,没重放已经立着的
                    helper.assertTrue(second[0].placed() == 2,
                            "the restocked run should only owe two cells, but it placed "
                                    + second[0].placed() + " — it re-laid work that was already up");
                    helper.assertTrue(second[0].broken() == 0,
                            "and it should not have broken anything, got " + second[0].broken());
                    // 床头由床脚代建,从来不是目标格
                    helper.assertTrue(level.getBlockState(anchor.offset(2, 0, 0)).is(Blocks.RED_BED),
                            "the bed head must be there, built by its foot");
                    // 总账:每种料的备用那一件必须一件不少
                    for (net.minecraft.world.item.Item item : List.of(
                            Items.STONE, Items.RED_BED, Items.ITEM_FRAME,
                            Items.ARMOR_STAND, Items.DIAMOND)) {
                        int left = 0;
                        for (int i = 0; i < 36; i++) {
                            ItemStack stack = companion.getInventory().getItem(i);
                            if (!stack.isEmpty() && stack.is(item)) {
                                left += stack.getCount();
                            }
                        }
                        helper.assertTrue(left == 1,
                                "across both runs the total spent must equal the quote: one spare "
                                        + item + " was set aside but " + left + " remain");
                    }
                })
                .thenSucceed();
    }

    /**
     * 报价数背包和施工数背包必须是<b>同一个口径</b>——副手上那叠料不算数。
     *
     * <p>这个分岔犯过两次,症状一模一样:一整叠木板放在副手,数 41 格(含盔甲栏与副手)
     * 的那一方说"料够了",数 36 格的那一方每格都判缺料。第一次是在开工预检与逐格闸门
     * 之间,第二次是在 {@code blueprint_read} 的报价与施工之间——玩家看着手里那叠木板,
     * 而我们两张嘴说两样话,他没法判断该信谁。
     *
     * <p>所以口径定义在一个地方({@code PlayerInv.BUILDABLE_SLOTS}),两边都调它。这条
     * 用例钉的就是"同源"本身:同一个背包状态,两个函数必须给同一个数。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void quote_and_gate_count_the_same_slots(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_offhand", new BlockPos(1, 2, 1), false);
        var inv = companion.getInventory();

        // 主背包里 5 块,副手里 64 块
        inv.add(new ItemStack(Items.SPRUCE_PLANKS, 5));
        inv.offhand.set(0, new ItemStack(Items.SPRUCE_PLANKS, 64));

        int whole = com.dwinovo.numen.core.PlayerInv.count(inv, Items.SPRUCE_PLANKS);
        int buildable = com.dwinovo.numen.core.PlayerInv
                .buildableCount(inv, Items.SPRUCE_PLANKS);

        helper.assertTrue(whole == 69,
                "the whole-inventory count should see the offhand stack too, got " + whole);
        helper.assertTrue(buildable == 5,
                "but building may only spend the 36 main slots — she does not strip her own"
                        + " offhand to lay bricks; got " + buildable);
        helper.assertTrue(whole != buildable,
                "if these two ever agree this test has stopped proving anything —"
                        + " the offhand stack must actually be in play");
        helper.succeed();
    }

    /**
     * 干着干着断料,要<b>当场</b>认账,不是溜达一圈再说。
     *
     * <p>判据从过程量换成状态量之前,这里是这样的:判"干不下去了"用的是"一遍走完没有
     * 进展",而那是个过程量,时间分辨率就是一遍。于是断料之后她要把剩下的层一层层空翻
     * 过去(每层 18 刻的演出停顿)、收遍再挪窝等 60 刻,还要连着三遍才认账——一栋剩二十层
     * 的房子就是一分钟。而"她付不起剩下任何一格"这个结论,在第一次付不起的那一刻就已经
     * 成立了。
     *
     * <p>这条用例把两件事一起钉住:失败要快(给一个远大于"当场"、又远小于旧路径的刻数
     * 上限),以及失败的理由要对(NO_MATERIAL,而不是被拖成超时或"她站不住")。
     */
    @GameTest(template = "floor16", timeoutTicks = 2000, batch = "numen_blueprint")
    public static void running_out_of_materials_is_reported_at_once(GameTestHelper helper)
            throws Exception {
        ServerLevel level = helper.getLevel();
        writeSmallHouse(level, "fixture_starve");
        BlockPos anchor = helper.absolutePos(new BlockPos(4, 2, 4));
        var loaded = com.dwinovo.numen.core.blueprint.BlueprintStore.load(
                level, "fixture_starve", anchor, 0);

        NumenPlayer companion = spawnAt(helper, "gametest_starve", new BlockPos(1, 2, 1), false);
        // 两块石头:够砌两格,然后就彻底断了(床、展示框、盔甲架一件料都没有)
        companion.getInventory().add(new ItemStack(Items.STONE, 2));

        var ctx = TaskDispatch.ctx("gametest-starve", companion);
        var rec = new BuildTaskRecord(ctx.toolCallId(), ctx.deadline(6000L), loaded.targets(),
                com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_EMPTY, true, true,
                loaded.blockEntityData(), loaded.entities());
        rec.cellNeeds(loaded.cellNeeds());
        TaskDispatch.setTask(companion, rec, null, reply -> {});

        long[] startTick = {level.getGameTime()};
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(rec.placed() >= 2,
                        "she should lay the two stones she has, placed=" + rec.placed()))
                .thenExecute(() -> startTick[0] = level.getGameTime())
                .thenWaitUntil(() -> helper.assertTrue(
                        rec.getState() == com.dwinovo.numen.task.TaskState.FAILED,
                        "the task should have given up by now, state=" + rec.getState()))
                .thenExecute(() -> {
                    long spent = level.getGameTime() - startTick[0];
                    // 旧路径是 (剩余层数×18 + 60) × 3;这栋只剩两层也要 200+ 刻,
                    // 而当场认账是个位数。给 120 刻的上限:远松于"当场",远严于旧路径。
                    helper.assertTrue(spent <= 120,
                            "running out should be reported at once, but it took " + spent
                                    + " ticks after the last placeable cell — she was wandering");
                    // 理由要对:是"料没了",不是被拖成超时、也不是"她站不住"
                    String said = rec.getResult() == null ? "" : rec.getResult().message();
                    helper.assertTrue(said.contains("ran out") && said.contains("still needs"),
                            "the reason must be materials and must say what to gather, got: "
                                    + said);
                })
                .thenSucceed();
    }

    /**
     * 续建那两条用例共用的小图纸:三块石头一面墙 + 一张朝北的床(两半都在文件里) +
     * 一个挂在墙上的展示框(框里一颗钻石) + 一个盔甲架。
     *
     * <p>特意把四类难处凑在四格里:双格方块的次半、按组件全等收的载荷、要依托墙面的挂件、
     * 以及要计料的躯壳。报价是 3 石 + 1 床 + 1 展示框 + 1 盔甲架 + 1 钻石。
     */
    private static void writeSmallHouse(ServerLevel level, String name) throws Exception {
        BlockState bedFoot = Blocks.RED_BED.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART,
                        net.minecraft.world.level.block.state.properties.BedPart.FOOT)
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties
                        .HORIZONTAL_FACING, net.minecraft.core.Direction.NORTH);
        BlockState bedHead = bedFoot.setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART,
                net.minecraft.world.level.block.state.properties.BedPart.HEAD);

        var root = new net.minecraft.nbt.CompoundTag();
        var size = new net.minecraft.nbt.ListTag();
        size.add(net.minecraft.nbt.IntTag.valueOf(4));
        size.add(net.minecraft.nbt.IntTag.valueOf(2));
        size.add(net.minecraft.nbt.IntTag.valueOf(4));
        root.put("size", size);
        var palette = new net.minecraft.nbt.ListTag();
        for (BlockState s : List.of(Blocks.STONE.defaultBlockState(), bedHead, bedFoot)) {
            palette.add(net.minecraft.nbt.NbtUtils.writeBlockState(s));
        }
        root.put("palette", palette);
        var blocks = new net.minecraft.nbt.ListTag();
        blocks.add(cellTag(0, 0, 0, 0));
        blocks.add(cellTag(0, 0, 1, 0));
        blocks.add(cellTag(0, 0, 2, 0));   // 挂展示框的那面墙
        blocks.add(cellTag(2, 0, 0, 1));   // 床头(朝北,z 更小)——加载期该被剔掉
        blocks.add(cellTag(2, 0, 1, 2));   // 床脚
        root.put("blocks", blocks);

        // 展示框挂在 (0,0,2) 那块石头的南面,框里一颗钻石;盔甲架立在院里
        var frameNbt = new net.minecraft.nbt.CompoundTag();
        frameNbt.putString("id", "minecraft:item_frame");
        frameNbt.putByte("Facing", (byte) net.minecraft.core.Direction.SOUTH.get3DDataValue());
        var held = new net.minecraft.nbt.CompoundTag();
        held.putString("id", "minecraft:diamond");
        held.putInt("count", 1);
        frameNbt.put("Item", held);
        var standNbt = new net.minecraft.nbt.CompoundTag();
        standNbt.putString("id", "minecraft:armor_stand");
        var entities = new net.minecraft.nbt.ListTag();
        entities.add(entityTag(0.5, 0.5, 3.5, frameNbt));
        entities.add(entityTag(3.5, 0.0, 3.5, standNbt));
        root.put("entities", entities);
        net.minecraft.nbt.NbtIo.writeCompressed(root,
                com.dwinovo.numen.core.blueprint.BlueprintStore.dir(level.getServer())
                        .resolve(name + ".nbt"));
    }

    /**
     * 坏掉的图纸要<b>干净地报错</b>,不能崩、也不能把服务端冻住。
     *
     * <p>图纸是玩家目录里的文件:可以手改、可以从网上下载、可以下到一半断线。解码器面对
     * 的是不可信输入,而它跑在服务端主线程上。两类事故各防一条:
     * <ul>
     *   <li><b>越界</b>——调色板下标指向不存在的项。裸下标会抛数组越界,一路冒到工具调用
     *       之外。改成跳过那一格并计入掉格。</li>
     *   <li><b>冻死</b>——区域尺寸直接来自文件。声明 100000³ 的话遍历要转上万亿次,服务端
     *       不是崩而是<b>整个没反应</b>,而那比崩更难查:没有崩溃报告,只有"服务器卡住了"。
     *       所以遍历有独立于格数的体积上限。</li>
     * </ul>
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_blueprint")
    public static void corrupt_blueprints_fail_cleanly(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        var dir = com.dwinovo.numen.core.blueprint.BlueprintStore.dir(level.getServer());
        BlockPos anchor = helper.absolutePos(new BlockPos(2, 2, 2));

        // 一、调色板下标越界:三格里有两格指向不存在的调色板项
        var root = new net.minecraft.nbt.CompoundTag();
        var size = new net.minecraft.nbt.ListTag();
        for (int n : new int[]{3, 1, 3}) {
            size.add(net.minecraft.nbt.IntTag.valueOf(n));
        }
        root.put("size", size);
        var palette = new net.minecraft.nbt.ListTag();
        palette.add(net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        root.put("palette", palette);
        var blocks = new net.minecraft.nbt.ListTag();
        blocks.add(cellTag(0, 0, 0, 0));      // 好的
        blocks.add(cellTag(1, 0, 0, 7));      // 越界
        blocks.add(cellTag(2, 0, 0, -1));     // 负下标
        root.put("blocks", blocks);
        net.minecraft.nbt.NbtIo.writeCompressed(root, dir.resolve("fixture_badindex.nbt"));

        var loaded = com.dwinovo.numen.core.blueprint.BlueprintStore.load(
                level, "fixture_badindex", anchor, 0);
        helper.assertTrue(loaded.targets().size() == 1,
                "the one good cell should survive, got " + loaded.targets().size());
        helper.assertTrue(loaded.dropped() == 2,
                "and the two broken ones must be counted as dropped, got " + loaded.dropped());

        // 二、体积炸弹:一个声明得离谱的 litematic 区域
        var lite = new net.minecraft.nbt.CompoundTag();
        var regions = new net.minecraft.nbt.CompoundTag();
        var region = new net.minecraft.nbt.CompoundTag();
        var pos = new net.minecraft.nbt.CompoundTag();
        pos.putInt("x", 0);
        pos.putInt("y", 0);
        pos.putInt("z", 0);
        region.put("Position", pos);
        var rsize = new net.minecraft.nbt.CompoundTag();
        rsize.putInt("x", 100000);
        rsize.putInt("y", 100000);
        rsize.putInt("z", 100000);
        region.put("Size", rsize);
        var rpal = new net.minecraft.nbt.ListTag();
        var airEntry = new net.minecraft.nbt.CompoundTag();
        airEntry.putString("Name", "minecraft:air");
        rpal.add(airEntry);
        region.put("BlockStatePalette", rpal);
        region.putLongArray("BlockStates", new long[]{0L});
        regions.put("bomb", region);
        lite.put("Regions", regions);
        net.minecraft.nbt.NbtIo.writeCompressed(lite, dir.resolve("fixture_bomb.litematic"));

        boolean refused = false;
        try {
            com.dwinovo.numen.core.blueprint.BlueprintStore.load(
                    level, "fixture_bomb", anchor, 0);
        } catch (IllegalArgumentException e) {
            // 判据是"说人话地拒绝",不是"没有卡住"——后者测不出来(卡住就是超时)
            refused = e.getMessage() != null && e.getMessage().contains("corrupt");
        }
        helper.assertTrue(refused,
                "a region declaring 100000^3 must be refused with a readable reason, not walked");
        helper.succeed();
    }

    /** 结构 NBT 的一只实体:{pos:[x,y,z], blockPos:[..], nbt:{...}}。 */
    private static net.minecraft.nbt.CompoundTag entityTag(double x, double y, double z,
                                                           net.minecraft.nbt.CompoundTag nbt) {
        var out = new net.minecraft.nbt.CompoundTag();
        var pos = new net.minecraft.nbt.ListTag();
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(x));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(y));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(z));
        out.put("pos", pos);
        var block = new net.minecraft.nbt.ListTag();
        block.add(net.minecraft.nbt.IntTag.valueOf((int) Math.floor(x)));
        block.add(net.minecraft.nbt.IntTag.valueOf((int) Math.floor(y)));
        block.add(net.minecraft.nbt.IntTag.valueOf((int) Math.floor(z)));
        out.put("blockPos", block);
        out.put("nbt", nbt);
        return out;
    }

    /** 结构 NBT 的一格:{pos:[x,y,z], state:i}。 */
    private static net.minecraft.nbt.CompoundTag cellTag(int x, int y, int z, int state) {
        var cell = new net.minecraft.nbt.CompoundTag();
        var pos = new net.minecraft.nbt.ListTag();
        pos.add(net.minecraft.nbt.IntTag.valueOf(x));
        pos.add(net.minecraft.nbt.IntTag.valueOf(y));
        pos.add(net.minecraft.nbt.IntTag.valueOf(z));
        cell.put("pos", pos);
        cell.putInt("state", state);
        return cell;
    }

    /**
     * skill 文档里点名的每一个方块都必须真的存在。
     *
     * <p>文档是<b>喂给模型的词汇表</b>:写错一个名字,模型就会照着吐一个无效的
     * block_id,而那一格不会报错、只会缺一块。四十份风格文件、上百个方块名从来
     * 没有人验过,而改一次屋顶用料就顺手把十四份文件里的 {@code _stairs} 换成了
     * {@code _slab}——{@code stone_bricks → stone_brick_slab} 这种去复数的名字
     * 靠手改必然漏。
     *
     * <p>判据是注册表本身,不另立一份清单:反引号里凡是带下划线的小写词,要么在
     * 方块/物品注册表里查得到,要么是我们自己的词汇(op 名、参数名、方块状态键、
     * 形制名)。两边都不是就是错字。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_build")
    public static void skill_docs_name_real_blocks(GameTestHelper helper) {
        // 我们自己的词汇:工具、op、参数、状态键、形制名。它们和方块名共用反引号,
        // 但不该去注册表里找。
        java.util.Set<String> ours = java.util.Set.of(
                "block_id", "legend", "rows", "mask", "carve", "overwrite", "solid", "keep",
                "rotation", "mirror", "include_air", "hollow", "load_skill", "task_status",
                "task_finished", "building_design", "blueprint_read",
                "short_grass", "dirt_path", "coarse_dirt", "flower_pot", "decorated_pot",
                "x1", "y1", "z1", "x2", "y2", "z2");
        java.util.List<String> bad = new java.util.ArrayList<>();
        int checked = 0;
        try {
            var url = BuildGameTests.class.getClassLoader()
                    .getResource("skills/building_design/SKILL.md");
            helper.assertTrue(url != null, "skill resources are not on the classpath");
            java.nio.file.Path root = java.nio.file.Path.of(url.toURI()).getParent();
            java.util.List<java.nio.file.Path> docs;
            try (var walk = java.nio.file.Files.walk(root)) {
                docs = walk.filter(p -> p.toString().endsWith(".md")).toList();
            }
            helper.assertTrue(docs.size() >= 40,
                    "expected the style reference set, found only " + docs.size() + " doc(s)");
            // 风格文件名也在反引号里(正文那份索引),它们是文档名不是方块名。
            // 用实际存在的文件当判据,顺带把索引里指向不存在文件的错字也一起抓了。
            java.util.Set<String> docNames = new java.util.HashSet<>();
            for (java.nio.file.Path doc : docs) {
                String n = doc.getFileName().toString();
                docNames.add(n.substring(0, n.length() - 3));
            }
            var token = java.util.regex.Pattern.compile("`([^`]+)`");
            var ident = java.util.regex.Pattern.compile("[a-z][a-z0-9_]*");
            for (java.nio.file.Path doc : docs) {
                java.util.List<String> words = new java.util.ArrayList<>();
                // 反引号里的(SKILL.md 用这种写法)
                var m = token.matcher(java.nio.file.Files.readString(doc));
                while (m.find()) {
                    String body = m.group(1);
                    if (body.indexOf('*') >= 0) {
                        continue;   // 通配写法(stripped_*_log)与调色权重(oak_slab*5)
                    }
                    // 方块状态跟在方块名后面(`oak_slab[type=double]`):状态键与值是原版
                    // 的词汇,由原版解析器把关,这条 lint 只管方块名对不对
                    if (body.indexOf('[') >= 0) {
                        body = body.substring(0, body.indexOf('['));
                    }
                    var w = ident.matcher(body);
                    while (w.find()) {
                        words.add(w.group());
                    }
                }
                // 材料槽的裸列(风格文件用这种写法):`- **roof**: a, b, c — 说明`。
                // 只收"整块就是一个词"的项,带空格的是散文不是方块名。
                for (String line : java.nio.file.Files.readAllLines(doc)) {
                    if (!line.startsWith("- **") || !line.contains("**:")) {
                        continue;
                    }
                    String list = line.substring(line.indexOf("**:") + 3);
                    for (String sep : new String[]{"—", ";", "("}) {
                        int cut = list.indexOf(sep);
                        if (cut >= 0) {
                            list = list.substring(0, cut);
                        }
                    }
                    for (String chunk : list.split("[,/]")) {
                        String t = chunk.trim();
                        if (!t.isEmpty() && t.indexOf(' ') < 0) {
                            words.add(t);
                        }
                    }
                }
                for (String word : words) {
                    if (word.indexOf('_') < 0 || ours.contains(word) || docNames.contains(word)) {
                        continue;
                    }
                    checked++;
                    var id = net.minecraft.resources.ResourceLocation.tryParse("minecraft:" + word);
                    boolean known = id != null
                            && (net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id)
                            || net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id));
                    if (!known) {
                        bad.add(doc.getFileName() + ": " + word);
                    }
                }
            }
        } catch (Exception e) {
            throw new AssertionError("could not lint the skill docs: " + e, e);
        }
        helper.assertTrue(bad.isEmpty(), "skill docs name " + bad.size()
                + " block(s) that do not exist: " + bad);
        helper.assertTrue(checked > 200,
                "the lint matched only " + checked + " block names — the extractor is broken");
        helper.succeed();
    }

    /**
     * 读图纸:尺寸、用料、按层分布,不动世界一格;而且<b>清点口径必须与实扣口径
     * 一致</b>。
     *
     * <p>双格方块在图纸里是上下两格、各带一个同样的物品。清单若逐格计费,一扇门
     * 就报成两扇门的料——玩家按清单备齐了,建到一半照样停下。两处判据同源
     * ({@code Target.costsMaterial}),这条用例就是那份同源的证据。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_blueprint")
    public static void blueprint_read_matches_what_gets_spent(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        copyCottageFixture(level);
        BlockPos anchor = helper.absolutePos(new BlockPos(0, 2, 0));
        var loaded = com.dwinovo.numen.core.blueprint.BlueprintStore.load(
                level, "japanese_cottage", anchor, 0);

        // 清点:与实扣共用同一个件数函数。一格不是恒定一件,而双格方块一件也不是
        // "两格各一件"——这栋图纸里两种都有,所以两边都要真的被走到,否则这条测试
        // 只是在测"1 == 1"。
        java.util.Map<net.minecraft.world.item.Item, Integer> quoted = new java.util.LinkedHashMap<>();
        int multi = 0;
        int beds = 0;
        for (BuildTaskRecord.Target t : loaded.targets()) {
            if (t.desiredState().is(Blocks.RED_BED)) {
                beds++;
            }
            int n = t.materialCount();
            if (n == 0) {
                continue;
            }
            if (n > 1) {
                multi++;
            }
            quoted.merge(t.item(), n, Integer::sum);
        }
        int quotedItems = quoted.values().stream().mapToInt(Integer::intValue).sum();
        helper.assertTrue(quotedItems > 0, "quote came out empty");
        helper.assertTrue(multi > 0,
                "this blueprint has double slabs — a cell that is two slabs must be quoted as two");
        // 双格方块一件料一张床:次半根本不在目标集里,所以格数与件数天然相等。
        // 此前是"两半都在集里、次半记 0 件"凑出来的,那条路上床头会先落位,而床的
        // 落位回调会往目标集之外再写一块床头——一件料换三块床方块。
        helper.assertTrue(beds > 0, "this cottage has beds; the fixture must still contain them");
        helper.assertTrue(quoted.get(Items.RED_BED) != null && quoted.get(Items.RED_BED) == beds,
                "one bed item per bed: " + beds + " bed cell(s) but "
                        + quoted.get(Items.RED_BED) + " item(s) quoted");
        helper.assertTrue(loaded.targets().stream().noneMatch(t -> com.dwinovo.numen.core.build
                        .BuildStates.isSecondaryHalf(t.desiredState())),
                "no secondary half belongs in the target set");

        // 按层分布:必须逐层可分辨,否则"去掉二楼"这类要求无从下手
        java.util.Map<Integer, Integer> byLayer = new java.util.TreeMap<>();
        int baseY = loaded.targets().stream().mapToInt(t -> t.pos().getY()).min().orElse(0);
        for (BuildTaskRecord.Target t : loaded.targets()) {
            byLayer.merge(t.pos().getY() - baseY, 1, Integer::sum);
        }
        helper.assertTrue(byLayer.size() >= 20,
                "layer profile should resolve every storey of a 23-tall blueprint, got " + byLayer.size());
        helper.assertTrue(byLayer.get(0) != null && byLayer.get(0) > 1000,
                "the ground layer of this cottage is a full footprint slab; got " + byLayer.get(0));
        helper.succeed();
    }

    /**
     * 施工时限必须够用完。
     *
     * <p>时限一度是按"每格固定几刻"估的,而生存最慢档实际是每格十刻——差二十倍,
     * 五百格的房子会在盖到一半时被判超时,而一千四百格以下走的都是这个下限速率,
     * 也就是大多数房子。派发层与施工层<b>必须共用同一个速率公式</b>,各拍各的
     * 就会重演。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void build_deadline_covers_pace(GameTestHelper helper) {
        for (int cells : new int[]{50, 500, 1440, 5859, 16384}) {
            for (boolean survival : new boolean[]{true, false}) {
                long need = com.dwinovo.numen.core.task.build.BuildOrder
                        .estimatedTicks(cells, survival);
                long budget = com.dwinovo.numen.core.tools.work.BuildTool.timeoutTicksFor(cells, survival);
                helper.assertTrue(budget > need,
                        "deadline must exceed the build itself: " + cells + " cells, survival="
                                + survival + ", needs " + need + " ticks but budget is " + budget);
            }
        }
        // 生存封顶:再大的工程也收敛到目标时长,不会无限拉长
        long huge = com.dwinovo.numen.core.task.build.BuildOrder.estimatedTicks(16384, true);
        helper.assertTrue(huge <= 12 * 60 * 20 + 20,
                "survival pace should cap total duration at the target, got " + huge + " ticks");
        helper.succeed();
    }

    /**
     * 一栋小屋,从工具入口按模型的样子下单:地基、墙、双坡屋顶、门窗细节全用通用原语
     * 写成——一张字符网格是一层,楼梯屋顶是逐课的网格,细节是单格。
     *
     * <p>这条用例是新原语集的整活钉桩:网格带图例、网格按层重复、方块状态跟在方块名里
     * ({@code oak_stairs[facing=south]})、同一次调用里后写覆盖先写,四件事缺一条都盖不出
     * 这栋屋子。
     */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_build_cottage")
    public static void build_medieval_cottage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spawn = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                "gametest_carpenter", UUID.randomUUID(), level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        // 这条量的是原语能不能盖出一栋屋子,不是生存备料:免耗材档
        companion.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        // 发脚手架:垫柱残料由交付前的清扫遍拆除,门洞可通行断言就是它的回归测试
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));

        BlockPos o = helper.absolutePos(new BlockPos(4, 2, 5));   // 地基西北角
        int x = o.getX();
        int y = o.getY();
        int z = o.getZ();
        // 12 x 10 的占地:地基一层实心,墙圈三层,屋顶四课
        List<String> solid = List.of(
                "############", "############", "############", "############", "############",
                "############", "############", "############", "############", "############");
        List<String> ring = List.of(
                "############", "#..........#", "#..........#", "#..........#", "#..........#",
                "#..........#", "#..........#", "#..........#", "#..........#", "############");
        ToolRun build = call(companion, "build", args("ops", List.of(
                args("op", "layer", "x1", x, "y1", y, "z1", z,
                        "rows", solid, "block_id", "minecraft:cobblestone"),
                args("op", "layer", "x1", x, "y1", y + 1, "z1", z, "y2", y + 3,
                        "rows", ring, "block_id", "minecraft:oak_planks"),
                // 屋顶:每课一张网格,两侧楼梯对着爬,顶上一条半砖压脊
                args("op", "layer", "x1", x, "y1", y + 4, "z1", z, "rows", List.of(
                        "<<<<<<<<<<<<", "............", "............", "............",
                        "............", "............", "............", "............",
                        "............", ">>>>>>>>>>>>"),
                        "legend", args("<", "minecraft:oak_stairs[facing=south]",
                                ">", "minecraft:oak_stairs[facing=north]")),
                args("op", "layer", "x1", x, "y1", y + 5, "z1", z, "rows", List.of(
                        "............", "<<<<<<<<<<<<", "............", "............",
                        "............", "............", "............", "............",
                        ">>>>>>>>>>>>", "............"),
                        "legend", args("<", "minecraft:oak_stairs[facing=south]",
                                ">", "minecraft:oak_stairs[facing=north]")),
                args("op", "layer", "x1", x, "y1", y + 6, "z1", z, "rows", List.of(
                        "............", "............", "<<<<<<<<<<<<", "............",
                        "............", "............", "............", ">>>>>>>>>>>>",
                        "............", "............"),
                        "legend", args("<", "minecraft:oak_stairs[facing=south]",
                                ">", "minecraft:oak_stairs[facing=north]")),
                args("op", "layer", "x1", x, "y1", y + 7, "z1", z, "rows", List.of(
                        "............", "............", "............", "============",
                        "============", "============", "============", "............",
                        "............", "............"),
                        "legend", args("=", "minecraft:oak_slab")),
                // 细节:四角原木柱(状态跟在方块名里)、南面门洞、玻璃窗、屋内火把
                args("op", "line", "x1", x, "y1", y + 1, "z1", z, "x2", x, "y2", y + 3, "z2", z,
                        "block_id", "minecraft:oak_log[axis=y]"),
                args("op", "line", "x1", x + 11, "y1", y + 1, "z1", z, "x2", x + 11, "y2", y + 3, "z2", z,
                        "block_id", "minecraft:oak_log[axis=y]"),
                args("op", "line", "x1", x, "y1", y + 1, "z1", z + 9, "x2", x, "y2", y + 3, "z2", z + 9,
                        "block_id", "minecraft:oak_log[axis=y]"),
                args("op", "line", "x1", x + 11, "y1", y + 1, "z1", z + 9,
                        "x2", x + 11, "y2", y + 3, "z2", z + 9, "block_id", "minecraft:oak_log[axis=y]"),
                args("op", "layer", "x1", x + 5, "y1", y + 1, "z1", z, "y2", y + 2,
                        "rows", List.of("##"), "block_id", "minecraft:air"),
                args("op", "set", "x", x + 2, "y", y + 2, "z", z, "block_id", "minecraft:glass_pane"),
                args("op", "set", "x", x + 9, "y", y + 2, "z", z, "block_id", "minecraft:glass_pane"),
                args("op", "set", "x", x + 5, "y", y + 1, "z", z + 4, "block_id", "minecraft:torch"))));

        helper.succeedWhen(() -> {
            helper.assertTrue(build.done(), "build has not finished");
            helper.assertTrue(build.succeeded(), "the cottage was not finished: " + build.outcome());
            helper.assertTrue(level.getBlockState(o).is(Blocks.COBBLESTONE), "no foundation");
            helper.assertTrue(level.getBlockState(o.offset(0, 1, 0)).is(Blocks.OAK_LOG), "no corner post");
            helper.assertTrue(level.getBlockState(o.offset(3, 1, 0)).is(Blocks.OAK_PLANKS), "no wall");
            var eave = level.getBlockState(o.offset(3, 4, 0));
            helper.assertTrue(eave.is(Blocks.OAK_STAIRS)
                            && eave.getValue(net.minecraft.world.level.block.StairBlock.FACING)
                            == net.minecraft.core.Direction.SOUTH,
                    "the eave course is not south-facing stairs: " + eave);
            helper.assertTrue(level.getBlockState(o.offset(3, 7, 3)).is(Blocks.OAK_SLAB), "no ridge");
            // 可通行断言:门洞两格为空——守则"门是走进去的"
            for (BlockPos at : List.of(o.offset(5, 1, 0), o.offset(5, 2, 0),
                    o.offset(6, 1, 0), o.offset(6, 2, 0))) {
                helper.assertTrue(level.getBlockState(at).isAir(),
                        "doorway blocked at " + at.toShortString());
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 蓝图批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_blueprint")
    public static void prepareBlueprintBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 重型建造批次前置(与轻型批分开,别让六个建造者同时抢搜索池——
     *  生产环境是 20tps 一两个同伴,测试没必要用数量级更苛的并发打自己)。 */
    @BeforeBatch(batch = "numen_build_heavy")
    public static void prepareHeavyBuildBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 经典蓝图:运行时从原版资源里取雪屋顶屋(igloo/top,7x5x8——雪墙、冰窗、
     * 木门、床、火把、熔炉、工作台俱全),写成 schematics 目录下的 .nbt,
     * 再经 BlueprintStore 展开成建造任务。覆盖 .nbt 读取、精确状态落位(门/床双格、
     * 火把贴附)、骨架先行贴附后置的阶段序,以及免材料模式。
     */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_blueprint")
    public static void blueprint_igloo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        try {
            var template = server.getStructureManager()
                    .get(net.minecraft.resources.ResourceLocation.parse("minecraft:igloo/top")).orElseThrow();
            var tag = template.save(new net.minecraft.nbt.CompoundTag());
            java.nio.file.Path dir = com.dwinovo.numen.core.blueprint.BlueprintStore.dir(server);
            net.minecraft.nbt.NbtIo.writeCompressed(tag, dir.resolve("igloo_top.nbt"));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }

        BlockPos spawn = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                "gametest_architect", UUID.randomUUID(), level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        // 蓝图免材料只是不消耗;寻路的脚手架(垫柱上穹顶)是真实放置,得有料
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));

        BlockPos anchorPos = helper.absolutePos(new BlockPos(7, 2, 7));
        var loaded = com.dwinovo.numen.core.blueprint.BlueprintStore.load(level, "igloo_top", anchorPos, 0);
        var ctx = TaskDispatch.ctx("gametest-blueprint", companion);
        long deadline = ctx.deadline(Math.max(2400L, loaded.targets().size() * 400L));
        TaskDispatch.setTask(companion, new BuildTaskRecord(ctx.toolCallId(), deadline,
                loaded.targets(), com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_EMPTY, false), null, reply -> {});

        helper.succeedWhen(() -> {
            for (BuildTaskRecord.Target target : loaded.targets()) {
                helper.assertTrue(target.matches(level.getBlockState(target.pos())),
                        "blueprint cell mismatch at " + target.pos().toShortString()
                                + " want " + target.desiredState());
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 单块悬置:目标在头部高度、上方为空——真实世界曾整任务卡死的最小场景
     *  (站在旁边就该侧身放上,不接受任何"找不到角度")。 */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_build")
    public static void build_single_block(GameTestHelper helper) {
        runBuildCase(helper, "gametest_handyman",
                List.of(new BlockPos(6, 3, 6)), 1);
    }

    /** 实心 5x5x5(125 格):逐层实心浇筑,身体要在自己刚铺的层面上走位。 */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_build_heavy")
    public static void build_solid_cube(GameTestHelper helper) {
        runBuildCase(helper, "gametest_mason",
                boxCells(new BlockPos(7, 2, 7), 5, 5, 5, false), 4);
    }

    /** 一堵 10x4 的墙(40 格):长条高结构,沿线往返 + 够高处的格子。 */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_build")
    public static void build_wall(GameTestHelper helper) {
        runBuildCase(helper, "gametest_waller",
                boxCells(new BlockPos(5, 2, 10), 10, 4, 1, false), 2);
    }

    /** 2x2x8 高塔(32 格):细高结构,自体脚手架式攀升,收尾要从塔顶回地面。 */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_build")
    public static void build_pillar(GameTestHelper helper) {
        runBuildCase(helper, "gametest_towerer",
                boxCells(new BlockPos(9, 2, 9), 2, 8, 2, false), 2);
    }

    /** 9x9 平台(81 格):纯水平铺面,不触发分层,考横向站位与边铺边退。 */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_build")
    public static void build_platform(GameTestHelper helper) {
        runBuildCase(helper, "gametest_paver",
                boxCells(new BlockPos(5, 2, 5), 9, 1, 9, false), 3);
    }

    /** 建造批次(重):创造同伴照真实社区图纸把整栋日式小屋盖出来。 */
    @BeforeBatch(batch = "numen_cottage_jp")
    public static void prepareJpCottageBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 终极实战:创造同伴(免材料+自动脚手架)把 5859 格的日式小屋从图纸
     * 盖到世界里,逐格对账(液体格已被管线跳过,不在目标集内)。
     *
     * <p>整栋房子的验收条件是逐格全中,不是"盖了大半"。它同时压着施工模型的
     * 三个要害:低层先行的顺序、支撑还没长出来时的分遍推迟、以及她自己站过的
     * 格子最终也得补上。
     */
    // 时限给得远远宽于实际用时。考的是"能不能盖完",不是"多快盖完"。
    @GameTest(template = "floor52", timeoutTicks = 400000, batch = "numen_cottage_jp")
    public static void build_japanese_cottage(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        copyCottageFixture(level);
        NumenPlayer companion = spawnAt(helper, "gametest_daiku", new BlockPos(2, 2, 2), true);
        BlockPos anchor = helper.absolutePos(new BlockPos(6, 2, 4));
        var loaded = com.dwinovo.numen.core.blueprint.BlueprintStore.load(
                level, "japanese_cottage", anchor, 0);
        var ctx = TaskDispatch.ctx("gametest-jp-cottage", companion);
        TaskDispatch.setTask(companion, new BuildTaskRecord(ctx.toolCallId(),
                ctx.deadline(95000L), loaded.targets(), com.dwinovo.numen.core.task.build.ReplaceMode.REPLACE_EMPTY, false), null, reply -> {});
        helper.succeedWhen(() -> {
            for (BuildTaskRecord.Target t : loaded.targets()) {
                helper.assertTrue(t.matches(level.getBlockState(t.pos())),
                        "cottage cell mismatch at " + t.pos().toShortString());
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    // ---- 从工具入口:build 的指令流与 blueprint 的文件 ----

    /** 空的指令流不是一件活:当参数错误回来,说清楚至少要一条,不派任何活。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void build_tool_rejects_an_empty_op_list(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_idle_builder", new BlockPos(2, 2, 2), false);
        ToolRun build = call(companion, "build", args("ops", List.of()));

        helper.succeedWhen(() -> {
            helper.assertTrue(build.done() && build.task() == null, "an empty build was dispatched");
            helper.assertTrue(!build.succeeded() && build.outcome().contains("invalid arguments")
                            && build.outcome().contains("at least one"),
                    "the reply does not reject the empty op list: " + build.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 生存模式,按模型的写法下一串指令:一圈两格高的圆石墙,再在南墙正中装一扇门(后面的指令盖掉前面的格子)。
     * 墙一格不缺,门的上下两半都在,圆石按格扣。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_build")
    public static void build_tool_walls_with_a_door(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_waller", new BlockPos(2, 2, 2), false);
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 32));
        companion.getInventory().add(new ItemStack(Items.OAK_DOOR));
        BlockPos min = helper.absolutePos(new BlockPos(5, 2, 5));
        BlockPos max = helper.absolutePos(new BlockPos(7, 3, 7));
        BlockPos door = helper.absolutePos(new BlockPos(6, 2, 7));
        ToolRun build = call(companion, "build", args("ops", List.of(
                args("op", "layer", "block_id", "minecraft:cobblestone",
                        "x1", min.getX(), "y1", min.getY(), "z1", min.getZ(), "y2", max.getY(),
                        "rows", List.of("###", "#.#", "###")),
                // 门只写下半格:另一半由原版的放置回调自己补,和图纸那条入口同一条纪律
                args("op", "set", "block_id", "minecraft:oak_door[facing=south]",
                        "x", door.getX(), "y", door.getY(), "z", door.getZ()))));

        helper.succeedWhen(() -> {
            helper.assertTrue(build.done(), "build has not finished");
            helper.assertTrue(build.succeeded(), "build failed: " + build.outcome());
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    boolean ring = x == min.getX() || x == max.getX() || z == min.getZ() || z == max.getZ();
                    for (int y = min.getY(); y <= max.getY(); y++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (p.getX() == door.getX() && p.getZ() == door.getZ()) {
                            helper.assertTrue(level.getBlockState(p).is(Blocks.OAK_DOOR),
                                    "the door is not at " + p.toShortString());
                        } else if (ring) {
                            helper.assertTrue(level.getBlockState(p).is(Blocks.COBBLESTONE),
                                    "the wall is missing at " + p.toShortString());
                        } else {
                            helper.assertTrue(level.getBlockState(p).isAir(), "the inside is not clear at "
                                    + p.toShortString());
                        }
                    }
                }
            }
            helper.assertTrue(companion.getInventory().countItem(Items.COBBLESTONE) == 32 - 14
                            && companion.getInventory().countItem(Items.OAK_DOOR) == 0,
                    "the materials spent do not match the cells built");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 蓝图:list 里看得到放进 schematics/ 的那份文件,build 照文件把它建出来,每一格都对上。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_build")
    public static void blueprint_tool_lists_and_builds_a_file(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        writeSmallHouse(level, "fixture_tool");
        NumenPlayer companion = spawnAt(helper, "gametest_architect", new BlockPos(2, 2, 2), true);
        BlockPos anchor = helper.absolutePos(new BlockPos(6, 2, 6));
        ToolRun list = call(companion, "blueprint", args("action", "list"));
        ToolRun build = call(companion, "blueprint", args("action", "build", "file", "fixture_tool",
                "x", anchor.getX(), "y", anchor.getY(), "z", anchor.getZ()));
        var targets = com.dwinovo.numen.core.blueprint.BlueprintStore.load(level, "fixture_tool", anchor, 0).targets();

        helper.succeedWhen(() -> {
            helper.assertTrue(list.succeeded() && list.reply().contains("fixture_tool"),
                    "the blueprint is not listed: " + list.reply());
            helper.assertTrue(build.done(), "blueprint build has not finished");
            helper.assertTrue(build.succeeded(), "blueprint build failed: " + build.outcome());
            for (BuildTaskRecord.Target t : targets) {
                helper.assertTrue(t.matches(level.getBlockState(t.pos())),
                        "the cell at " + t.pos().toShortString() + " does not match the blueprint");
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 生存模式砌一块 5×5 的圆石平台,砌到第三格主人按停止:活按主人停止收场;停下之后不再多砌一格,
     * 背包里少掉的圆石正好是砌上去的格数。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_build")
    public static void owner_stop_mid_build_keeps_what_is_built(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_paused_mason", new BlockPos(2, 2, 2), false);
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        BlockPos min = helper.absolutePos(new BlockPos(6, 2, 6));
        BlockPos max = helper.absolutePos(new BlockPos(10, 2, 10));
        ToolRun build = call(companion, "build", args("ops", List.of(args("op", "layer",
                "block_id", "minecraft:cobblestone", "x1", min.getX(), "y1", min.getY(), "z1", min.getZ(),
                "rows", List.of("#####", "#####", "#####", "#####", "#####")))));
        java.util.function.IntSupplier placed = () -> (int) BlockPos.betweenClosedStream(min, max)
                .filter(p -> level.getBlockState(p).is(Blocks.COBBLESTONE)).count();
        int[] atStop = new int[1];

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(placed.getAsInt() >= 3, "she has not laid three cells yet"))
                .thenExecute(() -> com.dwinovo.numen.task.CompanionTickDispatcher.cancelFor(companion))
                .thenWaitUntil(() -> helper.assertTrue(build.done() && build.outcome().startsWith("the owner pressed Stop"),
                        "the build did not end as stopped by the owner: " + build.outcome()))
                .thenExecute(() -> atStop[0] = placed.getAsInt())
                .thenIdle(20)
                .thenWaitUntil(() -> {
                    helper.assertTrue(placed.getAsInt() == atStop[0] && atStop[0] < 25,
                            "cells kept appearing after the stop: " + atStop[0] + " then " + placed.getAsInt());
                    helper.assertTrue(companion.getInventory().countItem(Items.COBBLESTONE) == 64 - atStop[0],
                            "the cobblestone spent does not match the " + atStop[0] + " cells laid");
                })
                .thenExecute(() -> CompanionFactory.despawn(level.getServer(), companion))
                .thenSucceed();
    }

    // ---- 通用原语:网格、复制、掩码 ----

    /**
     * 一张网格盖出一圈三层高的墙:{@code '.'} 是"这一格不管",所以屋里一格没碰;
     * 图例里每个字符自带方块状态,所以一条指令就能把朝向不同的楼梯铺在同一课上。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_build")
    public static void layer_repeats_a_grid_and_leaves_dots_alone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_grid_mason", new BlockPos(2, 2, 2), true);
        BlockPos o = helper.absolutePos(new BlockPos(6, 2, 6));
        level.setBlockAndUpdate(o.offset(1, 0, 1), Blocks.GOLD_BLOCK.defaultBlockState());   // 屋里的记号
        ToolRun build = call(companion, "build", args("ops", List.of(
                args("op", "layer", "x1", o.getX(), "y1", o.getY(), "z1", o.getZ(), "y2", o.getY() + 2,
                        "rows", List.of("###", "#.#", "###"), "block_id", "minecraft:stone_bricks"),
                args("op", "layer", "x1", o.getX(), "y1", o.getY() + 3, "z1", o.getZ(),
                        "rows", List.of("<<<", "...", ">>>"),
                        "legend", args("<", "minecraft:stone_brick_stairs[facing=south]",
                                ">", "minecraft:stone_brick_stairs[facing=north]")))));

        helper.succeedWhen(() -> {
            helper.assertTrue(build.done() && build.succeeded(), "build failed: " + build.outcome());
            for (int y = 0; y <= 2; y++) {
                helper.assertTrue(level.getBlockState(o.offset(0, y, 0)).is(Blocks.STONE_BRICKS),
                        "the grid did not repeat onto level " + y);
            }
            helper.assertTrue(level.getBlockState(o.offset(1, 0, 1)).is(Blocks.GOLD_BLOCK),
                    "'.' must leave a cell alone, but the marker inside the ring was overwritten");
            var north = level.getBlockState(o.offset(1, 3, 0));
            var south = level.getBlockState(o.offset(1, 3, 2));
            helper.assertTrue(north.is(Blocks.STONE_BRICK_STAIRS)
                            && north.getValue(net.minecraft.world.level.block.StairBlock.FACING)
                                    == net.minecraft.core.Direction.SOUTH
                            && south.getValue(net.minecraft.world.level.block.StairBlock.FACING)
                                    == net.minecraft.core.Direction.NORTH,
                    "the legend must carry each character's own block state");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 复制一段带楼梯的墙,镜像过去:方块跟着镜像走,楼梯的朝向由原版翻,不是原样照抄。
     * 源那一段一格不动——复制是"再盖一份",不是"搬走"。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_build")
    public static void copy_mirrors_a_wing_and_flips_its_stairs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_copyist", new BlockPos(2, 2, 2), true);
        BlockPos src = helper.absolutePos(new BlockPos(5, 2, 5));
        BlockPos dst = helper.absolutePos(new BlockPos(10, 2, 5));
        level.setBlockAndUpdate(src, Blocks.STONE_BRICKS.defaultBlockState());
        level.setBlockAndUpdate(src.offset(1, 0, 0), Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                .setValue(net.minecraft.world.level.block.StairBlock.FACING,
                        net.minecraft.core.Direction.EAST));
        ToolRun build = call(companion, "build", args("ops", List.of(
                args("op", "copy",
                        "x1", src.getX(), "y1", src.getY(), "z1", src.getZ(),
                        "x2", src.getX() + 1, "y2", src.getY(), "z2", src.getZ(),
                        "x", dst.getX(), "y", dst.getY(), "z", dst.getZ(),
                        "mirror", "front_back"))));

        helper.succeedWhen(() -> {
            helper.assertTrue(build.done() && build.succeeded(), "copy failed: " + build.outcome());
            helper.assertTrue(level.getBlockState(src.offset(1, 0, 0))
                            .is(Blocks.STONE_BRICK_STAIRS), "the source wing was moved instead of copied");
            var copied = level.getBlockState(dst);
            var other = level.getBlockState(dst.offset(1, 0, 0));
            helper.assertTrue(copied.is(Blocks.STONE_BRICK_STAIRS) || other.is(Blocks.STONE_BRICK_STAIRS),
                    "nothing was copied to the destination");
            var stair = copied.is(Blocks.STONE_BRICK_STAIRS) ? copied : other;
            helper.assertTrue(stair.getValue(net.minecraft.world.level.block.StairBlock.FACING)
                            == net.minecraft.core.Direction.WEST,
                    "mirroring must flip the stair, got " + stair);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 掩码分档:{@code keep} 只往空地上补,既有的墙一格不碰;{@code carve} 的空气格是
     * "把这里挖空"的指令。两档写在同一次调用的两条指令上,各管各的。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_build")
    public static void mask_keep_adds_without_touching_what_stands(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_masker", new BlockPos(2, 2, 2), true);
        BlockPos o = helper.absolutePos(new BlockPos(6, 2, 10));
        level.setBlockAndUpdate(o, Blocks.GOLD_BLOCK.defaultBlockState());          // 已经立着的
        level.setBlockAndUpdate(o.offset(2, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState());   // 要挖掉的
        ToolRun build = call(companion, "build", args("ops", List.of(
                args("op", "layer", "x1", o.getX(), "y1", o.getY(), "z1", o.getZ(),
                        "rows", List.of("##"), "block_id", "minecraft:stone_bricks", "mask", "keep"),
                args("op", "set", "x", o.getX() + 2, "y", o.getY(), "z", o.getZ(),
                        "block_id", "minecraft:air", "mask", "carve"))));

        helper.succeedWhen(() -> {
            helper.assertTrue(build.done() && build.succeeded(), "build failed: " + build.outcome());
            helper.assertTrue(level.getBlockState(o).is(Blocks.GOLD_BLOCK),
                    "mask=keep must not overwrite what already stands");
            helper.assertTrue(level.getBlockState(o.offset(1, 0, 0)).is(Blocks.STONE_BRICKS),
                    "mask=keep must still fill the empty cell beside it");
            helper.assertTrue(level.getBlockState(o.offset(2, 0, 0)).isAir(),
                    "mask=carve with an air cell must dig that cell out");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }
}
