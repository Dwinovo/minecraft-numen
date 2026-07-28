package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.tools.BlockActionTools;
import com.dwinovo.numen.core.task.BuildTaskRecord;
import net.minecraft.world.level.block.Blocks;
import java.util.ArrayList;
import com.dwinovo.numen.core.tools.MovementTools;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 同伴行为的游戏内自动化用例(无头 gameTestServer 运行,{@code gradlew :neoforge:runGameTestServer}):
 * 在结构模板圈出的场地里,用真实的生成路径拉起同伴、经真实任务队列下发指令,按 tick 轮询断言
 * 世界状态——退出码 = 失败用例数,可直接进 CI。
 *
 * <p>结构模板以 SNBT 文本存于仓库 {@code neoforge/gameteststructures/}(运行配置经系统属性
 * {@code numen.gametest.structures} 指路),不提交二进制 .nbt。注意两件事:模板必须是
 * gametest 的"打包" SNBT 形态(palette 为字符串、方块表叫 {@code data}——裸结构 NBT 形态
 * 会被 {@code NbtUtils.unpackStructureTemplate} 静默丢弃,一块不放);且模板方块落位在
 * {@code 测试原点+1+rel},而 {@link GameTestHelper#absolutePos} 只加 {@code rel}——引用
 * 模板内 rel y 的格子时要再 +1。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class CompanionGameTests {

    static {
        String dir = System.getProperty("numen.gametest.structures");
        if (dir != null) {
            StructureUtils.testStructuresDir = dir;
        }
    }

    /**
     * 冒烟:同伴能在测试世界里存活并走完一段路。验证的是整条链路——假玩家生成
     * (载档→入场→落位)、任务入队、后台 A* 搜索、逐 tick 执行——在无头环境下全通。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_smoke")
    public static void companion_goto(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spawn = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 13));

        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                "gametest_scout", UUID.randomUUID(), level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));

        TaskRecord record = (TaskRecord) new MovementTools().moveTo(
                (double) target.getX(), (double) target.getY(), (double) target.getZ(), null,
                TaskDispatch.ctx("gametest-goto", companion));
        TaskDispatch.enqueue(companion, record, reply -> {});

        helper.succeedWhen(() -> {
            helper.assertTrue(companion.blockPosition().distSqr(target) <= 2 * 2,
                    "companion has not reached the goto target");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    // ==================== 真实地形挖掘用例(模板取自实际存档地形)====================

    /** 挖掘批次前置:和平难度 + 正午,排除怪物袭扰与昼夜随机性。 */
    @BeforeBatch(batch = "numen_mine")
    public static void prepareMineBatch(ServerLevel level) {
        level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
        level.setDayTime(6000);
    }

    /**
     * 真实云杉林(高树场景):手持铁斧砍 8 根原木。
     *
     * <p>超时按游戏刻给得很宽:无头测试服不限速(数百 tps),而寻路搜索预算是墙钟毫秒——
     * 一次 200ms 的真实搜索在这里折合上百游戏刻,超时必须覆盖"搜索墙钟 × tps"的放大。走完整生产链路——目标索引注册与
     * 查询、复合站位、眼及就地挖掘、探底波段、掉落拾取、背包计数。
     */
    @GameTest(template = "real_spruce_forest", timeoutTicks = 100000, batch = "numen_mine")
    public static void mine_spruce_forest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spawn = helper.absolutePos(new BlockPos(1, 15, 1));
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                "gametest_logger", UUID.randomUUID(), level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));

        TaskRecord record = new BlockActionTools().autoMine(
                List.of("minecraft:spruce_log"), 8, TaskDispatch.ctx("gametest-mine", companion));
        TaskDispatch.dispatchAsync(companion, record, reply -> {});

        helper.succeedWhen(() -> {
            helper.assertTrue(companion.getInventory().countItem(Items.SPRUCE_LOG) >= 8,
                    "companion has not gathered 8 spruce logs");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    // ==================== 建造用例 ====================

    /** 建造批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_build")
    public static void prepareBuildBatch(ServerLevel level) {
        level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
        level.setDayTime(6000);
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
                    helper.absolutePos(rel), "cobblestone", null, null, null));
        }
        var ctx = TaskDispatch.ctx("gametest-build", companion);
        long deadline = ctx.deadline(Math.max(1200L, targets.size() * 400L));
        TaskDispatch.dispatchAsync(companion,
                new BuildTaskRecord(ctx.toolCallId(), deadline, targets, true, 0), reply -> {});

        List<BlockPos> cells = targets.stream().map(BuildTaskRecord.Target::pos).toList();
        helper.succeedWhen(() -> {
            for (BlockPos cell : cells) {
                helper.assertTrue(level.getBlockState(cell).is(Blocks.COBBLESTONE),
                        "structure incomplete at " + cell.toShortString());
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** rel 起点 + 尺寸圈出的长方体格集;hollow = 只留外壳。 */
    private static List<BlockPos> boxCells(BlockPos origin, int sx, int sy, int sz, boolean hollow) {
        List<BlockPos> cells = new ArrayList<>();
        for (int dy = 0; dy < sy; dy++) {
            for (int dx = 0; dx < sx; dx++) {
                for (int dz = 0; dz < sz; dz++) {
                    if (hollow && dx != 0 && dx != sx - 1 && dy != 0 && dy != sy - 1
                            && dz != 0 && dz != sz - 1) {
                        continue;
                    }
                    cells.add(origin.offset(dx, dy, dz));
                }
            }
        }
        return cells;
    }

    /** 形状 DSL:空心圆柱(半径 3、高 4 的塔筒)。几何由 build_shape 的展开器
     *  生成,走常规建造任务(消耗材料),验"搭积木"路线的地基。 */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_build")
    public static void build_shape_cylinder(GameTestHelper helper) {
        BlockPos center = new BlockPos(10, 2, 10);
        List<BlockPos> rel = com.dwinovo.numen.core.tools.BuildTool.shapeCells(
                "cylinder", true, center.getX(), center.getY(), center.getZ(),
                null, null, null, 3, 4);
        runBuildCase(helper, "gametest_mason2", rel, 2);
    }

    /**
     * 守则驱动的中世纪小屋(12x10x8):形状打底(圆石地基、橡木板空心墙、逐层
     * 收分的实心屋顶层——山墙天然填实;出檐待仰放站位落地后恢复)+ 精确格修饰(原木角柱 axis=y、
     * 南面 1x2 门洞、玻璃窗、屋内火把),后写覆盖先写——与 build 工具的混排语义
     * 完全一致,免材料模式。
     */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_build_heavy")
    public static void build_medieval_cottage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spawn = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                "gametest_carpenter", UUID.randomUUID(), level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        // 免材料只免建材;寻路上墙上顶的脚手架是真实放置,得有料
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));

        java.util.function.BiFunction<String, List<BlockPos>, List<BuildTaskRecord.Target>> vol =
                (id, cells) -> {
                    var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .get(net.minecraft.resources.ResourceLocation.parse(id));
                    var block = item instanceof net.minecraft.world.item.BlockItem bi
                            ? bi.getBlock() : Blocks.AIR;
                    List<BuildTaskRecord.Target> out = new ArrayList<>();
                    for (BlockPos rel : cells) {
                        out.add(new BuildTaskRecord.Target(block, item, helper.absolutePos(rel),
                                id, null, null, null));
                    }
                    return out;
                };
        var shape = com.dwinovo.numen.core.tools.BuildTool.class;   // shapeCells 静态引用可读性别名

        List<BuildTaskRecord.Target> ordered = new ArrayList<>();
        // 1) 地基:圆石 12x1x10
        ordered.addAll(vol.apply("minecraft:cobblestone",
                com.dwinovo.numen.core.tools.BuildTool.shapeCells("box", false, 4, 2, 5, 15, 2, 14, null, null)));
        // 2) 墙体:橡木板空心盒 12x4x10(y3-6,顶面自成阁楼板)
        ordered.addAll(vol.apply("minecraft:oak_planks",
                com.dwinovo.numen.core.tools.BuildTool.shapeCells("box", true, 4, 3, 5, 15, 6, 14, null, null)));
        // 3) 屋顶:x 向每层收 2、z 向出檐 1 的实心层——山墙同步填实
        ordered.addAll(vol.apply("minecraft:oak_planks",
                com.dwinovo.numen.core.tools.BuildTool.shapeCells("box", false, 4, 7, 5, 15, 7, 14, null, null)));
        ordered.addAll(vol.apply("minecraft:oak_planks",
                com.dwinovo.numen.core.tools.BuildTool.shapeCells("box", false, 5, 8, 5, 14, 8, 14, null, null)));
        ordered.addAll(vol.apply("minecraft:oak_planks",
                com.dwinovo.numen.core.tools.BuildTool.shapeCells("box", false, 7, 9, 5, 12, 9, 14, null, null)));
        ordered.addAll(vol.apply("minecraft:oak_planks",
                com.dwinovo.numen.core.tools.BuildTool.shapeCells("box", false, 9, 10, 5, 10, 10, 14, null, null)));
        // 4) 细节(后写覆盖先写):四角原木柱、南门洞 1x2、四扇玻璃窗、屋内火把
        for (int[] c : new int[][]{{4, 5}, {15, 5}, {4, 14}, {15, 14}}) {
            for (int y = 3; y <= 6; y++) {
                ordered.add(new BuildTaskRecord.Target(Blocks.OAK_LOG, Items.OAK_LOG,
                        helper.absolutePos(new BlockPos(c[0], y, c[1])), "oak_log",
                        null, net.minecraft.core.Direction.Axis.Y, null));
            }
        }
        ordered.addAll(vol.apply("minecraft:air",
                List.of(new BlockPos(9, 3, 5), new BlockPos(9, 4, 5))));
        ordered.addAll(vol.apply("minecraft:glass_pane",
                List.of(new BlockPos(4, 4, 8), new BlockPos(4, 4, 11),
                        new BlockPos(15, 4, 8), new BlockPos(15, 4, 11))));
        ordered.addAll(vol.apply("minecraft:torch", List.of(new BlockPos(9, 3, 9))));

        // 与 build 工具同语义:同格后写覆盖先写
        java.util.LinkedHashMap<Long, BuildTaskRecord.Target> byPos = new java.util.LinkedHashMap<>();
        for (BuildTaskRecord.Target t : ordered) {
            byPos.put(t.pos().asLong(), t);
        }
        List<BuildTaskRecord.Target> targets = new ArrayList<>(byPos.values());

        var ctx = TaskDispatch.ctx("gametest-cottage", companion);
        long deadline = ctx.deadline(Math.max(2400L, targets.size() * 400L));
        TaskDispatch.dispatchAsync(companion, new BuildTaskRecord(ctx.toolCallId(), deadline,
                targets, true, 0, false), reply -> {});

        helper.succeedWhen(() -> {
            for (BuildTaskRecord.Target target : targets) {
                helper.assertTrue(target.matches(level.getBlockState(target.pos())),
                        "cottage cell mismatch at " + target.pos().toShortString()
                                + " want " + target.desiredState());
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    // ==================== 蓝图用例 ====================

    /** 蓝图批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_blueprint")
    public static void prepareBlueprintBatch(ServerLevel level) {
        level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
        level.setDayTime(6000);
    }

    /** 重型建造批次前置(与轻型批分开,别让六个建造者同时抢搜索池——
     *  生产环境是 20tps 一两个同伴,测试没必要用数量级更苛的并发打自己)。 */
    @BeforeBatch(batch = "numen_build_heavy")
    public static void prepareHeavyBuildBatch(ServerLevel level) {
        level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
        level.setDayTime(6000);
    }

    /**
     * 经典蓝图:运行时从原版资源里取雪屋顶屋(igloo/top,7x5x8——雪墙、冰窗、
     * 木门、床、火把、熔炉、工作台俱全),写成 config/numen/blueprints 下的 .nbt,
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
        TaskDispatch.dispatchAsync(companion, new BuildTaskRecord(ctx.toolCallId(), deadline,
                loaded.targets(), true, 0, false), reply -> {});

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

    /**
     * 真实深板岩矿袋(袋内 26 颗钻石矿):站在顶面,手持铁镐向下挖入,采得 2 颗钻石。
     * 覆盖埋矿的挖入站位语义与索引查询。
     */
    @GameTest(template = "real_diamond_pocket", timeoutTicks = 100000, batch = "numen_mine")
    public static void mine_diamond_pocket(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spawn = helper.absolutePos(new BlockPos(8, 17, 8));
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                "gametest_miner", UUID.randomUUID(), level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        companion.getInventory().add(new ItemStack(Items.IRON_PICKAXE));

        TaskRecord record = new BlockActionTools().autoMine(
                List.of("minecraft:deepslate_diamond_ore"), 2, TaskDispatch.ctx("gametest-mine", companion));
        TaskDispatch.dispatchAsync(companion, record, reply -> {});

        helper.succeedWhen(() -> {
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) >= 2,
                    "companion has not gathered 2 diamonds");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }
}
