package com.dwinovo.numen.gametest;

import com.dwinovo.numen.task.build.BuildCompanionTask;
import com.dwinovo.numen.task.build.BuildTaskRecord;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.task.TaskDispatch;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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

import static com.dwinovo.numen.gametest.GameTestKit.*;

/** 建造:{@code numen.build.place} 与 {@code numen.build.diff}、{@code numen.shape} 画的格、材料账、整栋房子。 */
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
     * 建造用例的公共骨架,和模型的写法一样从脚本入口:floor20 平地、rel(2,2,2) 出生、按需发圆石(也是赶路的垫路料),一段 Lua 写出要盖的
     * 那一串格,交给 {@code numen.build.place} 走一遍(路线许改天然地形,高处垫块上去)。判据:程序跑到了最后,每格就位。
     *
     * @param cells 要盖的那一串格的 Lua 写法,收落点那一格(世界坐标)
     */
    private static void placeCase(GameTestHelper helper, String name, BlockPos anchorRel,
                                  java.util.function.Function<BlockPos, String> cells, List<BlockPos> relCells,
                                  int cobbleStacks) {
        placeCase(helper, name, anchorRel, cells, relCells, List.of(), cobbleStacks);
    }

    /** 同上;{@code leftAlone} 是形状占地里不该有方块的格(空心的里面):建完它们还是原样。 */
    private static void placeCase(GameTestHelper helper, String name, BlockPos anchorRel,
                                  java.util.function.Function<BlockPos, String> cells, List<BlockPos> relCells,
                                  List<BlockPos> leftAlone, int cobbleStacks) {
        ServerLevel level = helper.getLevel();
        Raised build = placeCells(helper, name, new BlockPos(2, 2, 2), anchorRel, cells, relCells, cobbleStacks);
        succeedWhen(helper, () -> {
            helper.assertTrue(build.run().receipt() != null, "numen.build.place has not finished");
            helper.assertTrue(build.run().ranToTheEnd(), "numen.build.place failed: " + build.run().receipt());
            for (BlockPos cell : build.cells()) {
                helper.assertTrue(level.getBlockState(cell).is(Blocks.COBBLESTONE),
                        "structure incomplete at " + cell.toShortString());
            }
            for (BlockPos cell : leftAlone) {
                helper.assertTrue(!level.getBlockState(helper.absolutePos(cell)).is(Blocks.COBBLESTONE),
                        "the inside of the hollow shape was built at " + cell.toShortString());
            }
            CompanionFactory.despawn(level.getServer(), build.companion());
        });
    }

    /** 一串交给 {@code numen.build.place} 的圆石格:她、那段程序、目标格。判据由用例自己定。 */
    private record Raised(NumenPlayer companion, ToolRun run, List<BlockPos> cells) {}

    /** 把 {@code cells} 在 {@code anchorRel} 交给 {@code numen.build.place}:rel 出生、按需发圆石。 */
    private static Raised placeCells(GameTestHelper helper, String name, BlockPos spawnRel, BlockPos anchorRel,
                                java.util.function.Function<BlockPos, String> cells, List<BlockPos> relCells,
                                int cobbleStacks) {
        NumenPlayer companion = spawnAt(helper, name, spawnRel, false);
        for (int i = 0; i < cobbleStacks; i++) {
            companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        }
        ToolRun run = lua(companion, "numen.build.place(" + cells.apply(helper.absolutePos(anchorRel)) + ")");
        return new Raised(companion, run, relCells.stream().map(helper::absolutePos).toList());
    }

    /** 一个圆石的长方体:{@code sx} 列、{@code sy} 层、{@code sz} 行,最低西北角在落点。 */
    private static java.util.function.Function<BlockPos, String> cobbleBox(int sx, int sy, int sz) {
        return o -> "numen.shape.cuboid(" + xyz(o) + ", " + xyz(o.offset(sx - 1, sy - 1, sz - 1)) + "):set(\"cobblestone\")";
    }

    /**
     * 形状:{@code numen.shape.hcyl} 画的空心圆柱(半径 3、高 4 的塔筒),圆心在 rel 10,2,10。WorldEdit 的空心圆:半径加 0.5 之后,
     * 圆里的格向外(+x、+z)一步还在圆里的就挖掉,留下的是最薄的连续一圈;圈里的格建完还是空的。
     */
    @GameTest(template = "floor20", timeoutTicks = 3126, batch = "numen_build")
    public static void build_shape_cylinder(GameTestHelper helper) {
        BlockPos center = new BlockPos(10, 2, 10);
        List<BlockPos> rel = new ArrayList<>();
        List<BlockPos> inside = new ArrayList<>();
        hollowCylinderCells(center, rel, inside);
        placeCase(helper, "gametest_mason2", center,
                c -> "numen.shape.hcyl(" + xyz(c) + ", \"cobblestone\", 3, 4)", rel, inside, 2);
    }

    /** 半径 3、高 4 的空心圆柱(底层中心 center):墙上的格进 rel,圈里留空的格进 inside。 */
    private static void hollowCylinderCells(BlockPos center, List<BlockPos> rel, List<BlockPos> inside) {
        double inv = 1 / 3.5;
        for (int y = 0; y < 4; y++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    double ax = Math.abs(dx);
                    double az = Math.abs(dz);
                    if (!inRound(ax * inv, az * inv)) {
                        continue;
                    }
                    boolean enclosed = inRound((ax + 1) * inv, az * inv) && inRound(ax * inv, (az + 1) * inv);
                    (enclosed ? inside : rel).add(center.offset(dx, y, dz));
                }
            }
        }
    }

    private static boolean inRound(double a, double b) {
        return a * a + b * b <= 1;
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
     * 素面格落进高草下半格:手放的邻居链会把上半截草打碎——
     * 直写时代留孤儿草悬在工作台头顶(#63)。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void plain_cell_into_tall_grass_breaks_the_top(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lower = helper.absolutePos(new BlockPos(6, 2, 6));
        level.setBlock(lower, Blocks.TALL_GRASS.defaultBlockState(), 3);
        level.setBlock(lower.above(), Blocks.TALL_GRASS.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 3);
        NumenPlayer companion = spawnAt(helper, "gametest_mower", new BlockPos(4, 2, 6), false);
        companion.getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
        var ctx = TaskDispatch.ctx("gametest-tallgrass", companion);
        TaskDispatch.setTask(companion, buildJob(ctx.toolCallId(), ctx.deadline(4000L),
                List.of(new BuildTaskRecord.Target(Blocks.CRAFTING_TABLE, Items.CRAFTING_TABLE,
                        lower, "crafting_table")), true));
        succeedWhen(helper, () -> {
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
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void volume_fences_connect_after_build(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_fencer", new BlockPos(7, 2, 6), false);
        List<BuildTaskRecord.Target> targets = new ArrayList<>();
        for (int x = 6; x <= 8; x++) {
            targets.add(new BuildTaskRecord.Target(Blocks.OAK_FENCE, Items.OAK_FENCE,
                    helper.absolutePos(new BlockPos(x, 2, 8)), "oak_fence"));
        }
        companion.getInventory().add(new ItemStack(Items.OAK_FENCE, 3));
        var ctx = TaskDispatch.ctx("gametest-fence-row", companion);
        TaskDispatch.setTask(companion, buildJob(ctx.toolCallId(), ctx.deadline(4000L),
                targets, true));
        BlockPos mid = helper.absolutePos(new BlockPos(7, 2, 8));
        BlockPos west = helper.absolutePos(new BlockPos(6, 2, 8));
        BlockPos east = helper.absolutePos(new BlockPos(8, 2, 8));
        succeedWhen(helper, () -> {
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
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void built_fence_grabs_existing_neighbour(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(6, 2, 6), Blocks.OAK_FENCE);
        NumenPlayer companion = spawnAt(helper, "gametest_edger", new BlockPos(7, 2, 4), false);
        BlockPos oldPos = helper.absolutePos(new BlockPos(6, 2, 6));
        BlockPos newPos = helper.absolutePos(new BlockPos(7, 2, 6));
        List<BuildTaskRecord.Target> targets = List.of(
                new BuildTaskRecord.Target(Blocks.OAK_FENCE, Items.OAK_FENCE,
                        newPos, "oak_fence"));
        companion.getInventory().add(new ItemStack(Items.OAK_FENCE, 1));
        var ctx = TaskDispatch.ctx("gametest-fence-edge", companion);
        TaskDispatch.setTask(companion, buildJob(ctx.toolCallId(), ctx.deadline(4000L),
                targets, true));
        succeedWhen(helper, () -> {
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
     * 素面格升格:无属性无数据的格子(工作台)对账宽容、按手放扣料——升格发生在
     * record 构造时,对账读 itemPlace 一个字段;带属性的格子(栅栏)不升格。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void plain_cell_promoted_to_item_place(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_joiner", new BlockPos(7, 2, 4), false);
        BlockPos tablePos = helper.absolutePos(new BlockPos(6, 2, 6));
        List<BuildTaskRecord.Target> targets = List.of(
                new BuildTaskRecord.Target(Blocks.CRAFTING_TABLE, Items.CRAFTING_TABLE,
                        tablePos, "crafting_table"),
                new BuildTaskRecord.Target(Blocks.OAK_FENCE, Items.OAK_FENCE,
                        helper.absolutePos(new BlockPos(8, 2, 6)), "oak_fence"));
        companion.getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
        companion.getInventory().add(new ItemStack(Items.OAK_FENCE, 1));
        var ctx = TaskDispatch.ctx("gametest-plain-cell", companion);
        BuildTaskRecord record = buildJob(ctx.toolCallId(), ctx.deadline(4000L), targets, true);
        helper.assertTrue(record.targets.get(0).itemPlace(), "素面格(工作台)没升格");
        helper.assertFalse(record.targets.get(1).itemPlace(), "带属性格(栅栏)不该升格");
        TaskDispatch.setTask(companion, record);
        succeedWhen(helper, () -> {
            helper.assertTrue(level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE),
                    "工作台没放出来");
            helper.assertTrue(companion.getInventory().countItem(Items.CRAFTING_TABLE) == 0,
                    "手放没按手扣料");
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
     * <p>液体读得通(名字是对的),只是她不放:读成方块,回执在 {@code liquid} 一类里说(见 {@code liquid_cells_*} 两条)。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void build_block_ids_and_material_counts(GameTestHelper helper) {
        for (String id : new String[]{"air", "minecraft:air", "dirt_path", "farmland", "tall_grass"}) {
            com.dwinovo.numen.build.BuildStates.resolve(id);
        }
        for (String liquid : new String[]{"water", "minecraft:water", "lava"}) {
            var resolved = com.dwinovo.numen.build.BuildStates.resolve(liquid);
            helper.assertTrue(resolved.state().getBlock() instanceof net.minecraft.world.level.block.LiquidBlock,
                    liquid + " reads as the liquid block; got " + resolved.state());
        }
        // 方块状态跟在方块名后面,和 /setblock 同一套语法
        var stairs = com.dwinovo.numen.build.BuildStates.resolve("spruce_stairs[facing=south,half=top]").state();
        helper.assertTrue(stairs.is(Blocks.SPRUCE_STAIRS)
                        && stairs.getValue(net.minecraft.world.level.block.StairBlock.FACING)
                                == net.minecraft.core.Direction.SOUTH
                        && stairs.getValue(net.minecraft.world.level.block.StairBlock.HALF)
                                == net.minecraft.world.level.block.state.properties.Half.TOP,
                "the block state must come through the block_id, got " + stairs);
        String stateMsg = "";
        try {
            com.dwinovo.numen.build.BuildStates.resolve("stone[facing=north]");
        } catch (IllegalArgumentException e) {
            stateMsg = String.valueOf(e.getMessage());
        }
        helper.assertTrue(stateMsg.contains("facing"),
                "a property the block does not have must be refused by name; got \"" + stateMsg + "\"");

        // 没有自己物品的方块要拿替代料算账,否则文档里教的 dirt_path 根本放不下去
        helper.assertTrue(com.dwinovo.numen.build.BuildStates.resolve("dirt_path").item() == Items.DIRT,
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
        mixed.sort(com.dwinovo.numen.task.build.BuildOrder.BUILD_ORDER);
        helper.assertTrue(mixed.get(0).desiredState().getBlock() == Blocks.STONE,
                "everything that stands on its own goes first, even nine layers up; got "
                        + mixed.stream().map(BuildTaskRecord.Target::label).toList());
        helper.succeed();
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

        record Row(com.dwinovo.numen.task.build.ReplaceMode mode, BlockState current,
                   BlockState desired, boolean want, String why) {}
        for (Row row : List.of(
                // 最低档:只往空地和软方块上补,既有建筑一格不碰,也不清空
                new Row(com.dwinovo.numen.task.build.ReplaceMode.DONT_REPLACE, air, solid, true,
                        "empty ground is always fair game"),
                new Row(com.dwinovo.numen.task.build.ReplaceMode.DONT_REPLACE, soft, solid, true,
                        "grass is replaceable, vanilla lets you build straight over it"),
                new Row(com.dwinovo.numen.task.build.ReplaceMode.DONT_REPLACE, solid, solid, false,
                        "this mode exists so an existing building is never touched"),
                new Row(com.dwinovo.numen.task.build.ReplaceMode.DONT_REPLACE, solid, air, false,
                        "no mode below the top one clears anything"),
                // 中档:实心可以压实心,但细软件不能顶掉墙
                new Row(com.dwinovo.numen.task.build.ReplaceMode.REPLACE_SOLID, solid, solid, true,
                        "structure may push through structure"),
                new Row(com.dwinovo.numen.task.build.ReplaceMode.REPLACE_SOLID, solid, torch, false,
                        "a torch must not knock out a wall"),
                new Row(com.dwinovo.numen.task.build.ReplaceMode.REPLACE_SOLID, soft, torch, true,
                        "but it may go where there was only grass"),
                new Row(com.dwinovo.numen.task.build.ReplaceMode.REPLACE_SOLID, solid, air, false,
                        "still no clearing"),
                // 高档:挡路的一律顶掉,但空气格只当"不管这一格"
                new Row(com.dwinovo.numen.task.build.ReplaceMode.REPLACE_ANY, solid, torch, true,
                        "anything in the way gives way"),
                new Row(com.dwinovo.numen.task.build.ReplaceMode.REPLACE_ANY, solid, air, false,
                        "an air cell here means 'leave this one alone', not 'dig it out'"),
                // 顶档:连该空的地方也挖空
                new Row(com.dwinovo.numen.task.build.ReplaceMode.REPLACE_EMPTY, solid, air, true,
                        "this is the mode where an air cell is a dig order"),
                new Row(com.dwinovo.numen.task.build.ReplaceMode.REPLACE_EMPTY, solid, torch, true,
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
     * <p>一串格可以来自某个世界某一刻的快照,里面混着大量世界自己算出来的东西:作物长到第
     * 几节、方块含不含水、活塞伸没伸出去、堆肥桶攒了多少、锅里装的什么。照字面摆
     * 下去就会凭空长出一片熟麦子、凭空造出水来、摆一口装着岩浆的锅。
     *
     * <p>判据和 {@code BuildValidity} 那张"作者属性"白名单是同一条的两面:那边管
     * 比对时忽略什么,这边管落位前清掉什么。所以归一必须在<b>每个目标格都要过的
     * 那道口</b>上做一次,而不是让每条入口各清各的。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void runtime_state_is_normalized(GameTestHelper helper) {
        BlockState ripe = Blocks.WHEAT.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_7, 7);
        BlockState wet = Blocks.OAK_STAIRS.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true);
        BlockState full = Blocks.COMPOSTER.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL_COMPOSTER, 5);

        var wheat = new BuildTaskRecord.Target(ripe, Items.WHEAT_SEEDS, BlockPos.ZERO, "wheat");
        helper.assertTrue(wheat.desiredState().getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_7) == 0,
                "ripe wheat must be planted as a seedling, not conjured fully grown");

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
                "a cauldron's contents are runtime state; a cell must not hand out free lava");

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
     * 材料记账上<b>有意</b>不随大流的那几行,钉成断言。
     *
     * <p>耕地与土径自述的是自己(那两件物品确实存在),但人手上没有"一块耕地"可放——那是拿锄头在土上刨出来的,所以按土算。
     *
     * <p>一格不止一件的也有:一格四根蜡烛、贴三面的藤蔓、双层砖、几层雪,通行做法是收一件,那是白送。我们按根数、面数、层数收。
     *
     * <p>两个方向合起来是同一条判据:<b>一格该收多少,取决于人手工要花多少</b>,不取决于那个方块的 {@code asItem()} 恰好是什么。
     * 这条测试的意义不是"证明我们对",是把这几行分歧写在明处——哪天有人照通行做法"修正"回去,得先来改这里的字。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void material_accounting_divergences_are_deliberate(GameTestHelper helper) {
        // 一、耕地与土径自述的是自己,记账要按土
        for (net.minecraft.world.level.block.Block b : List.of(Blocks.FARMLAND, Blocks.DIRT_PATH)) {
            helper.assertTrue(b.asItem() != Items.DIRT,
                    b + " self-reports " + b.asItem() + "; if that ever became dirt,"
                            + " this override could go");
            helper.assertTrue(com.dwinovo.numen.build.BuildStates.materialItem(b) == Items.DIRT,
                    b + " is worked from dirt by hand, so dirt is what it costs");
        }

        // 二、一格不止一件:这几行按"人手工要花多少"收,不按一件收
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
     * 报价数背包和施工数背包必须是<b>同一个口径</b>——副手上那叠料不算数。
     *
     * <p>这个分岔犯过两次,症状一模一样:一整叠木板放在副手,数 41 格(含盔甲栏与副手)
     * 的那一方说"料够了",数 36 格的那一方每格都判缺料。第一次是在开工预检与逐格闸门
     * 之间,第二次是在 {@code numen.build.diff} 的报价与施工之间——玩家看着手里那叠木板,
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

        int whole = com.dwinovo.numen.PlayerInv.count(inv, Items.SPRUCE_PLANKS);
        int buildable = com.dwinovo.numen.PlayerInv
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
     * skill 文档里点名的每一个方块都必须真的存在。
     *
     * <p>文档是<b>喂给模型的词汇表</b>:写错一个名字,模型就会照着吐一个无效的
     * block_id,而那一格不会报错、只会缺一块。四十份风格文件、上百个方块名从来
     * 没有人验过,而改一次屋顶用料就顺手把十四份文件里的 {@code _stairs} 换成了
     * {@code _slab}——{@code stone_bricks → stone_brick_slab} 这种去复数的名字
     * 靠手改必然漏。
     *
     * <p>判据是注册表本身,不另立一份清单:反引号里凡是带下划线的小写词,要么在
     * 方块/物品注册表里查得到,要么是风格文件名。两边都不是就是错字。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void skill_docs_name_real_blocks(GameTestHelper helper) {
        java.util.List<String> bad = new java.util.ArrayList<>();
        int checked = 0;
        try {
            // 技能文档随 Numen 一起发:经平台取(各加载器怎么把 jar 里的路径映射成 Path 只有它知道),不是测试所在类加载器的资源
            java.nio.file.Path skill = com.dwinovo.numen.api.platform.Services.platform()
                    .modResource(com.dwinovo.numen.Constants.MOD_ID, "numen/skills/numen/building_design/SKILL.md");
            helper.assertTrue(skill != null, "the skill resources are not shipped with Numen");
            java.nio.file.Path root = skill.getParent();
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
                    if (word.indexOf('_') < 0 || docNames.contains(word)) {
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
        } catch (java.io.IOException e) {
            throw new AssertionError("could not lint the skill docs: " + e, e);
        }
        helper.assertTrue(bad.isEmpty(), "skill docs name " + bad.size()
                + " block(s) that do not exist: " + bad);
        helper.assertTrue(checked > 200,
                "the lint matched only " + checked + " block names — the extractor is broken");
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
                long need = com.dwinovo.numen.task.build.BuildOrder
                        .estimatedTicks(cells, survival);
                long budget = com.dwinovo.numen.task.build.BuildOrder.deadlineTicks(cells, survival);
                helper.assertTrue(budget > need,
                        "deadline must exceed the build itself: " + cells + " cells, survival="
                                + survival + ", needs " + need + " ticks but budget is " + budget);
            }
        }
        // 生存是人手的固定速度,每秒 4 格,工程再大也不压缩:一格五刻
        for (int cells : new int[]{1, 500, 16384}) {
            long took = com.dwinovo.numen.task.build.BuildOrder.estimatedTicks(cells, true);
            helper.assertTrue(took == cells * 5L,
                    "survival builds " + cells + " cells at four a second, not " + took + " ticks");
        }
        helper.succeed();
    }

    /**
     * 一栋小屋,照模型的样子用一段 Lua 画出来再开工:地基、墙、双坡屋顶、门窗细节全用 {@code numen.shape}——一张字符网格是一层,
     * 楼梯屋顶是逐课的网格,细节是单格,几串格合起来后写的盖掉先写的。
     *
     * <p>这条用例是 {@code numen.shape} 的整活钉桩:网格带图例、网格按层重复、方块状态跟在方块名里({@code oak_stairs[facing=south]})、
     * 合起来时后写覆盖先写(门洞挖开墙),四件事缺一条都盖不出这栋屋子。
     */
    @GameTest(template = "floor20", timeoutTicks = 3837, batch = "numen_build_cottage")
    public static void build_medieval_cottage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 这条量的是形状能不能盖出一栋屋子,不是生存备料:免耗材档
        NumenPlayer companion = spawnAt(helper, "gametest_carpenter", new BlockPos(2, 2, 2), true);
        // 发垫路料:上高处垫柱要料
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));

        BlockPos o = helper.absolutePos(new BlockPos(4, 2, 5));   // 地基西北角
        String solid = " ############".repeat(10);
        String ring = " ############" + " #..........#".repeat(8) + " ############";
        // 12 x 10 的占地:地基一层实心,墙圈三层,屋顶四课(两侧楼梯对着爬,顶上一条半砖压脊);细节:四角原木柱、南面门洞、
        // 玻璃窗、屋内火把
        String program = """
                local S = numen.shape
                local o = %s
                local function at(x, y, z) return S.pos(o.x + x, o.y + y, o.z + z) end
                local stairs = {["<"] = "oak_stairs[facing=south]", [">"] = "oak_stairs[facing=north]"}
                local c = S.layer(o, %s, {["#"] = "cobblestone"})
                for y = 1, 3 do
                  c = c:union(S.layer(at(0, y, 0), %s, {["#"] = "oak_planks"}))
                end
                c = c:union(S.layer(at(0, 4, 0), %s, stairs))
                c = c:union(S.layer(at(0, 5, 0), %s, stairs))
                c = c:union(S.layer(at(0, 6, 0), %s, stairs))
                c = c:union(S.layer(at(0, 7, 0), %s, {["="] = "oak_slab"}))
                for _, xz in ipairs({{0, 0}, {11, 0}, {0, 9}, {11, 9}}) do
                  c = c:union(S.line({at(xz[1], 1, xz[2]), at(xz[1], 3, xz[2])}, "oak_log[axis=y]"))
                end
                c = c:union(S.cuboid(at(5, 1, 0), at(6, 2, 0)):set("air"))
                c = c:union({{name = "glass_pane", pos = at(2, 2, 0)}, {name = "glass_pane", pos = at(9, 2, 0)},
                  {name = "torch", pos = at(5, 1, 4)}})
                numen.build.place(c)
                """.formatted(xyz(o), rows(solid), rows(ring),
                rows("<<<<<<<<<<<<" + " ............".repeat(8) + " >>>>>>>>>>>>"),
                rows("............ <<<<<<<<<<<<" + " ............".repeat(6) + " >>>>>>>>>>>> ............"),
                rows(" ............".repeat(2) + " <<<<<<<<<<<<" + " ............".repeat(4) + " >>>>>>>>>>>>"
                        + " ............".repeat(2)),
                rows(" ............".repeat(3) + " ============".repeat(4) + " ............".repeat(3)));
        ToolRun build = lua(companion, program);

        // 收场了就判一次:停在别的原因上是失败,不等到时限
        steps(helper).thenWaitUntil(() -> helper.assertTrue(build.done(), "numen.build.place has not finished")).thenExecute(() -> {
            helper.assertTrue(build.ranToTheEnd(), "the cottage was not finished: " + build.receipt());
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
        }).thenSucceed();
    }

    /** 空格隔开的几行字符图写成脚本里的一串行:{@code " ### #.#"} 是 {@code {"###", "#.#"}}。 */
    private static String rows(String spaced) {
        return "{" + java.util.Arrays.stream(spaced.strip().split(" +")).map(r -> "\"" + r + "\"")
                .collect(java.util.stream.Collectors.joining(", ")) + "}";
    }

    /** 当场回的那一行必须成功,否则把回执原样报出来。 */
    private static void requireOk(ToolRun run) {
        if (!run.succeeded()) {
            throw new net.minecraft.gametest.framework.GameTestAssertException("not accepted: " + run.reply());
        }
    }

    /** 重型建造批次前置(与轻型批分开,别让六个建造者同时抢搜索池——
     *  生产环境是 20tps 一两个同伴,测试没必要用数量级更苛的并发打自己)。 */
    @BeforeBatch(batch = "numen_build_heavy")
    public static void prepareHeavyBuildBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 单块悬置:目标在头部高度、上方为空——真实世界曾整任务卡死的最小场景
     *  (站在旁边就该侧身放上,不接受任何"找不到角度")。 */
    @GameTest(template = "floor20", timeoutTicks = 200, batch = "numen_build")
    public static void build_single_block(GameTestHelper helper) {
        placeCase(helper, "gametest_handyman", new BlockPos(6, 3, 6),
                c -> "{{name = \"cobblestone\", pos = " + xyz(c) + "}}",
                List.of(new BlockPos(6, 3, 6)), 1);
    }

    /** 实心 5x5x5(125 格):逐层实心浇筑,高处的格要垫块上去够。 */
    @GameTest(template = "floor20", timeoutTicks = 4587, batch = "numen_build_heavy")
    public static void build_solid_cube(GameTestHelper helper) {
        placeCase(helper, "gametest_mason", new BlockPos(7, 2, 7), cobbleBox(5, 5, 5),
                boxCells(new BlockPos(7, 2, 7), 5, 5, 5, false), 4);
    }

    /** 一堵 10x4 的墙(40 格):长条高结构,沿线往返 + 够高处的格子。 */
    @GameTest(template = "floor20", timeoutTicks = 1866, batch = "numen_build")
    public static void build_wall(GameTestHelper helper) {
        placeCase(helper, "gametest_waller", new BlockPos(5, 2, 10), cobbleBox(10, 4, 1),
                boxCells(new BlockPos(5, 2, 10), 10, 4, 1, false), 2);
    }

    /** 2x2x8 高塔(32 格):细高结构,上面的格要垫块爬上去够。 */
    @GameTest(template = "floor20", timeoutTicks = 1758, batch = "numen_build")
    public static void build_pillar(GameTestHelper helper) {
        placeCase(helper, "gametest_towerer", new BlockPos(9, 2, 9), cobbleBox(2, 8, 2),
                boxCells(new BlockPos(9, 2, 9), 2, 8, 2, false), 2);
    }

    /** 9x9 平台(81 格):纯水平铺面,不触发分层,考横向站位与边铺边退。 */
    @GameTest(template = "floor20", timeoutTicks = 4893, batch = "numen_build")
    public static void build_platform(GameTestHelper helper) {
        placeCase(helper, "gametest_paver", new BlockPos(5, 2, 5), cobbleBox(9, 1, 9),
                boxCells(new BlockPos(5, 2, 5), 9, 1, 9, false), 3);
    }

    /**
     * 被外力挪进要盖的格里也得盖完:她被推进、挤进、掉进还没砌的格子里时,那一遍放不了那格、如实留在回执里;再放一遍(她先从那格里
     * 走出来,到达方式 reach 不站进要放的格),把它补上。
     */
    @GameTest(template = "floor20", timeoutTicks = 1203, batch = "numen_build")
    public static void build_finishes_the_cells_she_was_pushed_into(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Raised build = placeCells(helper, "gametest_shoved", new BlockPos(2, 2, 2), new BlockPos(8, 2, 8),
                cobbleBox(5, 1, 5), boxCells(new BlockPos(8, 2, 8), 5, 1, 5, false), 1);
        BlockPos[] shovedInto = {null};
        helper.onEachTick(() -> {
            if (shovedInto[0] != null
                    || build.cells().stream().filter(c -> level.getBlockState(c).is(Blocks.COBBLESTONE)).count() < 5) {
                return;
            }
            // 挑离她最远、还是空气的那格,把她整个人挪进去——脚就踩在自己要放的那格里
            BlockPos feet = build.companion().blockPosition();
            BlockPos target = null;
            for (BlockPos cell : build.cells()) {
                if (level.getBlockState(cell).isAir()
                        && (target == null || cell.distSqr(feet) > target.distSqr(feet))) {
                    target = cell;
                }
            }
            if (target == null) {
                return;
            }
            build.companion().teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            shovedInto[0] = target;
        });
        ToolRun[] again = {null};
        succeedWhen(helper, () -> {
            helper.assertTrue(shovedInto[0] != null, "she was never pushed in - the case did not run");
            helper.assertTrue(build.run().done(), "the first pass has not finished");
            if (again[0] == null) {
                // 第一遍收场了,那格没放;模型看回执再放一遍
                again[0] = lua(build.companion(), "numen.build.place("
                        + cobbleBox(5, 1, 5).apply(helper.absolutePos(new BlockPos(8, 2, 8))) + ")");
                helper.fail("the first pass is over; going over what is left once more");
            }
            helper.assertTrue(again[0].done(), "the second pass has not finished");
            helper.assertTrue(again[0].ranToTheEnd(), "numen.build.place failed: " + again[0].receipt());
            for (BlockPos cell : build.cells()) {
                helper.assertTrue(level.getBlockState(cell).is(Blocks.COBBLESTONE),
                        "structure incomplete at " + cell.toShortString());
            }
            CompanionFactory.despawn(level.getServer(), build.companion());
        });
    }

    // ---- 当场的一次放:小活不演、站着清与换、缺格清单 ----

    /** 一格的 {@code numen.build.place} 是一个动作:几刻之内放完收工,她站在原地不绕圈,没有二十五秒的演出。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void placing_one_cell_is_done_at_once_without_walking(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_one_cell", new BlockPos(6, 2, 6), true);
        BlockPos at = helper.absolutePos(new BlockPos(8, 2, 8));
        long[] sentAt = {0};
        Vec3[] from = {null};
        ToolRun[] set = {null};
        steps(helper)
                .thenExecute(() -> {
                    sentAt[0] = helper.getTick();
                    from[0] = companion.position();
                    set[0] = lua(companion, "numen.build.place({{name = \"stone\", pos = " + xyz(at) + "}})");
                })
                .thenWaitUntil(() -> helper.assertTrue(set[0].done(), "the build place has not finished"))
                .thenExecute(() -> {
                    long took = helper.getTick() - sentAt[0];
                    helper.assertTrue(set[0].succeeded(), "numen.build.place failed: " + set[0].outcome());
                    helper.assertTrue(level.getBlockState(at).is(Blocks.STONE), "no stone at the cell");
                    helper.assertTrue(took <= 20, "a one-cell build place took " + took + " ticks");
                    double moved = Math.hypot(companion.getX() - from[0].x, companion.getZ() - from[0].z);
                    helper.assertTrue(moved < 0.5, "she walked " + moved + " blocks for a one-cell build place");
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * 生存模式下要放的格里立着别的方块:{@code numen.build.place} 只放不挖,一行三格的空气当场拒,说这三格要先挖开、
     * 给出挖的那一行;照着用 {@code numen.work.dig} 挖掉——真挖(徒手挖一块泥土要十五刻),{@code numen.work.collect} 把掉下来的泥土捡回包里。
     */
    @GameTest(template = "floor16", timeoutTicks = 276, batch = "numen_build")
    public static void survival_build_digs_out_what_must_go(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> dirt = List.of(helper.absolutePos(new BlockPos(8, 2, 8)), helper.absolutePos(new BlockPos(9, 2, 8)),
                helper.absolutePos(new BlockPos(10, 2, 8)));
        for (BlockPos pos : dirt) {
            level.setBlockAndUpdate(pos, Blocks.DIRT.defaultBlockState());
        }
        NumenPlayer companion = spawnAt(helper, "gametest_clearer", new BlockPos(9, 2, 6), false);
        long[] sentAt = {0};
        ToolRun[] runs = {null, null};
        steps(helper)
                .thenExecute(() -> runs[0] = lua(companion, "numen.build.place(numen.shape.line({" + xyz(dirt.get(0)) + ", "
                        + xyz(dirt.get(2)) + "}, \"air\"))"))
                .thenWaitUntil(() -> helper.assertTrue(runs[0].done(), "the build has not replied"))
                .thenExecute(() -> {
                    helper.assertTrue(runs[0].refused() && runs[0].outcome().contains("3 cell(s) hold another block that "
                                    + "must be dug out first") && runs[0].outcome().contains("numen.work.dig("),
                            "the build did not refuse and point at numen.work.dig: " + runs[0].outcome());
                    helper.assertTrue(dirt.stream().allMatch(p -> level.getBlockState(p).is(Blocks.DIRT)),
                            "a refused build touched the dirt");
                    sentAt[0] = helper.getTick();
                    runs[1] = lua(companion, "numen.work.dig(" + xyz(dirt.get(0)) + ", " + xyz(dirt.get(1)) + ", "
                            + xyz(dirt.get(2)) + ")\nnumen.work.collect()");
                })
                .thenWaitUntil(() -> helper.assertTrue(runs[1].receipt() != null, "the dig has not finished"))
                .thenExecute(() -> {
                    long took = helper.getTick() - sentAt[0];
                    helper.assertTrue(runs[1].ranToTheEnd(), "the dig did not clear the three cells: " + runs[1].receipt());
                    for (BlockPos pos : dirt) {
                        helper.assertTrue(level.getBlockState(pos).isAir(), "dirt still stands at " + pos.toShortString());
                    }
                    helper.assertTrue(companion.getInventory().countItem(Items.DIRT) == 3,
                            "the dug dirt did not end up in her pack: " + companion.getInventory().countItem(Items.DIRT));
                    helper.assertTrue(took >= 3 * 15, "three blocks of dirt went in " + took + " ticks — not dug by hand");
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * 创造模式直接替换:原版创造一下就碎。一行三块泥土上放一行石头,另一行三块泥土上放一行空气({@code numen.shape.line})——
     * 她站在两行中间,几刻之内一行成了石头、一行成了空气,地上没有掉落物;回执说换了三格、清了三格。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void creative_build_replaces_what_stands_there_at_once(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> toStone = new ArrayList<>();
        List<BlockPos> toAir = new ArrayList<>();
        for (int x = 8; x <= 10; x++) {
            toStone.add(helper.absolutePos(new BlockPos(x, 2, 6)));
            toAir.add(helper.absolutePos(new BlockPos(x, 2, 10)));
        }
        for (BlockPos pos : toStone) {
            level.setBlockAndUpdate(pos, Blocks.DIRT.defaultBlockState());
        }
        for (BlockPos pos : toAir) {
            level.setBlockAndUpdate(pos, Blocks.DIRT.defaultBlockState());
        }
        NumenPlayer companion = spawnAt(helper, "gametest_creator", new BlockPos(9, 2, 8), true);
        ToolRun[] runs = {null, null};
        steps(helper)
                .thenExecute(() -> runs[0] = lua(companion, "numen.build.place(numen.shape.line({" + xyz(toStone.get(0)) + ", "
                        + xyz(toStone.get(2)) + "}, \"stone\"))"))
                .thenWaitUntil(() -> helper.assertTrue(runs[0].done(), "the stone line has not finished"))
                .thenExecute(() -> runs[1] = lua(companion, "numen.build.place(numen.shape.line({" + xyz(toAir.get(0)) + ", "
                        + xyz(toAir.get(2)) + "}, \"air\"))"))
                .thenWaitUntil(() -> helper.assertTrue(runs[1].done(), "the air line has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(runs[0].succeeded() && runs[0].outcome().contains("3 replacing what stood there"),
                            "the stone line did not replace the dirt: " + runs[0].outcome());
                    helper.assertTrue(runs[1].succeeded() && runs[1].outcome().contains("cleared 3"),
                            "the air line did not clear the dirt: " + runs[1].outcome());
                    for (BlockPos pos : toStone) {
                        helper.assertTrue(level.getBlockState(pos).is(Blocks.STONE), "no stone at " + pos.toShortString());
                    }
                    for (BlockPos pos : toAir) {
                        helper.assertTrue(level.getBlockState(pos).isAir(), "dirt still stands at " + pos.toShortString());
                    }
                    long drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                            new net.minecraft.world.phys.AABB(helper.absolutePos(new BlockPos(9, 2, 8))).inflate(6)).size();
                    helper.assertTrue(drops == 0, drops + " drop(s) lie around after a creative build");
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * 要放的格里两朵虞美人落在石头上:原版不让它们立在那儿。一次放里先放下石头(虞美人等着它们下面的石头),再轮到虞美人,放下去
     * 原版就弹掉;这不是失败,回执的 {@code unplaced} 把两朵归在 {@code did_not_stay} 下,说明点出是虞美人、在哪两格、周围是什么。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void cells_vanilla_will_not_hold_are_reported_one_by_one(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_unheld", new BlockPos(9, 2, 6), true);
        BlockPos o = helper.absolutePos(new BlockPos(8, 2, 8));
        BlockPos first = o.offset(0, 1, 0);
        BlockPos second = o.offset(2, 1, 0);
        String place = "numen.build.place(numen.shape.line({" + xyz(o) + ", " + xyz(o.offset(2, 0, 0))
                + "}, \"stone\"):union({{name = \"poppy\", pos = " + xyz(first) + "}, {name = \"poppy\", pos = "
                + xyz(second) + "}}))";
        ToolRun build = lua(companion, place);

        succeedWhen(helper, () -> {
            helper.assertTrue(build.done(), "the build has not finished");
            helper.assertTrue(build.succeeded(), "a partly built design is not a failure: " + build.outcome());
            var group = build.result(BuildCompanionTask.Placed.class).unplaced();
            helper.assertTrue(group.size() == 1 && group.get(0).kind().equals("did_not_stay") && group.get(0).cells() == 2,
                    "the two flowers are not one group of did_not_stay: " + group);
            String says = group.get(0).says();
            helper.assertTrue(says.contains("poppy at " + first.getX() + "," + first.getY() + "," + first.getZ())
                            && says.contains("poppy at " + second.getX() + "," + second.getY() + "," + second.getZ())
                            && says.contains("down ") && says.contains("is stone"),
                    "the sentence does not name both flowers, where they are, and the stone under them: " + says);
            helper.assertTrue(level.getBlockState(o).is(Blocks.STONE), "the stone under them was not built");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 一份格里有石头也有水:石头放好,水格不放,{@code place} 不整份拒掉;回执 {@code liquid} 一类把三种情形各数出来(要放的水两格、
     * 含水的楼梯一格去掉了水),世界里没有多出水。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_build")
    public static void liquid_cells_are_reported_and_the_rest_of_the_design_goes_in(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_liquid", new BlockPos(9, 2, 6), true);
        BlockPos o = helper.absolutePos(new BlockPos(8, 2, 8));
        String place = "numen.build.place({{name = \"stone\", pos = " + xyz(o) + "}, {name = \"stone\", pos = " + xyz(o.east())
                + "}, {name = \"water\", pos = " + xyz(o.south()) + "}, {name = \"water\", pos = " + xyz(o.south().east())
                + "}, {name = \"oak_stairs[waterlogged=true]\", pos = " + xyz(o.east(2)) + "}})";
        ToolRun build = lua(companion, place);

        succeedWhen(helper, () -> {
            helper.assertTrue(build.done(), "the build has not finished");
            helper.assertTrue(build.succeeded(), "liquid cells must not fail the whole design: " + build.outcome());
            var groups = build.result(BuildCompanionTask.Placed.class).unplaced();
            helper.assertTrue(groups.size() == 1 && groups.get(0).kind().equals("liquid") && groups.get(0).cells() == 3,
                    "the two water cells and the dry stairs are one liquid group: " + groups);
            String says = groups.get(0).says();
            helper.assertTrue(says.contains("2 cell(s): the design wants water or lava there")
                            && says.contains("1 cell(s): the block was placed without the water it holds"),
                    "the sentence counts each kind: " + says);
            helper.assertTrue(groups.get(0).hint().orElse("").contains("numen.use.block"), "the hint names the bucket");
            helper.assertTrue(level.getBlockState(o).is(Blocks.STONE) && level.getBlockState(o.east()).is(Blocks.STONE)
                            && level.getBlockState(o.east(2)).is(Blocks.OAK_STAIRS), "the rest of the design stands");
            helper.assertTrue(level.getBlockState(o.south()).isAir() && level.getBlockState(o.south().east()).isAir()
                            && level.getFluidState(o.east(2)).isEmpty(), "no water appeared");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * drain 交回的格(水换成空气)交给 {@code place}:她手挖不掉水,三格水都归 {@code liquid},不整份失败,水还在;{@code diff} 把它们单列,
     * 不算在还剩的格与材料里。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_build")
    public static void liquid_cells_from_drain_are_left_for_the_player(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_drained", new BlockPos(9, 2, 6), true);
        BlockPos o = helper.absolutePos(new BlockPos(8, 2, 8));
        for (int i = 0; i < 3; i++) {
            level.setBlock(o.east(i), Blocks.WATER.defaultBlockState(), 2);
        }
        String drained = "numen.shape.drain(" + xyz(o.east()) + ", 2)";
        ToolRun diff = lua(companion, "return numen.build.diff(" + drained + ")");
        helper.assertTrue(diff.ranToTheEnd(), "diff failed: " + diff.receipt());
        var got = diff.data().getAsJsonObject("returned");
        helper.assertTrue(got.get("liquid").getAsInt() == 3 && got.get("left").getAsInt() == 0 && got.get("skipped").getAsInt() == 0
                        && (got.get("materials").toString().equals("[]") || got.get("materials").toString().equals("{}")),
                "diff lists the water on its own: " + got);
        ToolRun build = lua(companion, "numen.build.place(" + drained + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(build.done() && build.succeeded(), "draining must not fail the call: " + build.outcome());
            var groups = build.result(BuildCompanionTask.Placed.class).unplaced();
            helper.assertTrue(groups.size() == 1 && groups.get(0).kind().equals("liquid") && groups.get(0).cells() == 3,
                    "the three water cells are one liquid group: " + groups);
            helper.assertTrue(level.getBlockState(o).is(Blocks.WATER) && level.getBlockState(o.east(2)).is(Blocks.WATER),
                    "she does not drain by hand: the water is still there");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 托着它的还没盖好,它就不算够得着:她站在三格高的石柱顶上,要放的是地上一块石头、石头上铺一块地毯。从柱顶看,地毯够得着、
     * 它下面那块石头够不着。{@code numen.build.diff} 说地毯还立不住({@code unheld})、手边能放的一格都没有、下一步去够那块石头;
     * {@code numen.build.place} 不先放那块地毯,而是走到够得着那块石头的地方,先放石头、再放地毯。日式小屋停在一块地毯上
     * (原版立不住它)就是把立不住的格当成了够得着。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void a_cell_whose_support_is_out_of_reach_is_not_within_reach(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int y = 2; y <= 4; y++) {
            level.setBlockAndUpdate(helper.absolutePos(new BlockPos(1, y, 7)), Blocks.STONE.defaultBlockState());
        }
        NumenPlayer companion = spawnAt(helper, "gametest_carpeter", new BlockPos(1, 5, 7), true);
        BlockPos support = helper.absolutePos(new BlockPos(5, 2, 7));
        String cells = "{{name = \"stone\", pos = " + xyz(support) + "}, {name = \"red_carpet\", pos = "
                + xyz(support.above()) + "}}";
        AtomicReference<ToolRun> at = new AtomicReference<>();
        AtomicReference<ToolRun> left = new AtomicReference<>();
        long[] stoneAt = {-1};
        long[] carpetAt = {-1};
        helper.onEachTick(() -> {
            if (stoneAt[0] < 0 && level.getBlockState(support).is(Blocks.STONE)) {
                stoneAt[0] = level.getGameTime();
            }
            if (carpetAt[0] < 0 && level.getBlockState(support.above()).is(Blocks.RED_CARPET)) {
                carpetAt[0] = level.getGameTime();
            }
        });

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(companion.onGround(), "she has not landed on the pillar"))
                .thenExecute(() -> left.set(lua(companion, "return numen.build.diff(" + cells + ")")))
                .thenWaitUntil(() -> helper.assertTrue(left.get().receipt() != null, "numen.build.diff has not finished"))
                .thenExecute(() -> {
                    var got = left.get().data().getAsJsonObject("returned");
                    helper.assertTrue(got.get("left").getAsInt() == 2 && got.get("reach").getAsInt() == 0
                                    && got.get("unheld").getAsInt() == 1
                                    && got.get("next").equals(posJson(support)),
                            "numen.build.diff does not hold the carpet back until the stone under it is in: " + got);
                    at.set(lua(companion, "numen.build.place(" + cells + ")"));
                })
                .thenWaitUntil(() -> helper.assertTrue(at.get().done(), "numen.build.place has not finished"))
                .thenExecute(() -> {
                    ToolRun build = at.get();
                    helper.assertTrue(build.succeeded() && stoneAt[0] >= 0 && carpetAt[0] >= 0 && stoneAt[0] <= carpetAt[0],
                            "numen.build.place did not walk to the stone and put it in before the carpet (stone at "
                                    + stoneAt[0] + ", carpet at " + carpetAt[0] + "): " + build.outcome());
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * 放下一格会让原版按邻居重算,把旁边一格原版托不住的(安山岩上的粉红花簇)带掉:这一次放下了石头,就是做成了——
     * {@code numen.build.place} 不因为被带掉的花簇当失败收场;花簇的事回执另说。花簇先摆在场景里(原版的放置规则不让它放上去)。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void a_pass_that_places_a_cell_is_not_failed_for_the_neighbour_it_knocks_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_knocker", new BlockPos(4, 2, 6), false);
        companion.getInventory().add(new ItemStack(Items.PINK_PETALS, 4));
        companion.getInventory().add(new ItemStack(Items.STONE, 4));
        BlockPos petals = helper.absolutePos(new BlockPos(6, 2, 6));
        BlockPos stone = petals.east();
        level.setBlockAndUpdate(petals.below(), Blocks.ANDESITE.defaultBlockState());
        level.setBlock(petals, Blocks.PINK_PETALS.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        String flower = "{name = \"pink_petals\", pos = " + xyz(petals) + "}";
        String cells = "{" + flower + ", {name = \"stone\", pos = " + xyz(stone) + "}}";
        AtomicReference<ToolRun> second = new AtomicReference<>();

        steps(helper)
                .thenExecute(() -> second.set(lua(companion, "numen.build.place(" + cells + ")")))
                .thenWaitUntil(() -> helper.assertTrue(second.get().receipt() != null, "the placement has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(second.get().succeeded() && level.getBlockState(stone).is(Blocks.STONE),
                            "a pass that placed the stone was failed for the petals it knocked out: " + second.get().reply());
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    // ---- 从脚本入口:numen.build.place、diff 与建成的房子 ----

    /** 放一格工作台:没有朝向的方块像右键那样放下,生存按格扣料;派下的活叫那个函数的名字。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void build_place_puts_one_block_down_now(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_placer", new BlockPos(4, 2, 6), false);
        companion.getInventory().add(new ItemStack(Items.CRAFTING_TABLE));
        BlockPos at = helper.absolutePos(new BlockPos(6, 2, 6));
        ToolRun place = lua(companion, "numen.build.place({{name = \"crafting_table\", pos = " + xyz(at) + "}})");

        succeedWhen(helper, () -> {
            helper.assertTrue(place.task() != null && place.task().getToolName().equals("numen.build.place"),
                    "the placement is not named after the command: " + place.reply());
            helper.assertTrue(place.done() && place.succeeded(), "place failed: " + place.outcome());
            helper.assertTrue(level.getBlockState(at).is(Blocks.CRAFTING_TABLE), "no crafting table at the cell");
            helper.assertTrue(companion.getInventory().countItem(Items.CRAFTING_TABLE) == 0,
                    "the crafting table was not taken from her inventory");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 要盖的样子已经立在那儿就不是一件活:{@code numen.build.place} 不派活,当场交回一份全是零的账(要盖零格、还差零格)。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void placing_what_already_stands_dispatches_nothing(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_idle_builder", new BlockPos(2, 2, 2), false);
        BlockPos at = helper.absolutePos(new BlockPos(6, 2, 6));
        helper.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
        ToolRun build = lua(companion, "numen.build.place({{name = \"stone\", pos = " + xyz(at) + "}})");

        succeedWhen(helper, () -> {
            helper.assertTrue(build.done() && build.task() == null, "a building that already stands was dispatched");
            helper.assertTrue(build.succeeded() && Long.valueOf(0).equals(build.field("requested"))
                            && Long.valueOf(0).equals(build.field("left")) && Long.valueOf(0).equals(build.field("placed")),
                    "the reply does not say nothing is left to build: " + build.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 要盖的格离她有点远、一格都够不着:{@code numen.build.diff} 如实说还差几格、够得着的零格、够不着的几格和最低最近的那一格,不动;
     * {@code numen.build.place} 自己走到够得着的地方,把九格圆石放齐,圆石按格扣。
     */
    @GameTest(template = "floor16", timeoutTicks = 690, batch = "numen_build")
    public static void build_diff_says_where_and_place_walks_there_and_builds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_far_builder", new BlockPos(1, 2, 1), false);
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 9));
        BlockPos o = helper.absolutePos(new BlockPos(11, 2, 11));
        String cells = "numen.shape.cuboid(" + xyz(o) + ", " + xyz(o.offset(2, 0, 2)) + "):set(\"cobblestone\")";
        ToolRun left = lua(companion, "return numen.build.diff(" + cells + ")");
        AtomicReference<ToolRun> at = new AtomicReference<>();
        BlockPos[] stood = new BlockPos[1];

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(left.receipt() != null, "numen.build.diff has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(left.ranToTheEnd(), "numen.build.diff failed: " + left.receipt());
                    String r = left.receipt();
                    var got = left.data().getAsJsonObject("returned");
                    helper.assertTrue(got.get("left").getAsInt() == 9 && got.get("reach").getAsInt() == 0
                                    && got.get("far").getAsInt() == 9 && got.get("next").isJsonObject(),
                            "numen.build.diff does not say nine left, none within reach, nine far and where next: " + r);
                    stood[0] = companion.blockPosition();
                    helper.assertTrue(stood[0].equals(helper.absolutePos(new BlockPos(1, 2, 1))), "the diff moved her");
                    at.set(lua(companion, "numen.build.place(" + cells + ")"));
                })
                .thenWaitUntil(() -> helper.assertTrue(at.get().done(), "numen.build.place has not finished"))
                .thenExecute(() -> {
                    ToolRun build = at.get();
                    helper.assertTrue(build.succeeded(), "place from afar failed: " + build.outcome());
                    helper.assertTrue(BlockPos.betweenClosedStream(o, o.offset(2, 0, 2))
                                    .allMatch(p -> level.getBlockState(p).is(Blocks.COBBLESTONE))
                                    && companion.getInventory().countItem(Items.COBBLESTONE) == 0,
                            "the nine cells were not all placed, or the cobblestone does not match: " + build.outcome());
                    helper.assertTrue(!companion.blockPosition().equals(stood[0]), "she placed them without walking up");
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * {@code numen.build.place} 一遍:一圈两格高的圆石墙落在她够不着的地方,它自己走到够得着的地方放;墙一格不缺,圆石按格扣。
     */
    @GameTest(template = "floor16", timeoutTicks = 1749, batch = "numen_build")
    public static void build_place_walks_the_site_and_builds_all_of_it(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_raiser", new BlockPos(1, 2, 1), false);
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        BlockPos min = helper.absolutePos(new BlockPos(7, 2, 7));
        ToolRun raise = lua(companion, "numen.build.place(numen.shape.cuboid(" + xyz(min) + ", " + xyz(min.offset(4, 1, 4))
                + "):set(\"cobblestone\"):minus(numen.shape.cuboid(" + xyz(min.offset(1, 0, 1)) + ", " + xyz(min.offset(3, 1, 3))
                + "):cells()))");

        succeedWhen(helper, () -> {
            helper.assertTrue(raise.receipt() != null, "numen.build.place has not finished");
            helper.assertTrue(raise.ranToTheEnd(), "numen.build.place failed: " + raise.receipt());
            int walls = 0;
            for (int dx = 0; dx < 5; dx++) {
                for (int dz = 0; dz < 5; dz++) {
                    boolean ring = dx == 0 || dx == 4 || dz == 0 || dz == 4;
                    for (int dy = 0; dy < 2; dy++) {
                        BlockPos p = min.offset(dx, dy, dz);
                        if (ring) {
                            walls++;
                            helper.assertTrue(level.getBlockState(p).is(Blocks.COBBLESTONE),
                                    "the wall is missing at " + p.toShortString());
                        }
                    }
                }
            }
            helper.assertTrue(companion.getInventory().countItem(Items.COBBLESTONE) == 64 - walls,
                    "the cobblestone spent does not match the cells built");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 生存模式,照模型的写法画好再开工:一圈两格高的圆石墙,再在南墙正中装一扇门(合起来时门盖掉墙的那一格)。
     * 墙一格不缺,门的上下两半都在,圆石按格扣。
     */
    @GameTest(template = "floor16", timeoutTicks = 459, batch = "numen_build")
    public static void walls_with_a_door_are_built_at_a_spot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_waller", new BlockPos(6, 2, 9), false);
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 32));
        companion.getInventory().add(new ItemStack(Items.OAK_DOOR));
        BlockPos min = helper.absolutePos(new BlockPos(5, 2, 5));
        BlockPos max = helper.absolutePos(new BlockPos(7, 3, 7));
        BlockPos door = helper.absolutePos(new BlockPos(6, 2, 7));
        // 门只写下半格:另一半由原版的放置回调自己补,和图纸那条入口同一条纪律
        ToolRun build = lua(companion, "local walls = numen.shape.layer(" + xyz(min) + ", {\"###\", \"#.#\", \"###\"}, "
                + "{[\"#\"] = \"cobblestone\"})\n"
                + "numen.build.place(walls:union(walls:shift(0, 1, 0)):union({{name = \"oak_door[facing=south]\", pos = "
                + xyz(door) + "}}))");

        succeedWhen(helper, () -> {
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

    /**
     * 生存模式料不够:有料的格放、缺的不放,回执如实说缺什么、多少;她手上的圆石一块不剩(都放了出去),放了几格就是几块。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_build")
    public static void cells_short_of_materials_place_what_is_paid_for_and_say_what_is_missing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_skint", new BlockPos(2, 2, 2), false);
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 3));
        BlockPos o = helper.absolutePos(new BlockPos(6, 2, 6));
        ToolRun build = lua(companion, "numen.build.place(numen.shape.cuboid(" + xyz(o) + ", " + xyz(o.offset(2, 0, 2))
                + "):set(\"cobblestone\"))");

        succeedWhen(helper, () -> {
            helper.assertTrue(build.done() && build.succeeded(), "a partly paid build is not a failure: " + build.outcome());
            var result = build.result(BuildCompanionTask.Placed.class);
            helper.assertTrue(BlockPos.betweenClosedStream(o, o.offset(2, 0, 2))
                            .filter(p -> level.getBlockState(p).is(Blocks.COBBLESTONE)).count() == 3
                            && companion.getInventory().countItem(Items.COBBLESTONE) == 0,
                    "she should have placed exactly the three cells her stock paid for: " + build.outcome());
            helper.assertTrue(result.unplaced().size() == 1 && result.unplaced().get(0).kind().equals("short_of_material")
                            && result.unplaced().get(0).cells() == 6
                            && result.unplaced().get(0).says().contains("cobblestone"),
                    "the six she could not pay for are not reported as short of cobblestone: " + result.unplaced());
            helper.assertTrue(result.stillShort().isPresent() && result.stillShort().get().contains("cobblestone"),
                    "the receipt does not say what is still short: " + result.stillShort());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 干着干着断料,要<b>当场</b>认账,不是溜达一圈再说。开工时料是够的,施工中途背包被人清空(她付不起剩下任何一格):走完这一遍
     * 就带着回执收场(不是失败),要快(给一个远大于"当场"的刻数上限),回执把剩下的格归在缺料下,并说清还要凑什么。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_build")
    public static void running_out_of_materials_is_reported_at_once(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_starve", new BlockPos(7, 2, 5), false);
        companion.getInventory().add(new ItemStack(Items.STONE, 9));
        List<BuildTaskRecord.Target> targets = new ArrayList<>();
        for (int x = 6; x <= 8; x++) {
            for (int z = 6; z <= 8; z++) {
                targets.add(new BuildTaskRecord.Target(Blocks.STONE, Items.STONE,
                        helper.absolutePos(new BlockPos(x, 2, z)), "stone"));
            }
        }
        var ctx = TaskDispatch.ctx("gametest-starve", companion);
        var rec = buildJob(ctx.toolCallId(), ctx.deadline(6000L), targets, true);
        TaskDispatch.setTask(companion, rec);

        long[] startTick = {level.getGameTime()};
        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(rec.placed() >= 2,
                        "she should have laid a couple of stones by now, placed=" + rec.placed()))
                .thenExecute(() -> {
                    var inv = companion.getInventory();
                    for (int i = 0; i < inv.getContainerSize(); i++) {
                        if (inv.getItem(i).is(Items.STONE)) {
                            inv.setItem(i, ItemStack.EMPTY);
                        }
                    }
                    startTick[0] = level.getGameTime();
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        rec.getState() == com.dwinovo.numen.api.task.TaskState.SUCCESS,
                        "the task should have come back with its receipt by now, state=" + rec.getState()))
                .thenExecute(() -> {
                    long spent = level.getGameTime() - startTick[0];
                    helper.assertTrue(spent <= 120,
                            "running out should be reported at once, but it took " + spent
                                    + " ticks after the stock was gone — she was wandering");
                    var receipt = (BuildCompanionTask.Placed) rec.getResult().value();
                    helper.assertTrue(receipt.unplaced().stream().anyMatch(g -> g.kind().equals("short_of_material"))
                                    && receipt.stillShort().isPresent(),
                            "the reason must be materials and must say what to gather, got: "
                                    + receipt.unplaced() + " / " + receipt.stillShort());
                })
                .thenSucceed();
    }

    /**
     * {@code numen.build.diff} 报料:剩下要放的格要花的全部物品,和生存模式下她还缺多少;免耗材时没有缺口这一项。件数与逐格实扣是同一个
     * 函数(双层砖一格两件),背包只数 36 格主背包(副手上那叠不算)。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void build_diff_prices_what_is_left_and_what_she_lacks(GameTestHelper helper) {
        BlockPos o = helper.absolutePos(new BlockPos(6, 2, 6));
        StringBuilder cells = new StringBuilder("{");
        for (BlockPos p : BlockPos.betweenClosed(o, o.offset(2, 0, 2))) {
            cells.append("{name = \"cobblestone\", pos = ").append(xyz(p)).append("}, ");
        }
        cells.append("{name = \"stone_brick_slab[type=double]\", pos = ").append(xyz(o.offset(0, 1, 0))).append("}}");
        String ask = "local d = numen.build.diff(" + cells + ")\n"
                + "return {left = d.left, cobble = d.materials.cobblestone, slab = d.materials.stone_brick_slab,"
                + " short_cobble = d.short_of and d.short_of.cobblestone, short_slab = d.short_of and d.short_of.stone_brick_slab,"
                + " has_short_of = d.short_of ~= nil}";

        NumenPlayer survivor = spawnAt(helper, "gametest_quoter", new BlockPos(2, 2, 2), false);
        survivor.getInventory().add(new ItemStack(Items.COBBLESTONE, 5));
        survivor.getInventory().add(new ItemStack(Items.STONE_BRICK_SLAB, 1));
        survivor.getInventory().offhand.set(0, new ItemStack(Items.COBBLESTONE, 64));
        NumenPlayer creator = spawnAt(helper, "gametest_quoter_creative", new BlockPos(3, 2, 2), true);
        ToolRun survival = lua(survivor, ask);
        ToolRun creative = lua(creator, ask);

        succeedWhen(helper, () -> {
            helper.assertTrue(survival.done() && creative.done(), "numen.build.diff has not answered");
            var s = survival.data().getAsJsonObject("returned");
            helper.assertTrue(s.get("left").getAsInt() == 10 && s.get("cobble").getAsInt() == 9
                            && s.get("slab").getAsInt() == 2,
                    "10 cells to do cost 9 cobblestone and 2 slabs (a double slab is two): " + survival.receipt());
            helper.assertTrue(s.get("short_cobble").getAsInt() == 4 && s.get("short_slab").getAsInt() == 1,
                    "5 cobblestone and 1 slab in the 36 slots leave 4 and 1 short; the offhand stack does not count: "
                            + survival.receipt());
            var c = creative.data().getAsJsonObject("returned");
            helper.assertTrue(c.get("cobble").getAsInt() == 9 && !c.get("has_short_of").getAsBoolean(),
                    "creative building costs nothing to be short of, but the price is still told: " + creative.receipt());
            CompanionFactory.despawn(helper.getLevel().getServer(), survivor);
            CompanionFactory.despawn(helper.getLevel().getServer(), creator);
        });
    }

    /**
     * 把她放的一格 A 换成 B:生存模式下先 {@code numen.work.dig} 拆她自己的石头
     * ({@code break(self_placed & !contents)})、再 {@code numen.build.place} 放玻璃({@code place(!hazard_item)}),主人在场,全程一张卡都不弹。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void swapping_a_block_she_placed_asks_nobody(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_swapper", new BlockPos(5, 2, 7), false);
        // 生存换一格是先把原来那块挖掉:石头要镐才挖得下
        companion.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE));
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_patron");
        companion.getInventory().add(new ItemStack(Items.STONE));
        companion.getInventory().add(new ItemStack(Items.GLASS));
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= com.dwinovo.numen.api.permission.ConsentDesk.of(companion).pending() != null);
        BlockPos o = helper.absolutePos(new BlockPos(7, 2, 7));
        String stone = "{{name = \"stone\", pos = " + xyz(o) + "}}";
        String glass = "{{name = \"glass\", pos = " + xyz(o) + "}}";
        AtomicReference<ToolRun> run = new AtomicReference<>(lua(companion, "numen.build.place(" + stone + ")"));

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(run.get().done() && run.get().succeeded(),
                        "the first build did not finish: " + run.get().outcome()))
                .thenExecute(() -> {
                    var placer = com.dwinovo.numen.api.permission.PlacedBlocks.of(level).placerAt(o, level.getBlockState(o));
                    helper.assertTrue(placer != null && placer.id().equals(companion.getUUID()),
                            "the stone she built is not recorded as hers: " + placer);
                    run.set(lua(companion, "numen.work.dig(" + xyz(o) + ")\nnumen.build.place(" + glass + ")"));
                })
                .thenWaitUntil(() -> helper.assertTrue(run.get().done(), "the swap has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(run.get().ranToTheEnd() && level.getBlockState(o).is(Blocks.GLASS),
                            "her stone was not swapped for glass: " + run.get().receipt());
                    helper.assertTrue(!asked[0], "swapping a block she placed asked the owner");
                    CompanionFactory.despawn(level.getServer(), companion);
                    leave(owner);
                })
                .thenSucceed();
    }

    /**
     * 对照:同一处换方块,但那一格的石头是主人后来亲手放的(她砌的被主人拆了重放)——拆它的
     * {@code numen.work.dig} 是 {@code break(placed)},照旧挂一条征询;主人拒绝就不换,回执说主人没答应。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void swapping_a_block_the_owner_placed_still_asks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_tactful", new BlockPos(5, 2, 7), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_landowner");
        // 生存换一格是先把原来那块挖掉:石头要镐才挖得下
        companion.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE));
        companion.getInventory().add(new ItemStack(Items.STONE));
        companion.getInventory().add(new ItemStack(Items.GLASS));
        var desk = com.dwinovo.numen.api.permission.ConsentDesk.of(companion);
        BlockPos o = helper.absolutePos(new BlockPos(7, 2, 7));
        String stone = "{{name = \"stone\", pos = " + xyz(o) + "}}";
        String glass = "{{name = \"glass\", pos = " + xyz(o) + "}}";
        AtomicReference<ToolRun> run = new AtomicReference<>(lua(companion, "numen.build.place(" + stone + ")"));
        boolean[] answered = new boolean[1];

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(run.get().done() && run.get().succeeded(),
                        "the first build did not finish: " + run.get().outcome()))
                .thenExecute(() -> {
                    // 主人拆了她那块石头,自己放回一块:这一格从此是主人的
                    level.setBlockAndUpdate(o, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(o, Blocks.STONE.defaultBlockState());
                    com.dwinovo.numen.api.permission.PlacedBlocks.placedBy(level, o, owner);
                    run.set(lua(companion, "numen.work.dig(" + xyz(o) + ")\nnumen.build.place(" + glass + ")"));
                })
                .thenWaitUntil(() -> {
                    var pending = desk.pending();
                    helper.assertTrue(pending != null, "swapping the owner's stone did not ask");
                    helper.assertTrue(level.getBlockState(o).is(Blocks.STONE), "the owner's stone went before he answered");
                    answered[0] = desk.answer(pending.id(),
                            com.dwinovo.numen.api.permission.ConsentAnswer.Decision.DENY, "");
                })
                .thenWaitUntil(() -> helper.assertTrue(run.get().done(), "the swap has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(answered[0], "the request was not answered");
                    helper.assertTrue(level.getBlockState(o).is(Blocks.STONE),
                            "the owner's stone was swapped although he said no");
                    helper.assertTrue(!run.get().ranToTheEnd() && run.get().receipt().contains("refused by the owner"),
                            "the receipt does not say the owner refused: " + run.get().receipt());
                    CompanionFactory.despawn(level.getServer(), companion);
                    leave(owner);
                })
                .thenSucceed();
    }

    /**
     * 技能里"方块朝哪"的说法在真世界里核一遍(形状在单测 {@code BlockFacingTest} 里核):楼梯高背在 {@code facing} 那一侧,
     * 南坡朝北才从南边踩得上去;梯子挂在与 {@code facing} 相反那一侧的方块上;挂着的灯笼要上面有东西;床头落在
     * {@code facing} 那一格。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_build")
    public static void block_facing_in_the_skill_holds_in_the_world(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockState stone = Blocks.STONE.defaultBlockState();

        BlockPos stair = helper.absolutePos(new BlockPos(2, 2, 2));
        level.setBlockAndUpdate(stair, Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(net.minecraft.world.level.block.StairBlock.FACING, net.minecraft.core.Direction.NORTH));
        var shape = level.getBlockState(stair).getCollisionShape(level, stair).toAabbs();
        helper.assertTrue(shape.stream().anyMatch(b -> b.contains(0.5, 0.75, 0.25))
                        && shape.stream().noneMatch(b -> b.contains(0.5, 0.75, 0.75)),
                "a north-facing stair does not have its tall back to the north: " + shape);

        BlockPos ladder = helper.absolutePos(new BlockPos(6, 2, 6));
        BlockState northLadder = Blocks.LADDER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LadderBlock.FACING, net.minecraft.core.Direction.NORTH);
        level.setBlockAndUpdate(ladder.south(), stone);
        helper.assertTrue(northLadder.canSurvive(level, ladder), "ladder[facing=north] does not hang on the block south");
        level.setBlockAndUpdate(ladder.south(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(ladder.north(), stone);
        helper.assertFalse(northLadder.canSurvive(level, ladder), "ladder[facing=north] hangs on the block north");

        BlockPos lantern = helper.absolutePos(new BlockPos(10, 2, 10));
        BlockState hanging = Blocks.LANTERN.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true);
        helper.assertFalse(hanging.canSurvive(level, lantern), "a hanging lantern holds with nothing above");
        level.setBlockAndUpdate(lantern.above(), stone);
        helper.assertTrue(hanging.canSurvive(level, lantern), "a hanging lantern does not hang from the block above");

        BlockPos foot = helper.absolutePos(new BlockPos(2, 2, 10));
        BlockState bed = Blocks.RED_BED.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.EAST);
        level.setBlockAndUpdate(foot, bed);
        bed.getBlock().setPlacedBy(level, foot, bed, null, new ItemStack(Items.RED_BED));
        helper.assertTrue(level.getBlockState(foot.east()).is(Blocks.RED_BED) && level.getBlockState(foot.east())
                        .getValue(net.minecraft.world.level.block.BedBlock.PART)
                        == net.minecraft.world.level.block.state.properties.BedPart.HEAD,
                "the head of red_bed[facing=east] is not one cell east of its foot");
        helper.succeed();
    }

    /**
     * 生存模式砌一块 5×5 的圆石平台,砌到第三格主人按停止:活按主人停止收场;停下之后不再多砌一格,
     * 背包里少掉的圆石正好是砌上去的格数。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void owner_stop_mid_build_keeps_what_is_built(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_paused_mason", new BlockPos(8, 2, 5), false);
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        BlockPos min = helper.absolutePos(new BlockPos(6, 2, 6));
        BlockPos max = helper.absolutePos(new BlockPos(10, 2, 10));
        ToolRun build = lua(companion, "numen.build.place(numen.shape.cuboid(" + xyz(min) + ", " + xyz(max)
                + "):set(\"cobblestone\"))");
        java.util.function.IntSupplier placed = () -> (int) BlockPos.betweenClosedStream(min, max)
                .filter(p -> level.getBlockState(p).is(Blocks.COBBLESTONE)).count();
        int[] atStop = new int[1];

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(placed.getAsInt() >= 3, "she has not laid three cells yet"))
                .thenExecute(() -> com.dwinovo.numen.api.task.CompanionTickDispatcher.cancelFor(companion))
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

    // ---- numen.shape 画的格:网格、转向 ----

    /**
     * 一张网格叠三层盖出一圈墙:{@code '.'} 是"这一格不管",所以屋里一格没碰;
     * 图例里每个字符自带方块状态,所以一张网格就能把朝向不同的楼梯铺在同一课上。
     */
    @GameTest(template = "floor16", timeoutTicks = 1506, batch = "numen_build")
    public static void layer_repeats_a_grid_and_leaves_dots_alone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_grid_mason", new BlockPos(7, 2, 5), true);
        BlockPos o = helper.absolutePos(new BlockPos(6, 2, 6));
        level.setBlockAndUpdate(o.offset(1, 0, 1), Blocks.GOLD_BLOCK.defaultBlockState());   // 屋里的记号
        ToolRun build = lua(companion, """
                local S = numen.shape
                local o = %s
                local ring = S.layer(o, {"###", "#.#", "###"}, {["#"] = "stone_bricks"})
                local c = ring:union(ring:shift(0, 1, 0)):union(ring:shift(0, 2, 0))
                c = c:union(S.layer(S.pos(o.x, o.y + 3, o.z), {"<<<", "...", ">>>"},
                  {["<"] = "stone_brick_stairs[facing=south]", [">"] = "stone_brick_stairs[facing=north]"}))
                numen.build.place(c)
                """.formatted(xyz(o)));

        succeedWhen(helper, () -> {
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
     * 画好的一段带楼梯的墙转半圈再放:方块跟着转,楼梯的朝向一起转过去,不是原样照抄。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void turned_cells_turn_their_stairs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_copyist", new BlockPos(10, 2, 7), true);
        BlockPos at = helper.absolutePos(new BlockPos(9, 2, 5));
        ToolRun build = lua(companion, "numen.build.place(numen.shape.cells({{name = \"stone_bricks\", pos = "
                + xyz(at) + "}, {name = \"stone_brick_stairs[facing=east]\", pos = " + xyz(at.offset(1, 0, 0))
                + "}}, " + xyz(at) + "):rotate(180))");

        succeedWhen(helper, () -> {
            helper.assertTrue(build.done() && build.succeeded(), "build failed: " + build.outcome());
            helper.assertTrue(level.getBlockState(at).is(Blocks.STONE_BRICKS), "the cell turned about moved");
            var stair = level.getBlockState(at.offset(-1, 0, 0));
            helper.assertTrue(stair.is(Blocks.STONE_BRICK_STAIRS)
                            && stair.getValue(net.minecraft.world.level.block.StairBlock.FACING)
                            == net.minecraft.core.Direction.WEST,
                    "turning half round must turn the stair to face west, got " + stair);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 一排带朝向的方块(楼梯上下两种、原木、告示牌、合成器、门、一段铁轨)用 {@code layer} 画出来,{@code rotate(90)} 之后经
     * {@code numen.build.place} 放下:每一格落在转过去的位置,方块状态逐格等于原版 {@code BlockState.rotate} 转出来的那一个。
     * 判据取原版的结果、不取我们自己的算法;格与格之间留空,楼梯的内外角、铁轨的拐弯这类跟邻居走的属性不会被邻居改掉。
     */
    @GameTest(template = "floor16", timeoutTicks = 1749, batch = "numen_build")
    public static void a_rotated_shape_is_built_as_vanilla_rotates_it(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_turner", new BlockPos(5, 2, 1), true);
        BlockPos o = helper.absolutePos(new BlockPos(3, 2, 1));
        String legend = "{A = \"oak_stairs[facing=north,half=top]\", B = \"oak_stairs[facing=east,half=bottom]\", "
                + "L = \"oak_log[axis=x]\", S = \"oak_sign[rotation=3]\", C = \"crafter[orientation=north_up]\", "
                + "D = \"oak_door[facing=east,half=lower,hinge=left,open=false]\", R = \"rail[shape=east_west]\"}";
        ToolRun build = lua(companion, "numen.build.place(numen.shape.layer(" + xyz(o) + ", {\"A.B.L.S.C.D.RRR\"}, " + legend
                + "):rotate(90))");
        String row = "A.B.L.S.C.D.RRR";
        java.util.Map<Character, String> written = java.util.Map.of('A', "oak_stairs[facing=north,half=top]",
                'B', "oak_stairs[facing=east,half=bottom]", 'L', "oak_log[axis=x]", 'S', "oak_sign[rotation=3]",
                'C', "crafter[orientation=north_up]", 'D', "oak_door[facing=east,half=lower,hinge=left,open=false]",
                'R', "rail[shape=east_west]");

        succeedWhen(helper, () -> {
            helper.assertTrue(build.done() && build.succeeded(), "build failed: " + build.outcome());
            for (int i = 0; i < row.length(); i++) {
                if (row.charAt(i) == '.') {
                    continue;
                }
                BlockState expected = com.dwinovo.numen.build.BuildStates.resolve(written.get(row.charAt(i))).state()
                        .rotate(net.minecraft.world.level.block.Rotation.CLOCKWISE_90);
                BlockPos at = o.offset(0, 0, i);
                helper.assertTrue(level.getBlockState(at) == expected,
                        "cell " + i + " (" + row.charAt(i) + ") is " + level.getBlockState(at) + ", vanilla turns it into "
                                + expected);
            }
            helper.assertTrue(level.getBlockState(o.offset(1, 0, 0)).isAir() && level.getBlockState(o.offset(0, 0, 1)).isAir(),
                    "the row stayed where it was drawn or filled the gaps");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 建完的红石要能用。施工期刻意不通知邻居(半成品世界会把贴附方块整批弹掉),
     * 代价曾经是红石线全是孤立的点、通不了电;收尾那趟"让世界自己反应一次"补的就是它。
     *
     * <p>手放与直接写都带着原版的方块更新,红石线放下去就连上;建完的电路得是通的。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void a_built_redstone_line_actually_powers_the_lamp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_sparky", new BlockPos(7, 2, 10), true);
        BlockPos o = helper.absolutePos(new BlockPos(5, 2, 8));
        ToolRun build = lua(companion, "numen.build.place(numen.shape.layer(" + xyz(o) + ", {\"B####L\"}, "
                + "{B = \"redstone_block\", [\"#\"] = \"redstone_wire\", L = \"redstone_lamp\"}))");

        // 只在"她报完工"那一刻判一次。用 succeedWhen 每刻重试的话,量到的是"最终有没有
        // 连上"——收尾补水、摆设生成、旁边的动静都可能事后把线碰连,断言迟早会过。
        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(build.done(), "build has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(build.succeeded(), "build failed: " + build.outcome());
                    var wire = level.getBlockState(o.offset(2, 0, 0));
                    helper.assertTrue(wire.is(Blocks.REDSTONE_WIRE), "the wire is not there: " + wire);
                    var east = wire.getValue(net.minecraft.world.level.block.RedStoneWireBlock.EAST);
                    var west = wire.getValue(net.minecraft.world.level.block.RedStoneWireBlock.WEST);
                    helper.assertTrue(
                            east == net.minecraft.world.level.block.state.properties.RedstoneSide.SIDE
                                    && west == net.minecraft.world.level.block.state.properties.RedstoneSide.SIDE,
                            "the wire stayed a dot: east=" + east + " west=" + west);
                    helper.assertTrue(level.getBlockState(o.offset(5, 0, 0))
                                    .getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT),
                            "the lamp did not light: the built circuit is dead");
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // 手放:她站在原地点一个面,朝向、上下半、轴向由点击决定
    // ------------------------------------------------------------------

    /** 一格要手放的样子:方块写法、用哪件物品(null 是方块自己的)、落点,以及为点出这个样子需要的一块石头(没有是 null)。 */
    private record HandCase(String spec, net.minecraft.world.item.Item item, BlockPos cell, BlockPos stone) {

        static HandCase of(String spec, int x, int y, int z) {
            return new HandCase(spec, null, new BlockPos(x, y, z), null);
        }

        static HandCase against(String spec, int x, int y, int z, int stoneX, int stoneY, int stoneZ) {
            return new HandCase(spec, null, new BlockPos(x, y, z), new BlockPos(stoneX, stoneY, stoneZ));
        }

        HandCase holding(net.minecraft.world.item.Item item) {
            return new HandCase(spec, item, cell, stone);
        }
    }

    /**
     * 生存模式、她站在 rel 8,2,8 不动,{@code numen.build.place} 一次交一串格:每一格都得放成 spec 写的样子(spec 里点名的每个状态位),
     * 背包里每样一件、放完扣光,回执说直接写进去的有 {@code written} 格。场景里的石头先放、再拉起她。
     * 石头总在格子离她更远的一侧或侧面,点它朝着格子的那一面她才看得见。
     */
    private static void placedByHand(GameTestHelper helper, String name, List<HandCase> cases, int written) {
        placedByHand(helper, name, cases, written, () -> { });
    }

    /** 同上,{@code more} 是放完之后用例自己再看的东西。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void placedByHand(GameTestHelper helper, String name, List<HandCase> cases, int written,
                                     Runnable more) {
        ServerLevel level = helper.getLevel();
        for (HandCase c : cases) {
            if (c.stone() != null) {
                place(helper, c.stone(), Blocks.STONE);
            }
        }
        NumenPlayer companion = spawnAt(helper, name, new BlockPos(8, 2, 8), false);
        List<net.minecraft.commands.arguments.blocks.BlockStateParser.BlockResult> wanted = new ArrayList<>();
        List<net.minecraft.world.item.Item> items = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        for (HandCase c : cases) {
            try {
                wanted.add(net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(), c.spec(), false));
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                throw new IllegalArgumentException(c.spec(), e);
            }
            net.minecraft.world.item.Item item = c.item() != null ? c.item()
                    : wanted.get(wanted.size() - 1).blockState().getBlock().asItem();
            items.add(item);
            companion.getInventory().add(new ItemStack(item));
            cells.add("{name = \"" + c.spec() + "\", pos = " + xyz(helper.absolutePos(c.cell())) + "}");
        }
        ToolRun[] run = {null};
        steps(helper)
                .thenExecute(() -> run[0] = lua(companion, "numen.build.place({" + String.join(", ", cells) + "})"))
                .thenWaitUntil(() -> helper.assertTrue(run[0].done(), "numen.build.place has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(run[0].succeeded(), "numen.build.place failed: " + run[0].outcome());
                    for (int i = 0; i < cases.size(); i++) {
                        BlockPos at = helper.absolutePos(cases.get(i).cell());
                        BlockState got = level.getBlockState(at);
                        var want = wanted.get(i);
                        helper.assertTrue(got.is(want.blockState().getBlock()),
                                cases.get(i).spec() + " came out as " + got + " at " + cases.get(i).cell().toShortString());
                        want.properties().forEach((property, value) -> helper.assertTrue(
                                got.getValue((net.minecraft.world.level.block.state.properties.Property) property)
                                        .equals(value),
                                "wanted " + property.getName() + "=" + value + " but got " + got));
                        helper.assertTrue(companion.getInventory().countItem(items.get(i)) == 0,
                                "the " + items.get(i) + " was not taken from her pack");
                    }
                    var result = run[0].result(BuildCompanionTask.Placed.class);
                    helper.assertTrue(result.placed() == cases.size() && result.setDirectly() == written,
                            "expected " + cases.size() + " placed, " + written + " written straight in, got "
                                    + result.placed() + " and " + result.setDirectly());
                    more.run();
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /** 楼梯四个朝向(点地面,朝向是她看过去的方向)、倒置(点石头朝着它的面的上半)、台阶上下半。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_build")
    public static void build_by_hand_stairs_and_slabs(GameTestHelper helper) {
        placedByHand(helper, "gametest_stairs", List.of(
                HandCase.of("oak_stairs[facing=north,half=bottom]", 8, 2, 6),
                HandCase.of("oak_stairs[facing=east,half=bottom]", 10, 2, 8),
                HandCase.of("oak_stairs[facing=south,half=bottom]", 8, 2, 10),
                HandCase.of("oak_stairs[facing=west,half=bottom]", 6, 2, 8),
                HandCase.against("oak_stairs[facing=north,half=top]", 9, 2, 5, 9, 2, 4),
                HandCase.against("oak_slab[type=top]", 7, 2, 5, 7, 2, 4),
                HandCase.of("oak_slab[type=bottom]", 7, 2, 6)), 0);
    }

    /** 原木三个轴(点地面是竖的,点侧面横过来)、活板门上下、火把立地与贴墙。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_build")
    public static void build_by_hand_logs_trapdoors_and_torches(GameTestHelper helper) {
        placedByHand(helper, "gametest_logs", List.of(
                HandCase.of("oak_log[axis=y]", 10, 2, 6),
                HandCase.against("oak_log[axis=x]", 11, 2, 8, 12, 2, 8),
                HandCase.against("oak_log[axis=z]", 5, 2, 8, 5, 2, 9),
                HandCase.of("oak_trapdoor[facing=south,half=bottom]", 9, 2, 6),
                HandCase.against("oak_trapdoor[facing=south,half=top]", 8, 2, 5, 8, 2, 4),
                HandCase.of("torch", 7, 2, 7),
                HandCase.against("wall_torch[facing=south]", 7, 2, 5, 7, 2, 4)
                        .holding(net.minecraft.world.item.Items.TORCH)), 0);
    }

    /** 门(合页左右由点在面上的哪一侧定)与箱子(朝着她)。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_build")
    public static void build_by_hand_doors_and_chests(GameTestHelper helper) {
        placedByHand(helper, "gametest_doors", List.of(
                HandCase.of("oak_door[facing=north,half=lower,hinge=left]", 8, 2, 6),
                HandCase.of("oak_door[facing=north,half=lower,hinge=right]", 10, 2, 5),
                HandCase.of("chest[facing=north]", 8, 2, 10)), 0);
    }

    /** 悬空的一格没有面可点:直接写进去,回执数出一格;脚下有地的那一格照常手放,不算在里面。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_build")
    public static void build_floating_cell_is_written_straight_in(GameTestHelper helper) {
        placedByHand(helper, "gametest_floating", List.of(
                HandCase.of("stone", 8, 2, 6),
                HandCase.of("stone", 8, 5, 5)), 1);
    }

    /** 红石线一串放下去:邻居当场按原版规则连上,不留一个个孤点。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_build")
    public static void build_redstone_wire_row_is_connected(GameTestHelper helper) {
        placedByHand(helper, "gametest_wires", List.of(
                HandCase.of("redstone_wire", 6, 2, 6), HandCase.of("redstone_wire", 7, 2, 6),
                HandCase.of("redstone_wire", 8, 2, 6), HandCase.of("redstone_wire", 9, 2, 6)), 0, () -> {
                    var side = net.minecraft.world.level.block.state.properties.RedstoneSide.SIDE;
                    var level = helper.getLevel();
                    for (int x = 6; x <= 9; x++) {
                        var wire = level.getBlockState(helper.absolutePos(new BlockPos(x, 2, 6)));
                        var east = wire.getValue(net.minecraft.world.level.block.RedStoneWireBlock.EAST);
                        var west = wire.getValue(net.minecraft.world.level.block.RedStoneWireBlock.WEST);
                        helper.assertTrue(x == 9 || east == side, "the wire at x=" + x + " is not joined to the east: " + wire);
                        helper.assertTrue(x == 6 || west == side, "the wire at x=" + x + " is not joined to the west: " + wire);
                    }
                });
    }

    /** 一排玻璃板放完,互相连着。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_build")
    public static void build_glass_pane_row_is_joined(GameTestHelper helper) {
        placedByHand(helper, "gametest_panes", List.of(
                HandCase.of("glass_pane", 6, 2, 6), HandCase.of("glass_pane", 7, 2, 6),
                HandCase.of("glass_pane", 8, 2, 6), HandCase.of("glass_pane", 9, 2, 6)), 0, () -> {
                    var level = helper.getLevel();
                    for (int x = 6; x <= 9; x++) {
                        var pane = level.getBlockState(helper.absolutePos(new BlockPos(x, 2, 6)));
                        helper.assertTrue(x == 9 || pane.getValue(net.minecraft.world.level.block.IronBarsBlock.EAST),
                                "the pane at x=" + x + " is not joined to the east: " + pane);
                        helper.assertTrue(x == 6 || pane.getValue(net.minecraft.world.level.block.IronBarsBlock.WEST),
                                "the pane at x=" + x + " is not joined to the west: " + pane);
                    }
                });
    }

    /**
     * 火把在空中、贴墙火把没有墙:放下去原版就弹掉。两格都不算完成,回执的 {@code unplaced} 把它们归在 {@code did_not_stay} 下,说明点出
     * 是哪种方块、在哪一格、它周围六格是什么,每一格只轮到一次(没有放第二遍)。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_build")
    public static void build_reports_cells_the_world_did_not_keep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_torches", new BlockPos(8, 2, 8), false);
        companion.getInventory().add(new ItemStack(Items.TORCH, 2));
        BlockPos air = helper.absolutePos(new BlockPos(8, 5, 6));
        BlockPos wall = helper.absolutePos(new BlockPos(8, 2, 5));
        ToolRun[] run = {null};
        steps(helper)
                .thenExecute(() -> run[0] = lua(companion, "numen.build.place({{name = \"torch\", pos = " + xyz(air)
                        + "}, {name = \"wall_torch[facing=south]\", pos = " + xyz(wall) + "}})"))
                .thenWaitUntil(() -> helper.assertTrue(run[0].done(), "numen.build.place has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(run[0].succeeded(), "a partial build is not a failure: " + run[0].outcome());
                    var result = run[0].result(BuildCompanionTask.Placed.class);
                    helper.assertTrue(result.completed() == 0 && result.left() == 2 && result.placed() == 2,
                            "the receipt does not say two were placed and none stayed: " + run[0].outcome());
                    helper.assertTrue(result.unplaced().size() == 1
                                    && result.unplaced().get(0).kind().equals("did_not_stay")
                                    && result.unplaced().get(0).cells() == 2,
                            "the cells that did not stay are not one group of two: " + result.unplaced());
                    String says = result.unplaced().get(0).says();
                    helper.assertTrue(says.contains("torch at " + air.getX() + "," + air.getY() + "," + air.getZ())
                                    && says.contains("wall_torch at " + wall.getX() + "," + wall.getY() + "," + wall.getZ())
                                    && says.contains("nothing holds it") && says.contains("down "),
                            "the sentence does not name the blocks, where, and what is around them: " + says);
                    helper.assertTrue(!level.getBlockState(air).is(Blocks.TORCH) && !level.getBlockState(wall).is(Blocks.WALL_TORCH),
                            "a torch with nothing to hold it stayed in the world");
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /** 生存模式下一格立着别的方块:别的格照放,那一格归在 {@code dig_first} 下,说明点出挡着的方块与位置,下一步是挖它的那一行。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_build")
    public static void build_reports_the_block_to_dig_first(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos dirt = helper.absolutePos(new BlockPos(8, 2, 6));
        level.setBlockAndUpdate(dirt, Blocks.DIRT.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_digfirst", new BlockPos(8, 2, 8), false);
        companion.getInventory().add(new ItemStack(Items.STONE, 2));
        BlockPos free = helper.absolutePos(new BlockPos(10, 2, 8));
        ToolRun[] run = {null};
        steps(helper)
                .thenExecute(() -> run[0] = lua(companion, "numen.build.place({{name = \"stone\", pos = " + xyz(free)
                        + "}, {name = \"stone\", pos = " + xyz(dirt) + "}})"))
                .thenWaitUntil(() -> helper.assertTrue(run[0].done(), "numen.build.place has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(run[0].succeeded(), "a partial build is not a failure: " + run[0].outcome());
                    var result = run[0].result(BuildCompanionTask.Placed.class);
                    helper.assertTrue(level.getBlockState(free).is(Blocks.STONE) && result.completed() == 1,
                            "the free cell was not placed: " + run[0].outcome());
                    helper.assertTrue(result.unplaced().size() == 1
                                    && result.unplaced().get(0).kind().equals("dig_first")
                                    && result.unplaced().get(0).first().equals(List.of(dirt)),
                            "the dirt cell is not the one group of dig_first: " + result.unplaced());
                    var group = result.unplaced().get(0);
                    helper.assertTrue(group.says().contains("dirt") && group.says().contains(
                                    dirt.getX() + "," + dirt.getY() + "," + dirt.getZ()),
                            "the sentence does not name the dirt and where it is: " + group.says());
                    helper.assertTrue(group.hint().isPresent() && group.hint().get().contains("numen.work.dig("),
                            "no dig hint: " + group.hint());
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * 一次 {@code numen.build.place} 里先放墙、再去放墙另一侧够不着的一格:走过去的路线不挖刚放好的墙。墙两格高、七格长横在她和
     * 那一格之间,创造模式挖一块几乎不花时间,路线若把刚放下的墙当地形,一定直接挖穿——绕开靠的是放置记号。
     */
    @GameTest(template = "floor16", timeoutTicks = 800, batch = "numen_build")
    public static void build_walks_round_the_wall_it_just_placed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_roundabout", new BlockPos(8, 2, 5), true);
        BlockPos beyond = helper.absolutePos(new BlockPos(8, 2, 13));
        List<BlockPos> wall = new ArrayList<>();
        StringBuilder cells = new StringBuilder("{");
        for (int x = 5; x <= 11; x++) {
            for (int y = 2; y <= 3; y++) {
                BlockPos cell = helper.absolutePos(new BlockPos(x, y, 8));
                wall.add(cell);
                cells.append("{name = \"stone_bricks\", pos = ").append(xyz(cell)).append("}, ");
            }
        }
        cells.append("{name = \"stone\", pos = ").append(xyz(beyond)).append("}}");
        ToolRun[] run = {null};
        steps(helper)
                .thenExecute(() -> run[0] = lua(companion, "numen.build.place(" + cells + ")"))
                .thenWaitUntil(() -> helper.assertTrue(run[0].done(), "numen.build.place has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(run[0].succeeded(), "numen.build.place failed: " + run[0].outcome());
                    for (BlockPos cell : wall) {
                        helper.assertTrue(level.getBlockState(cell).is(Blocks.STONE_BRICKS),
                                "the wall she had just placed has a gap at " + cell.toShortString() + ": " + run[0].outcome());
                    }
                    helper.assertTrue(level.getBlockState(beyond).is(Blocks.STONE),
                            "the cell beyond the wall was not placed: " + run[0].outcome());
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * 没有自己物品的方块(盆栽):照常进设计、不让整次调用抛错,别的格照放;它归在 {@code no_item} 下,说明点出它在哪、它是花盆里种了哪株花、
     * 该先放什么。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_build")
    public static void build_reports_a_block_no_item_can_place(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_potter", new BlockPos(8, 2, 8), false);
        companion.getInventory().add(new ItemStack(Items.STONE, 2));
        BlockPos stone = helper.absolutePos(new BlockPos(8, 2, 6));
        BlockPos pot = helper.absolutePos(new BlockPos(10, 2, 6));
        ToolRun[] run = {null};
        steps(helper)
                .thenExecute(() -> run[0] = lua(companion, "numen.build.place({{name = \"stone\", pos = " + xyz(stone)
                        + "}, {name = \"potted_poppy\", pos = " + xyz(pot) + "}})"))
                .thenWaitUntil(() -> helper.assertTrue(run[0].done(), "numen.build.place has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(run[0].succeeded(), "one cell no item can place failed the whole call: " + run[0].outcome());
                    helper.assertTrue(level.getBlockState(stone).is(Blocks.STONE), "the other cell was not placed");
                    var groups = run[0].result(BuildCompanionTask.Placed.class).unplaced();
                    helper.assertTrue(groups.size() == 1 && groups.get(0).kind().equals("no_item")
                                    && groups.get(0).first().equals(List.of(pot)),
                            "the potted plant is not the one no_item group: " + groups);
                    String says = groups.get(0).says();
                    helper.assertTrue(says.contains("potted_poppy") && says.contains("flower_pot") && says.contains("poppy")
                                    && says.contains(pot.getX() + "," + pot.getY() + "," + pot.getZ()),
                            "the sentence does not say which block, where, and what it is made of: " + says);
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * "够得着"只有一个判据:手点得着。她站在 rel 8,2,8,一格在 11,2,9(面的最近点离眼睛三米多)、一格在 12,2,10(整块包围盒量够得着,
     * 可它朝下的那一面最近的点离眼睛 4.14 格,超出 4.5 减去站位余量,手点不着)。{@code numen.build.diff} 只数前一格够得着、后一格不算;
     * {@code numen.build.place} 走一步到手点得着的地方,两格都手放,没有一格是直接写进去的。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_build")
    public static void build_reach_means_a_hand_can_click(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_clicker", new BlockPos(8, 2, 8), false);
        companion.getInventory().add(new ItemStack(Items.STONE, 2));
        BlockPos near = helper.absolutePos(new BlockPos(11, 2, 9));
        BlockPos edge = helper.absolutePos(new BlockPos(12, 2, 10));
        String cells = "{{name = \"stone\", pos = " + xyz(near) + "}, {name = \"stone\", pos = " + xyz(edge) + "}}";
        ToolRun[] runs = {null, null};
        steps(helper)
                .thenExecute(() -> runs[0] = lua(companion, "return numen.build.diff(" + cells + ")"))
                .thenWaitUntil(() -> helper.assertTrue(runs[0].receipt() != null, "numen.build.diff has not finished"))
                .thenExecute(() -> {
                    var got = runs[0].data().getAsJsonObject("returned");
                    helper.assertTrue(got.get("reach").getAsInt() == 1 && got.get("far").getAsInt() == 1,
                            "diff does not count only the cell a hand can click as within reach: " + got);
                    runs[1] = lua(companion, "numen.build.place(" + cells + ")");
                })
                .thenWaitUntil(() -> helper.assertTrue(runs[1].done(), "numen.build.place has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(runs[1].succeeded(), "numen.build.place failed: " + runs[1].outcome());
                    var result = runs[1].result(BuildCompanionTask.Placed.class);
                    helper.assertTrue(level.getBlockState(near).is(Blocks.STONE) && level.getBlockState(edge).is(Blocks.STONE)
                                    && result.setDirectly() == 0,
                            "both cells should go in by hand, none written straight in: " + runs[1].outcome());
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

}
