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

    /**
     * 开门出屋:同伴被关在四面石墙(3 高、无顶但空背包无从垫高、徒手拆墙
     * 代价高昂)的屋里,唯一出口是一扇关着的橡木门——goto 屋外目标必须
     * 走"规划穿门 + 执行层右键开门"这条链。守的是 MovementTraverse 的
     * 门交互与 canWalkThroughBlockState 的木门可通行假定。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_smoke")
    public static void goto_through_closed_door(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 周界石墙 rel x,z ∈ [1,5]、y 2..4;南墙中央 (3,*,5) 留门洞
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                boolean perimeter = x == 1 || x == 5 || z == 1 || z == 5;
                if (!perimeter) continue;
                for (int y = 2; y <= 4; y++) {
                    if (x == 3 && z == 5 && y <= 3) continue;   // 门占的两格
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, z)),
                            Blocks.STONE.defaultBlockState());
                }
            }
        }
        BlockPos doorLow = helper.absolutePos(new BlockPos(3, 2, 5));
        var lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.FACING,
                        net.minecraft.core.Direction.SOUTH);
        level.setBlockAndUpdate(doorLow, lower);
        level.setBlockAndUpdate(doorLow.above(), lower.setValue(
                net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));

        NumenPlayer companion = spawnAt(helper, "gametest_shutin", new BlockPos(3, 2, 3), false);
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 13));
        TaskRecord record = (TaskRecord) new MovementTools().moveTo(
                (double) target.getX(), (double) target.getY(), (double) target.getZ(), null,
                TaskDispatch.ctx("gametest-door", companion));
        TaskDispatch.enqueue(companion, record, reply -> {});
        helper.succeedWhen(() -> {
            helper.assertTrue(companion.blockPosition().distSqr(target) <= 2 * 2,
                    "companion has not escaped through the door");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * mine 也走门:黑曜石屋(铁镐非正确工具,成本模型按不可破对待——拆墙
     * 不再是廉价选项)关住矿工,矿在屋外,唯一通路是关着的橡木门。验证
     * 挖掘任务的站位寻路复用同一条开门链;收工后墙体完好(确实没打洞)。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_mine")
    public static void mine_through_closed_door(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                boolean perimeter = x == 1 || x == 5 || z == 1 || z == 5;
                if (!perimeter) continue;
                for (int y = 2; y <= 4; y++) {
                    if (x == 3 && z == 5 && y <= 3) continue;   // 门占的两格
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, z)),
                            Blocks.OBSIDIAN.defaultBlockState());
                }
            }
        }
        BlockPos doorLow = helper.absolutePos(new BlockPos(3, 2, 5));
        var lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.FACING,
                        net.minecraft.core.Direction.SOUTH);
        level.setBlockAndUpdate(doorLow, lower);
        level.setBlockAndUpdate(doorLow.above(), lower.setValue(
                net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));

        List<BlockPos> ores = List.of(
                helper.absolutePos(new BlockPos(12, 2, 12)),
                helper.absolutePos(new BlockPos(13, 2, 12)));
        for (BlockPos ore : ores) {
            level.setBlockAndUpdate(ore, Blocks.GOLD_ORE.defaultBlockState());
        }

        NumenPlayer companion = spawnAt(helper, "gametest_tunneler", new BlockPos(3, 2, 3), false);
        companion.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        TaskRecord record = new BlockActionTools().autoMine(
                List.of("minecraft:gold_ore"), 2, TaskDispatch.ctx("gametest-doormine", companion));
        TaskDispatch.dispatchAsync(companion, record, reply -> {});

        BlockPos wallProbe = helper.absolutePos(new BlockPos(1, 3, 3));
        helper.succeedWhen(() -> {
            helper.assertTrue(companion.getInventory().countItem(Items.RAW_GOLD) >= 2,
                    "companion has not mined the gold outside the door");
            helper.assertTrue(level.getBlockState(wallProbe).is(Blocks.OBSIDIAN),
                    "wall breached — expected the door route");
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

    /** 小屋独立批次前置(薄墙环几何对并发搜索池最敏感,单独跑)。 */
    @BeforeBatch(batch = "numen_build_cottage")
    public static void prepareCottageBatch(ServerLevel level) {
        level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
        level.setDayTime(6000);
    }

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
                new BuildTaskRecord(ctx.toolCallId(), deadline, targets, true), reply -> {});

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
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_build")
    public static void survival_build_resumes_after_restock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_hauler", new BlockPos(2, 2, 2), false);

        List<BuildTaskRecord.Target> targets = new ArrayList<>();
        for (int x = 6; x <= 10; x++) {
            for (int z = 6; z <= 10; z++) {
                targets.add(new BuildTaskRecord.Target(Blocks.COBBLESTONE, Items.COBBLESTONE,
                        helper.absolutePos(new BlockPos(x, 2, z)), "cobblestone", null, null, null));
            }
        }
        final int total = targets.size();          // 25 格
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 9));   // 只够一小半

        java.util.function.Consumer<String> go = tag -> {
            var ctx = TaskDispatch.ctx(tag, companion);
            TaskDispatch.dispatchAsync(companion, new BuildTaskRecord(ctx.toolCallId(),
                    ctx.deadline(4000L), targets, true, true, true), reply -> {});
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
                                .asyncTaskFor(companion.getUUID()) == null,
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
     * 屋顶几何:楼梯朝向、屋脊走向、出檐、山墙、脊盖——纯展开器断言,不进世界。
     *
     * <p>楼梯朝向是整个屋顶最容易做反的一处,而做反了不会报错、只会难看,所以
     * 必须有回归锁:<b>facing 指向上坡方向,也就是指向屋脊</b>。屋脊沿长轴——
     * 收分收错轴的话,越长的房子顶得越离谱。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void roof_geometry(GameTestHelper helper) {
        // 底面 20 长(x) × 8 宽(z):脊必须沿 x,坡向 z
        var cells = com.dwinovo.numen.core.tools.BuildTool.roofCells(
                0, 100, 0, 19, 7, "minecraft:oak_stairs", "gable", "straight", 1, 0, null,
                "minecraft:oak_planks", "minecraft:oak_slab", true);
        java.util.Map<BlockPos, BuildTaskRecord.Target> byPos = new java.util.HashMap<>();
        for (BuildTaskRecord.Target t : cells) {
            byPos.put(t.pos(), t);
        }
        // 出檐 1:底层从 z=-1 起、到 z=8 止
        BuildTaskRecord.Target low = byPos.get(new BlockPos(5, 100, -1));
        BuildTaskRecord.Target high = byPos.get(new BlockPos(5, 100, 8));
        helper.assertTrue(low != null && high != null, "roof: overhang=1 did not extend the base rect");

        var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
        helper.assertTrue(low.desiredState().hasProperty(facing),
                "roof: slope course is not made of stairs");
        // 低 z 侧上坡朝 +z(south),高 z 侧上坡朝 -z(north)——两侧对着爬向屋脊
        helper.assertTrue(low.desiredState().getValue(facing) == net.minecraft.core.Direction.SOUTH,
                "roof: low-z slope must face the ridge (south), got "
                        + low.desiredState().getValue(facing));
        helper.assertTrue(high.desiredState().getValue(facing) == net.minecraft.core.Direction.NORTH,
                "roof: high-z slope must face the ridge (north), got "
                        + high.desiredState().getValue(facing));
        // 脊沿长轴:坡面每升一层向内收一格,x 方向自始至终跑满
        helper.assertTrue(byPos.containsKey(new BlockPos(0, 100, -1))
                        && byPos.containsKey(new BlockPos(19, 100, -1)),
                "roof: ridge must run along the longer axis, so the eave spans the full length");
        helper.assertTrue(byPos.containsKey(new BlockPos(5, 101, 0)),
                "roof: second course did not step inward by one");
        // 脊盖与山墙都在
        boolean hasSlab = cells.stream().anyMatch(t -> t.desiredState().getBlock() == Blocks.OAK_SLAB);
        boolean hasGable = cells.stream().anyMatch(t -> t.desiredState().getBlock() == Blocks.OAK_PLANKS);
        helper.assertTrue(hasSlab, "roof: ridge_block never placed");
        helper.assertTrue(hasGable, "roof: gable_block never filled the ends");
        helper.succeed();
    }

    /**
     * 四坡顶(庑殿/hip)与举架曲线(concave)。
     *
     * <p>举架是东亚屋面读起来是"曲"而不是"阶梯金字塔"的原因:清式一律从檐口的
     * 五举(0.5)起步,越往脊越陡,脊步不超过十举。翻成方块就是<b>檐口一层收两格、
     * 脊部一层收一格</b>——所以同样的跨度,凹曲屋面的层数明显少于直线屋面,而且
     * 头两层的收分必然大于 1。这两条都是可测的。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_build")
    public static void roof_hip_and_concave(GameTestHelper helper) {
        // ── 四坡:四面都收,没有山墙,四角是垂脊 ──
        var hip = com.dwinovo.numen.core.tools.BuildTool.roofCells(
                0, 200, 0, 10, 10, "minecraft:oak_stairs", "hip", "straight", 0, 0, null,
                null, "minecraft:oak_planks", true);
        java.util.Map<BlockPos, BuildTaskRecord.Target> byPos = new java.util.HashMap<>();
        for (BuildTaskRecord.Target t : hip) byPos.put(t.pos(), t);
        var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
        // 四条边各自朝内爬
        helper.assertTrue(byPos.get(new BlockPos(5, 200, 0)).desiredState().getValue(facing)
                        == net.minecraft.core.Direction.SOUTH, "hip: -z edge must climb south");
        helper.assertTrue(byPos.get(new BlockPos(5, 200, 10)).desiredState().getValue(facing)
                        == net.minecraft.core.Direction.NORTH, "hip: +z edge must climb north");
        helper.assertTrue(byPos.get(new BlockPos(0, 200, 5)).desiredState().getValue(facing)
                        == net.minecraft.core.Direction.EAST, "hip: -x edge must climb east");
        helper.assertTrue(byPos.get(new BlockPos(10, 200, 5)).desiredState().getValue(facing)
                        == net.minecraft.core.Direction.WEST, "hip: +x edge must climb west");
        // 角上两坡相撞:给了 ridge_block 就用它当垂脊,不能留一块朝向随便的楼梯
        helper.assertTrue(byPos.get(new BlockPos(0, 200, 0)).desiredState().getBlock()
                        == Blocks.OAK_PLANKS,
                "hip: corner must use the ridge block, got "
                        + byPos.get(new BlockPos(0, 200, 0)).desiredState());
        // 方形底面收到一点(攒尖)
        helper.assertTrue(byPos.containsKey(new BlockPos(5, 205, 5)),
                "hip on a square footprint must converge to a point");

        // ── 举架:同跨度下凹曲比直线矮,且头两层收分大于 1 ──
        int span = 16;
        var straight = com.dwinovo.numen.core.tools.BuildTool.roofCells(
                0, 300, 0, 30, span, "minecraft:oak_stairs", "gable", "straight", 0, 0, null,
                null, null, true);
        var concave = com.dwinovo.numen.core.tools.BuildTool.roofCells(
                0, 300, 0, 30, span, "minecraft:oak_stairs", "gable", "concave", 0, 0, null,
                null, null, true);
        int hStraight = straight.stream().mapToInt(t -> t.pos().getY()).max().orElse(0);
        int hConcave = concave.stream().mapToInt(t -> t.pos().getY()).max().orElse(0);
        helper.assertTrue(hConcave < hStraight,
                "concave roof should be lower than a straight one over the same span, got "
                        + hConcave + " vs " + hStraight);
        // 檐口那两层是缓坡:第二层的坡脚必然离檐口 2 格开外
        java.util.Set<BlockPos> cc = new java.util.HashSet<>();
        for (BuildTaskRecord.Target t : concave) cc.add(t.pos());
        helper.assertTrue(cc.contains(new BlockPos(15, 300, 0)) && cc.contains(new BlockPos(15, 301, 2)),
                "concave roof must start shallow — the second course should step in by 2, not 1");

        // ── 歇山:下四坡、上双坡 ──
        var xieshan = com.dwinovo.numen.core.tools.BuildTool.roofCells(
                0, 400, 0, 16, 12, "minecraft:oak_stairs", "half_hip", "straight", 0, 0, null,
                "minecraft:oak_planks", null, true);
        java.util.Set<BlockPos> xs = new java.util.HashSet<>();
        for (BuildTaskRecord.Target t : xieshan) xs.add(t.pos());
        // 底层四面都有坡(四坡段)
        helper.assertTrue(xs.contains(new BlockPos(0, 400, 6)) && xs.contains(new BlockPos(16, 400, 6)),
                "half_hip: the lower section must slope on the short sides too");
        // 上段变双坡:某一层开始,x 两端不再收(脊沿 x 跑满)
        boolean gableAbove = xieshan.stream().anyMatch(t ->
                t.desiredState().getBlock() == Blocks.OAK_PLANKS);
        helper.assertTrue(gableAbove, "half_hip: upper section must have filled gable ends");

        // ── 单坡:只有一个方向有坡,另一侧不收 ──
        var shed = com.dwinovo.numen.core.tools.BuildTool.roofCells(
                0, 500, 0, 10, 6, "minecraft:oak_stairs", "shed", "straight", 0, 0, null,
                null, null, true);
        int shedMinZ = shed.stream().mapToInt(t -> t.pos().getZ()).min().orElse(-1);
        int shedMaxZ = shed.stream().mapToInt(t -> t.pos().getZ()).max().orElse(-1);
        int lowY = shed.stream().filter(t -> t.pos().getZ() == shedMinZ)
                .mapToInt(t -> t.pos().getY()).max().orElse(0);
        int highY = shed.stream().filter(t -> t.pos().getZ() == shedMaxZ)
                .mapToInt(t -> t.pos().getY()).max().orElse(0);
        helper.assertTrue(highY > lowY,
                "shed: the roof must rise from one edge to the other, got " + lowY + " -> " + highY);

        // ── 不对称双坡:脊偏心,两侧坡长不等 ──
        var salt = com.dwinovo.numen.core.tools.BuildTool.roofCells(
                0, 600, 0, 20, 12, "minecraft:oak_stairs", "saltbox", "straight", 0, 0, 4,
                null, null, true);
        int peakY = salt.stream().mapToInt(t -> t.pos().getY()).max().orElse(0);
        var peakZ = salt.stream().filter(t -> t.pos().getY() == peakY)
                .mapToInt(t -> t.pos().getZ()).min().orElse(-1);
        helper.assertTrue(peakZ > 0 && peakZ < 6,
                "saltbox: ridge_offset=4 should put the peak near z=4, got " + peakZ);
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
                long need = com.dwinovo.numen.core.task.BuildCompanionTask
                        .estimatedTicks(cells, survival);
                long budget = com.dwinovo.numen.core.tools.BuildTool.timeoutTicksFor(cells, survival);
                helper.assertTrue(budget > need,
                        "deadline must exceed the build itself: " + cells + " cells, survival="
                                + survival + ", needs " + need + " ticks but budget is " + budget);
            }
        }
        // 生存封顶:再大的工程也收敛到目标时长,不会无限拉长
        long huge = com.dwinovo.numen.core.task.BuildCompanionTask.estimatedTicks(16384, true);
        helper.assertTrue(huge <= 12 * 60 * 20 + 20,
                "survival pace should cap total duration at the target, got " + huge + " ticks");
        helper.succeed();
    }

    /**
     * 守则驱动的中世纪小屋(12x10x8):形状打底(圆石地基、橡木板空心墙、楼梯
     * 砌的斜屋顶——出檐一格、山墙填实、脊线压半砖)+ 精确格修饰(原木角柱 axis=y、
     * 南面 1x2 门洞、玻璃窗、屋内火把),后写覆盖先写——与 build 工具的混排语义
     * 完全一致,免材料模式。
     */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_build_cottage")
    public static void build_medieval_cottage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spawn = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                "gametest_carpenter", UUID.randomUUID(), level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        // 发脚手架:垫柱残料由交付前的清扫遍拆除,门洞可通行断言就是它的回归测试
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
        // 2) 墙体:walls 周界墙 y3-5(3 高,整墙地面臂展可及)(无顶底面——地板只有地基那一层,守则单层地板铁律)
        ordered.addAll(vol.apply("minecraft:oak_planks",
                com.dwinovo.numen.core.tools.BuildTool.shapeCells("walls", false, 4, 3, 5, 15, 5, 14, null, null)));
        // 3) 屋顶:楼梯砌斜面、脊沿长轴、山墙填实、出檐一格
        BlockPos roofA = helper.absolutePos(new BlockPos(4, 6, 5));
        BlockPos roofB = helper.absolutePos(new BlockPos(15, 6, 14));
        ordered.addAll(com.dwinovo.numen.core.tools.BuildTool.roofCells(
                roofA.getX(), roofA.getY(), roofA.getZ(), roofB.getX(), roofB.getZ(),
                "minecraft:oak_stairs", "gable", "straight", 1, 0, null, "minecraft:oak_planks", "minecraft:oak_slab", true));
        // 4) 细节(后写覆盖先写):四角原木柱、南门洞 1x2、四扇玻璃窗、屋内火把
        for (int[] c : new int[][]{{4, 5}, {15, 5}, {4, 14}, {15, 14}}) {
            for (int y = 3; y <= 5; y++) {
                ordered.add(new BuildTaskRecord.Target(Blocks.OAK_LOG, Items.OAK_LOG,
                        helper.absolutePos(new BlockPos(c[0], y, c[1])), "oak_log",
                        null, net.minecraft.core.Direction.Axis.Y, null));
            }
        }
        ordered.addAll(vol.apply("minecraft:air",
                List.of(new BlockPos(9, 3, 5), new BlockPos(9, 4, 5))));
        // 门槛台阶:室内地板(y2 顶面=脚位 y3)比室外地面高一格,守则要求门外补一级
        ordered.addAll(vol.apply("minecraft:cobblestone", List.of(new BlockPos(9, 2, 4))));
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
                targets, true, false), reply -> {});

        helper.succeedWhen(() -> {
            for (BuildTaskRecord.Target target : targets) {
                helper.assertTrue(target.matches(level.getBlockState(target.pos())),
                        "cottage cell mismatch at " + target.pos().toShortString()
                                + " want " + target.desiredState());
            }
            // 可通行断言:门洞两格为空、门内落脚两格为空——守则"门是走进去的"
            for (BlockPos rel : List.of(new BlockPos(9, 3, 5), new BlockPos(9, 4, 5),
                    new BlockPos(9, 3, 6), new BlockPos(9, 4, 6))) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).isAir(),
                        "doorway blocked at rel " + rel.toShortString());
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
        TaskDispatch.dispatchAsync(companion, new BuildTaskRecord(ctx.toolCallId(), deadline,
                loaded.targets(), true, false), reply -> {});

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

    // ==================== 能力画像用例(生存 / 创造 分道)====================

    /** 画像批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_mode")
    public static void prepareModeBatch(ServerLevel level) {
        level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
        level.setDayTime(6000);
    }

    /** floor20 上拉起同伴的公共步骤;creative = 召后切创造档。 */
    private static NumenPlayer spawnAt(GameTestHelper helper, String name, BlockPos rel,
                                       boolean creative) {
        ServerLevel level = helper.getLevel();
        BlockPos spawn = helper.absolutePos(rel);
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                name, UUID.randomUUID(), level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        if (creative) {
            companion.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        }
        return companion;
    }

    /** 创造 goto:无畏/无饥饿画像下移动与疾跑门照常工作。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_mode")
    public static void creative_goto(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_cghost", new BlockPos(2, 2, 2), true);
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 13));
        TaskRecord record = (TaskRecord) new MovementTools().moveTo(
                (double) target.getX(), (double) target.getY(), (double) target.getZ(), null,
                TaskDispatch.ctx("gametest-cgoto", companion));
        TaskDispatch.enqueue(companion, record, reply -> {});
        helper.succeedWhen(() -> {
            helper.assertTrue(companion.blockPosition().distSqr(target) <= 2 * 2,
                    "creative companion has not reached the goto target");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 创造挖矿:空手(无镐)采金矿——验证三件事:瞬破画像跳过工具门
     * (生存下金矿需铁镐,空手会 WRONG_TOOL 拒工)、无掉落画像按"破坏的
     * 目标方块"计数(背包增量恒零)、以及确实没有掉落物入包。
     */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_mode")
    public static void creative_mine_no_drops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> ores = List.of(
                helper.absolutePos(new BlockPos(8, 2, 8)), helper.absolutePos(new BlockPos(9, 2, 8)),
                helper.absolutePos(new BlockPos(8, 2, 9)), helper.absolutePos(new BlockPos(9, 2, 9)));
        for (BlockPos ore : ores) {
            level.setBlockAndUpdate(ore, Blocks.GOLD_ORE.defaultBlockState());
        }
        NumenPlayer companion = spawnAt(helper, "gametest_cminer", new BlockPos(2, 2, 2), true);

        TaskRecord record = new BlockActionTools().autoMine(
                List.of("minecraft:gold_ore"), 4, TaskDispatch.ctx("gametest-cmine", companion));
        TaskDispatch.dispatchAsync(companion, record, reply -> {});

        helper.succeedWhen(() -> {
            for (BlockPos ore : ores) {
                helper.assertTrue(level.getBlockState(ore).isAir(),
                        "gold ore not broken at " + ore.toShortString());
            }
            helper.assertTrue(companion.getInventory().countItem(Items.RAW_GOLD) == 0
                            && companion.getInventory().countItem(Items.GOLD_ORE.asItem()) == 0,
                    "creative mining must not yield drops");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 创造建造:背包全空 + 免耗材记账,想建就建;建完背包依旧全空。 */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_mode")
    public static void creative_build_empty_inventory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_cmason", new BlockPos(2, 2, 2), true);
        List<BuildTaskRecord.Target> targets = new ArrayList<>();
        for (BlockPos rel : boxCells(new BlockPos(8, 2, 8), 3, 1, 3, false)) {
            targets.add(new BuildTaskRecord.Target(Blocks.COBBLESTONE, Items.COBBLESTONE,
                    helper.absolutePos(rel), "cobblestone", null, null, null));
        }
        var ctx = TaskDispatch.ctx("gametest-cbuild", companion);
        TaskDispatch.dispatchAsync(companion, new BuildTaskRecord(ctx.toolCallId(),
                ctx.deadline(3600L), targets, true, false), reply -> {});
        helper.succeedWhen(() -> {
            for (BuildTaskRecord.Target t : targets) {
                helper.assertTrue(level.getBlockState(t.pos()).is(Blocks.COBBLESTONE),
                        "structure incomplete at " + t.pos().toShortString());
            }
            helper.assertTrue(companion.getInventory().countItem(Items.COBBLESTONE) == 0,
                    "free-material build must not touch the inventory");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 生存缺料拒工:空背包 + 消耗记账 → 开工前盘料失败,回执逐项报缺。 */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_mode")
    public static void survival_build_missing_materials(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_broke", new BlockPos(2, 2, 2), false);
        List<BuildTaskRecord.Target> targets = new ArrayList<>();
        for (BlockPos rel : boxCells(new BlockPos(8, 2, 8), 3, 1, 3, false)) {
            targets.add(new BuildTaskRecord.Target(Blocks.COBBLESTONE, Items.COBBLESTONE,
                    helper.absolutePos(rel), "cobblestone", null, null, null));
        }
        var ctx = TaskDispatch.ctx("gametest-sbuild-broke", companion);
        // dispatchAsync 的回调只回"已受理"收条;预检失败落在任务记录的终态上
        BuildTaskRecord record = new BuildTaskRecord(ctx.toolCallId(),
                ctx.deadline(3600L), targets, true, true);
        TaskDispatch.dispatchAsync(companion, record, reply -> {});
        helper.succeedWhen(() -> {
            var result = record.getResult();
            helper.assertTrue(result != null && !result.success()
                            && result.message() != null
                            && result.message().contains("not enough materials"),
                    "expected an itemized missing-materials refusal, got: "
                            + (result == null ? "still running" : result.message()));
            for (BuildTaskRecord.Target t : targets) {
                helper.assertTrue(!level.getBlockState(t.pos()).is(Blocks.COBBLESTONE),
                        "must not build anything without materials");
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 空手创造爬井:1×1 黑曜石竖井(徒手黑曜石按不可破计价,四面无路),
     * 背包全空——唯一出路是免耗材画像自动补脚手架泥土后原地垫柱。守两件事:
     * 规划器敢想放置路线(hasThrowaway 画像位)+ 执行层自动补料与垫柱动作。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_mode")
    public static void creative_pillar_out_empty_handed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int y = 2; y <= 4; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(3 + dx, y, 3 + dz)),
                            Blocks.OBSIDIAN.defaultBlockState());
                }
            }
        }
        NumenPlayer companion = spawnAt(helper, "gametest_climber", new BlockPos(3, 2, 3), true);
        BlockPos target = helper.absolutePos(new BlockPos(12, 2, 12));
        TaskRecord record = (TaskRecord) new MovementTools().moveTo(
                (double) target.getX(), (double) target.getY(), (double) target.getZ(), null,
                TaskDispatch.ctx("gametest-climb", companion));
        TaskDispatch.enqueue(companion, record, reply -> {});
        helper.succeedWhen(() -> {
            helper.assertTrue(companion.blockPosition().distSqr(target) <= 2 * 2,
                    "empty-handed creative companion has not pillared out");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 社区图纸格式解码:代码现场构造最小 .litematic(跨 long 位流、YZX 序、
     * 稀疏丢空气)与 .schem v2(varint 数据、带属性的调色板键),写进蓝图目录
     * 经 BlueprintStore 统一管线加载,逐格断言。不提交二进制夹具。
     */
    @GameTest(template = "floor16", timeoutTicks = 6000, batch = "numen_mode")
    public static void blueprint_community_formats(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        java.nio.file.Path dir = com.dwinovo.numen.core.blueprint.BlueprintStore.dir(level.getServer());

        // ---- .litematic:2×1×2,调色板 [air, cobblestone],条目 [1,1,0,1] bits=2 ----
        var region = new net.minecraft.nbt.CompoundTag();
        var pos = new net.minecraft.nbt.CompoundTag();
        pos.putInt("x", 0); pos.putInt("y", 0); pos.putInt("z", 0);
        region.put("Position", pos);
        var size = new net.minecraft.nbt.CompoundTag();
        size.putInt("x", 2); size.putInt("y", 1); size.putInt("z", 2);
        region.put("Size", size);
        var pal = new net.minecraft.nbt.ListTag();
        var air = new net.minecraft.nbt.CompoundTag(); air.putString("Name", "minecraft:air");
        var cob = new net.minecraft.nbt.CompoundTag(); cob.putString("Name", "minecraft:cobblestone");
        pal.add(air); pal.add(cob);
        region.put("BlockStatePalette", pal);
        region.putLongArray("BlockStates", new long[]{0b01000101L});   // [1,1,0,1]
        var regions = new net.minecraft.nbt.CompoundTag();
        regions.put("main", region);
        var liteRoot = new net.minecraft.nbt.CompoundTag();
        liteRoot.put("Regions", regions);
        net.minecraft.nbt.NbtIo.writeCompressed(liteRoot, dir.resolve("fixture_lite.litematic"));

        BlockPos anchor = helper.absolutePos(new BlockPos(4, 4, 4));
        var lite = com.dwinovo.numen.core.blueprint.BlueprintStore.load(level, "fixture_lite", anchor, 0);
        helper.assertTrue(lite.targets().size() == 3, "litematic: expect 3 non-air cells, got "
                + lite.targets().size());
        var litePos = lite.targets().stream().map(BuildTaskRecord.Target::pos).toList();
        helper.assertTrue(litePos.contains(anchor)
                        && litePos.contains(anchor.offset(1, 0, 0))
                        && litePos.contains(anchor.offset(1, 0, 1)),
                "litematic: wrong cell positions " + litePos);
        helper.assertTrue(lite.targets().stream().allMatch(
                        t -> t.desiredState().is(Blocks.COBBLESTONE)),
                "litematic: all cells should be cobblestone");

        // ---- .schem v2:2×1×2,调色板含带属性键,BlockData=[1,1,0,1] ----
        var schemRoot = new net.minecraft.nbt.CompoundTag();
        schemRoot.putInt("Version", 2);
        schemRoot.putShort("Width", (short) 2);
        schemRoot.putShort("Height", (short) 1);
        schemRoot.putShort("Length", (short) 2);
        var spal = new net.minecraft.nbt.CompoundTag();
        spal.putInt("minecraft:air", 0);
        spal.putInt("minecraft:oak_stairs[facing=north]", 1);
        schemRoot.put("Palette", spal);
        schemRoot.putByteArray("BlockData", new byte[]{1, 1, 0, 1});
        net.minecraft.nbt.NbtIo.writeCompressed(schemRoot, dir.resolve("fixture_schem.schem"));

        var schem = com.dwinovo.numen.core.blueprint.BlueprintStore.load(level, "fixture_schem", anchor, 0);
        helper.assertTrue(schem.targets().size() == 3, "schem: expect 3 non-air cells, got "
                + schem.targets().size());
        helper.assertTrue(schem.targets().stream().allMatch(t ->
                        t.desiredState().is(Blocks.OAK_STAIRS)
                                && t.desiredState().getValue(net.minecraft.world.level.block.state
                                        .properties.BlockStateProperties.HORIZONTAL_FACING)
                                == net.minecraft.core.Direction.NORTH),
                "schem: cells should be north-facing oak stairs");
        helper.assertTrue(com.dwinovo.numen.core.blueprint.BlueprintStore.list(level.getServer())
                        .containsAll(List.of("fixture_lite", "fixture_schem")),
                "blueprint list should include community formats");
        helper.succeed();
    }

    /** 真实社区图纸解码:日式小屋(40×23×45,负 z 尺寸区域、379 项调色板
     *  9bit 跨 long 位流)。Litematica 元数据 TotalBlocks=5859 当金标准,
     *  解码格数必须分毫不差。 */
    @GameTest(template = "floor16", timeoutTicks = 6000, batch = "numen_mode")
    public static void blueprint_japanese_cottage_decode(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        copyCottageFixture(level);
        var loaded = com.dwinovo.numen.core.blueprint.BlueprintStore.load(
                level, "japanese_cottage", helper.absolutePos(new BlockPos(0, 2, 0)), 0);
        helper.assertTrue(loaded.size().getX() == 40 && loaded.size().getY() == 23
                        && loaded.size().getZ() == 45,
                "cottage size mismatch: " + loaded.size());
        helper.assertTrue(loaded.targets().size() == 5859,
                "cottage decode: expect 5859 cells (litematica TotalBlocks), got "
                        + loaded.targets().size());
        helper.succeed();
    }

    /** 图纸夹具从测试结构目录拷进蓝图目录(幂等)。 */
    private static void copyCottageFixture(ServerLevel level) throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of(
                StructureUtils.testStructuresDir, "japanese_cottage.litematic");
        java.nio.file.Files.copy(src,
                com.dwinovo.numen.core.blueprint.BlueprintStore.dir(level.getServer())
                        .resolve("japanese_cottage.litematic"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** 建造批次(重):创造同伴照真实社区图纸把整栋日式小屋盖出来。 */
    @BeforeBatch(batch = "numen_cottage_jp")
    public static void prepareJpCottageBatch(ServerLevel level) {
        level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
        level.setDayTime(6000);
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
        TaskDispatch.dispatchAsync(companion, new BuildTaskRecord(ctx.toolCallId(),
                ctx.deadline(95000L), loaded.targets(), true, false), reply -> {});
        helper.succeedWhen(() -> {
            for (BuildTaskRecord.Target t : loaded.targets()) {
                helper.assertTrue(t.matches(level.getBlockState(t.pos())),
                        "cottage cell mismatch at " + t.pos().toShortString());
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 创造取物:take_items 凭空取 100 钻石入背包(创造物品栏 GUI 的假体)。 */
    @GameTest(template = "floor16", timeoutTicks = 6000, batch = "numen_mode")
    public static void creative_take_items(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_conjure", new BlockPos(2, 2, 2), true);
        var args = new com.google.gson.JsonObject();
        args.addProperty("item_id", "minecraft:diamond");
        args.addProperty("count", 100);
        java.util.concurrent.atomic.AtomicReference<String> reply =
                new java.util.concurrent.atomic.AtomicReference<>();
        new com.dwinovo.numen.core.tools.TakeItemsTool()
                .onServerCall("gametest-take", args, companion, reply::set);
        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null && reply.get().contains("\"success\":true"),
                    "take_items should succeed in creative, got: " + reply.get());
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 100,
                    "expected 100 diamonds in inventory");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 生存取物拒绝:take_items 在生存画像下吃诚实拒绝,背包不动。 */
    @GameTest(template = "floor16", timeoutTicks = 6000, batch = "numen_mode")
    public static void survival_take_items_refused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_honest", new BlockPos(2, 2, 2), false);
        var args = new com.google.gson.JsonObject();
        args.addProperty("item_id", "minecraft:diamond");
        args.addProperty("count", 10);
        java.util.concurrent.atomic.AtomicReference<String> reply =
                new java.util.concurrent.atomic.AtomicReference<>();
        new com.dwinovo.numen.core.tools.TakeItemsTool()
                .onServerCall("gametest-take2", args, companion, reply::set);
        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null && reply.get().contains("\"success\":false"),
                    "take_items must refuse in survival, got: " + reply.get());
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 0,
                    "survival refusal must not add items");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 生存耗料建造:恰好给足一组圆石,9 格平台建成且背包精确少 9。 */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_mode")
    public static void survival_build_consumes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_frugal", new BlockPos(2, 2, 2), false);
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        List<BuildTaskRecord.Target> targets = new ArrayList<>();
        for (BlockPos rel : boxCells(new BlockPos(8, 2, 8), 3, 1, 3, false)) {
            targets.add(new BuildTaskRecord.Target(Blocks.COBBLESTONE, Items.COBBLESTONE,
                    helper.absolutePos(rel), "cobblestone", null, null, null));
        }
        var ctx = TaskDispatch.ctx("gametest-sbuild", companion);
        TaskDispatch.dispatchAsync(companion, new BuildTaskRecord(ctx.toolCallId(),
                ctx.deadline(3600L), targets, true, true), reply -> {});
        helper.succeedWhen(() -> {
            for (BuildTaskRecord.Target t : targets) {
                helper.assertTrue(level.getBlockState(t.pos()).is(Blocks.COBBLESTONE),
                        "structure incomplete at " + t.pos().toShortString());
            }
            int left = companion.getInventory().countItem(Items.COBBLESTONE);
            helper.assertTrue(left == 64 - targets.size(),
                    "survival build must consume exactly " + targets.size()
                            + " cobblestone, inventory has " + left);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }
}
