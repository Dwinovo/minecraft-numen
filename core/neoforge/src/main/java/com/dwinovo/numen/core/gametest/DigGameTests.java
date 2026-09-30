package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.nav.WorkArea;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
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

/**
 * 挖掘:{@code work dig} 挖扫描来的一团、框出来的坑、一格坐标;站位、够得着、开门出屋、树林与埋矿、够不着时如实收工;只在跟前的
 * 工作区里干,区外的只报告并给开路的写法;{@code --arrive dig} 走到够得着埋着的方块再挖。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class DigGameTests {

    /**
     * 挖掘也走门:黑曜石屋(铁镐非正确工具,成本模型按不可破对待——拆墙不是廉价选项)关住她,矿在屋外、她的工作区里,唯一通路是
     * 关着的橡木门。挖掘的站位寻路复用同一条开门链;收工后墙体完好(确实没打洞)。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_through_closed_door(GameTestHelper helper) {
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

        // 屋外十格以内、屋里够不着的两块金矿
        List<BlockPos> ores = List.of(
                helper.absolutePos(new BlockPos(3, 2, 10)),
                helper.absolutePos(new BlockPos(4, 2, 10)));
        for (BlockPos ore : ores) {
            level.setBlockAndUpdate(ore, Blocks.GOLD_ORE.defaultBlockState());
        }

        NumenPlayer companion = spawnAt(helper, "gametest_tunneler", new BlockPos(3, 2, 3), false);
        companion.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        mineScanned(helper, companion, 9, "minecraft:gold_ore", "count", 2);

        BlockPos wallProbe = helper.absolutePos(new BlockPos(1, 3, 3));
        succeedWhen(helper, () -> {
            helper.assertTrue(companion.getInventory().countItem(Items.RAW_GOLD) >= 2,
                    "companion has not dug the gold outside the door");
            helper.assertTrue(level.getBlockState(wallProbe).is(Blocks.OBSIDIAN),
                    "wall breached — expected the door route");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 树冠上的原木站在地上挖:两根金合欢原木悬在她脚上五格、六格,四周一圈树叶,她身上只有一把斧头,
     * 没有垫脚的方块。爬上去贴着它们是做不到的;站在底下仰头,眼睛离它们 3.38 格、4.38 格,在交互距离里——
     * 斜着看过去挡着的树叶先挖开,再挖原木。原木正下方留空,掉落物落回地面。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_canopy_logs_from_the_ground(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockState leaves = Blocks.ACACIA_LEAVES.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT, true);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int y = 6; y <= 8; y++) {
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(8 + dx, y, 8 + dz)), leaves);
                }
            }
        }
        List<BlockPos> logs = List.of(new BlockPos(8, 7, 8), new BlockPos(8, 8, 8));
        for (BlockPos rel : logs) {
            level.setBlockAndUpdate(helper.absolutePos(rel), Blocks.ACACIA_LOG.defaultBlockState());
        }
        NumenPlayer companion = spawnAt(helper, "gametest_canopy", new BlockPos(4, 2, 8), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        Mining mine = mineScanned(helper, companion, 9, "minecraft:acacia_log", "count", 2);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "dig has not finished");
            helper.assertTrue(mine.succeeded() && companion.getInventory().countItem(Items.ACACIA_LOG) >= 2,
                    "the canopy logs were not gathered from the ground: " + mine.outcome());
            for (BlockPos rel : logs) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).isAir(),
                        "a canopy log is still up at " + rel.toShortString());
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 够不着就如实收工:一根去皮白桦原木悬在她脚上八格,站在底下眼睛离它 5.38 格,出了交互距离;她没有垫脚的方块,
     * 爬不上去。任务不该站着一遍遍重搜同一条走不通的路,而是收场、说清楚够不着,原因是寻路给的那一条(要垫方块而身上没有)。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_out_of_reach_ends_instead_of_hanging(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos logRel = new BlockPos(8, 10, 8);
        level.setBlockAndUpdate(helper.absolutePos(logRel), Blocks.STRIPPED_BIRCH_LOG.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_skyward", new BlockPos(7, 2, 8), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        Mining mine = mineScanned(helper, companion, 9, "minecraft:stripped_birch_log", "count", 1);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "dig has not finished");
            String reply = mine.outcome();
            helper.assertTrue(!mine.succeeded() && reply.contains("could not reach")
                            && reply.contains("blocks to pillar or bridge with"),
                    "an out-of-reach log did not end as unreachable for want of blocks to pillar with: " + reply);
            helper.assertTrue(level.getBlockState(helper.absolutePos(logRel)).is(Blocks.STRIPPED_BIRCH_LOG),
                    "the out-of-reach log is gone");
            // 悬在模板外的原木不收走,后面批次的扫描会把它当目标
            level.removeBlock(helper.absolutePos(logRel), false);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    // ==================== 真实地形挖掘用例(模板取自实际存档地形)====================

    /** 挖掘批次前置:和平难度 + 正午,排除怪物袭扰与昼夜随机性。 */
    @BeforeBatch(batch = "numen_dig")
    public static void prepareDigBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 云杉林:三棵云杉围着她,树干六七格高,上半截一圈圈裹着树叶;手持铁斧砍 8 根原木。三棵都在她的工作区里——
     * 复合站位、就地挖掘与挖开挡在视线上的树叶、掉落拾取、背包计数走一遍。
     *
     * <p>超时按游戏刻给得很宽:无头测试服不限速(数百 tps),而寻路搜索在后台线程上要花真实时间。
     */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_spruce_grove(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        spruceTree(helper, new BlockPos(5, 2, 5), 6);
        spruceTree(helper, new BlockPos(13, 2, 6), 6);
        spruceTree(helper, new BlockPos(8, 2, 14), 7);
        BlockPos spawn = helper.absolutePos(new BlockPos(9, 2, 9));
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                "gametest_logger", UUID.randomUUID(), level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));

        mineScanned(helper, companion, 10, "minecraft:spruce_log", "count", 8);

        succeedWhen(helper, () -> {
            helper.assertTrue(companion.getInventory().countItem(Items.SPRUCE_LOG) >= 8,
                    "companion has not gathered 8 spruce logs");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 够数靠的那一件躺在远处:砍下的第一根原木已经算进数里,掉落物却落在八格外,而她站的地方手边还够得着下一根。该做的是走
     * 过去捡,不是再砍一根,也不是站着不动。
     *
     * <p>钉的是"到了没有"只有一个判据:导航问的"站在这儿有没有可挖的"和任务真去挖的必须是同一个——够数之后手边那根不是该挖的,
     * 导航就不能拿它当"到了"。用场地里独一种的去皮橡木,免得看见别的用例的原木。
     */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_fetches_the_counted_drop_instead_of_freezing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(2, 2, 4));
        BlockPos second = helper.absolutePos(new BlockPos(2, 2, 6));
        level.setBlockAndUpdate(first, Blocks.STRIPPED_OAK_LOG.defaultBlockState());
        level.setBlockAndUpdate(second, Blocks.STRIPPED_OAK_LOG.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_fetcher", new BlockPos(2, 2, 2), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        // 第一根的掉落物一露面就挪开:还在她的工作区里,离她远到"走过去"比"再砍一根"估价还高
        Vec3 far = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(8, 2, 8)));
        boolean[] thrown = {false};
        helper.onEachTick(() -> {
            if (!thrown[0] && level.getBlockState(first).isAir()) {
                for (net.minecraft.world.entity.item.ItemEntity drop : level.getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(first).inflate(2),
                        ie -> ie.getItem().is(Items.STRIPPED_OAK_LOG))) {
                    drop.teleportTo(far.x, far.y, far.z);
                    drop.setDeltaMovement(Vec3.ZERO);
                    thrown[0] = true;
                }
            }
            if (level.getBlockState(second).isAir()) {
                helper.fail("she cut a second log instead of fetching the one she had already cut");
            }
        });
        Mining mine = mineScanned(helper, companion, 5, "minecraft:stripped_oak_log", "count", 1);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "dig has not finished");
            helper.assertTrue(mine.succeeded() && companion.getInventory().countItem(Items.STRIPPED_OAK_LOG) == 1,
                    "the counted log was not fetched: " + mine.outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 掉落物躺在工作区的边上:南瓜离她整十格,正落在工作区那个球的边上,挖下来的南瓜摆回那一格、不再弹动。那一格脚下在区里,
     * 头顶那一格出了区,她站不进去——挨着它站在区里照样捡得到,于是走过去捡起来,算这一单的收获。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_picks_up_a_drop_lying_on_the_edge_of_its_work_area(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_edge_picker", new BlockPos(2, 2, 5), false);
        BlockPos pumpkin = helper.absolutePos(new BlockPos(12, 2, 5));
        level.setBlockAndUpdate(pumpkin, Blocks.PUMPKIN.defaultBlockState());
        WorkArea area = WorkArea.around(companion);
        helper.assertTrue(area.contains(level.dimension(), pumpkin) && !area.contains(level.dimension(), pumpkin.above()),
                "the pumpkin does not lie on the edge of the work area as laid out");
        Vec3 onTheEdge = Vec3.atBottomCenterOf(pumpkin);
        boolean[] placed = {false};
        helper.onEachTick(() -> {
            if (!placed[0] && level.getBlockState(pumpkin).isAir()) {
                for (net.minecraft.world.entity.item.ItemEntity drop : level.getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(pumpkin).inflate(2),
                        ie -> ie.getItem().is(Items.PUMPKIN))) {
                    drop.teleportTo(onTheEdge.x, onTheEdge.y, onTheEdge.z);
                    drop.setDeltaMovement(Vec3.ZERO);
                    placed[0] = true;
                }
            }
        });
        Mining mine = mineScanned(helper, companion, 10, "minecraft:pumpkin", "count", 1);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "dig has not finished");
            helper.assertTrue(placed[0] && mine.succeeded() && companion.getInventory().countItem(Items.PUMPKIN) == 1,
                    "the pumpkin lying on the edge of the work area was not picked up: " + mine.outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 埋在石头里的钻石:一块九乘九、六层高的深板岩,中间埋着四颗深板岩钻石矿;她站在顶上,手持铁镐
     * 往下挖进去,采得 2 颗钻石。盯的是埋矿的站位:脚不能高于矿,得一路挖着往下走,再就地挖矿。
     */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_buried_diamonds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 5; x <= 13; x++) {
            for (int z = 5; z <= 13; z++) {
                for (int y = 2; y <= 7; y++) {
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, z)),
                            Blocks.DEEPSLATE.defaultBlockState());
                }
            }
        }
        for (BlockPos rel : List.of(new BlockPos(9, 3, 9), new BlockPos(10, 3, 9), new BlockPos(9, 3, 10),
                new BlockPos(9, 4, 9))) {
            level.setBlockAndUpdate(helper.absolutePos(rel), Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState());
        }
        BlockPos spawn = helper.absolutePos(new BlockPos(9, 8, 9));
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                "gametest_miner", UUID.randomUUID(), level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        companion.getInventory().add(new ItemStack(Items.IRON_PICKAXE));

        mineScanned(helper, companion, 8, "minecraft:deepslate_diamond_ore", "count", 2);

        succeedWhen(helper, () -> {
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) >= 2,
                    "companion has not gathered 2 diamonds");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 矿紧挨着岩浆:挖开它岩浆就会淌出来,所以这一格不挖。回执交代它挖不成的原因(贴着流体),矿与岩浆都原样。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_leaves_ore_that_borders_lava(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ore = helper.absolutePos(new BlockPos(6, 2, 4));
        BlockPos lava = helper.absolutePos(new BlockPos(7, 2, 4));
        // 岩浆关在一个石头兜里,只有挨着矿的那一面敞着
        for (BlockPos wall : List.of(new BlockPos(8, 2, 4), new BlockPos(7, 2, 3), new BlockPos(7, 2, 5),
                new BlockPos(7, 3, 4))) {
            level.setBlockAndUpdate(helper.absolutePos(wall), Blocks.STONE.defaultBlockState());
        }
        level.setBlockAndUpdate(ore, Blocks.IRON_ORE.defaultBlockState());
        level.setBlockAndUpdate(lava, Blocks.LAVA.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_careful", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        Mining mine = mineScanned(helper, companion, 8, "minecraft:iron_ore", "count", 1);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "dig has not finished");
            helper.assertTrue(!mine.succeeded() && mine.outcome().contains("none of them can be broken here"),
                    "the reply does not say the ore by the lava cannot be broken: " + mine.outcome());
            helper.assertTrue(level.getBlockState(ore).is(Blocks.IRON_ORE)
                            && level.getBlockState(lava).is(Blocks.LAVA),
                    "the ore was broken or the lava got out");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 服务端这一刻认不认为她正在挖一格方块。 */
    private static boolean digging(NumenPlayer companion) {
        return ((com.dwinovo.numen.core.mixin.ServerPlayerGameModeAccessor) companion.gameMode).numen$isDestroyingBlock();
    }

    /**
     * 一棵云杉:{@code base} 起往上 {@code trunk} 格树干;树干上半截每层裹一圈树叶(下面两层两格宽、
     * 再往上一格宽),树顶再压一片。树叶是不会凋落的那种。
     */
    private static void spruceTree(GameTestHelper helper, BlockPos base, int trunk) {
        ServerLevel level = helper.getLevel();
        BlockState leaves = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT, true);
        int top = base.getY() + trunk - 1;
        for (int y = base.getY() + 2; y <= top + 1; y++) {
            int r = y <= base.getY() + 3 ? 2 : y <= top ? 1 : 0;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(base.getX() + dx, y, base.getZ() + dz)),
                            leaves);
                }
            }
        }
        for (int y = base.getY(); y <= top; y++) {
            level.setBlockAndUpdate(helper.absolutePos(new BlockPos(base.getX(), y, base.getZ())),
                    Blocks.SPRUCE_LOG.defaultBlockState());
        }
    }

    /** 手里的镐挖不下这种矿(木镐对钻石矿):当场失败,说清楚是工具不够,矿原样留着。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_dig")
    public static void dig_without_a_harvesting_tool_says_the_tool_is_short(GameTestHelper helper) {
        BlockPos ore = helper.absolutePos(new BlockPos(6, 2, 4));
        helper.getLevel().setBlockAndUpdate(ore, Blocks.DIAMOND_ORE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_underequipped", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE));
        Mining mine = mineScanned(helper, companion, 8, "minecraft:diamond_ore", "count", 1);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "dig has not finished");
            helper.assertTrue(!mine.succeeded() && mine.outcome().contains("current tools"),
                    "the failure does not say the tool is short: " + mine.outcome());
            helper.assertTrue(helper.getLevel().getBlockState(ore).is(Blocks.DIAMOND_ORE), "the ore was broken");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 扫描一格都没找到,区域是空的:点名它挖,派发当场拒收,不满世界乱走,如实说区域里还没有格、下一步照抄哪一行去扫。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_an_empty_area_says_so_and_how_to_fill_it(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_prospector", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        BlockPos start = helper.absolutePos(new BlockPos(3, 2, 4));
        Mining mine = mineScanned(helper, companion, 8, "minecraft:emerald_ore", "count", 1);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "dig has not finished");
            helper.assertTrue(!mine.succeeded() && mine.task() == null
                            && mine.outcome().contains(MINED_AREA + " has no cells yet, so I did not start")
                            && mine.outcome().contains("scan blocks <radius> <block ids> --into " + MINED_AREA),
                    "the refusal does not say the area is empty and how to fill it: " + mine.outcome());
            helper.assertTrue(companion.blockPosition().distSqr(start) <= 4, "she wandered off looking for it");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 挖到一半主人按停止:活按主人停止收场,那块黑曜石还在,挖掘的裂纹也收掉了。 */
    @GameTest(template = "floor16", timeoutTicks = 2000, batch = "numen_dig")
    public static void owner_stop_mid_dig_leaves_the_block(GameTestHelper helper) {
        BlockPos block = helper.absolutePos(new BlockPos(5, 2, 4));
        helper.getLevel().setBlockAndUpdate(block, Blocks.OBSIDIAN.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_interrupted", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
        Mining mine = mineScanned(helper, companion, 8, "minecraft:obsidian", "count", 1);

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(digging(companion),
                        "she has not started digging the obsidian"))
                .thenExecute(() -> com.dwinovo.numen.task.CompanionTickDispatcher.cancelFor(companion))
                .thenWaitUntil(() -> helper.assertTrue(mine.done() && mine.outcome().startsWith("the owner pressed Stop"),
                        "the dig did not end as stopped by the owner: " + mine.outcome()))
                .thenWaitUntil(() -> {
                    helper.assertTrue(helper.getLevel().getBlockState(block).is(Blocks.OBSIDIAN),
                            "the obsidian was broken after the stop");
                    helper.assertTrue(!digging(companion), "she is still digging after the stop");
                })
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /**
     * 要 12 就是 12。够挖 20 块的金块,只要 12 个。金块摆成两排、当中留出走道,她站在走道当中:她够得着每一块,也够得着每一件
     * 掉落物,不必挖穿目标才能走过去。
     *
     * <p>进度的口径是<b>背包里的物品</b>,而背包是个滞后指标(原版拾取延迟 10 tick)。"还敲不敲"要算上已经敲掉、还没进包的,
     * 而"完了没"仍然只看到手——这条量的就是这两件事分开了没有(#69)。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_stops_at_the_requested_count(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int field = 0;
        for (int x = 4; x <= 13; x++) {
            for (int z : new int[]{6, 10}) {
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 2, z)),
                        Blocks.GOLD_BLOCK.defaultBlockState());
                field++;
            }
        }
        final int blocks = field;
        NumenPlayer companion = spawnAt(helper, "gametest_counter", new BlockPos(8, 2, 8), false);
        companion.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        Mining mine = mineScanned(helper, companion, 7, "minecraft:gold_block", "count", 12);

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(mine.done(), "dig has not finished"))
                .thenWaitUntil(() -> {
                    helper.assertTrue(mine.succeeded(), "dig failed: " + mine.outcome());
                    int held = companion.getInventory().countItem(Items.GOLD_BLOCK);
                    helper.assertTrue(held == 12, "asked for 12, came back with " + held);
                    int left = 0;
                    for (int x = 4; x <= 13; x++) {
                        for (int z : new int[]{6, 10}) {
                            if (level.getBlockState(helper.absolutePos(new BlockPos(x, 2, z)))
                                    .is(Blocks.GOLD_BLOCK)) {
                                left++;
                            }
                        }
                    }
                    helper.assertTrue(blocks - left == 12,
                            "asked for 12 blocks, broke " + (blocks - left));
                    long onTheGround = level.getEntitiesOfClass(
                            net.minecraft.world.entity.item.ItemEntity.class,
                            new net.minecraft.world.phys.AABB(helper.absolutePos(new BlockPos(8, 2, 8)))
                                    .inflate(24.0),
                            ie -> ie.getItem().is(Items.GOLD_BLOCK)).size();
                    helper.assertTrue(onTheGround == 0,
                            onTheGround + " gold blocks left lying around — she walked off without them");
                })
                .thenExecute(() -> CompanionFactory.despawn(level.getServer(), companion))
                .thenSucceed();
    }

    /**
     * 同源:快捷工具 work_dig 与命令 work dig 是同一个处理函数。两块干海带块扫进同一块区域,先用工具挖一块、再用命令挖一块:
     * 两次都挖成、各自到手一块,回执除了数字一字不差;派下的活一个叫工具名、一个叫"组 动作"。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_from_the_tool_and_the_command_is_the_same_work(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(8, 2, 6)), Blocks.DRIED_KELP_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(8, 2, 10)), Blocks.DRIED_KELP_BLOCK.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_twin_digger", new BlockPos(3, 2, 8), false);
        Mining viaTool = mineScanned(helper, companion, 8, "minecraft:dried_kelp_block", "count", 1);
        java.util.concurrent.atomic.AtomicReference<ToolRun> viaCommand = new java.util.concurrent.atomic.AtomicReference<>();

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(viaTool.done(), "dig has not finished"))
                .thenExecute(() -> viaCommand.set(command(companion,
                        "work dig " + MINED_AREA + " --count 1")))
                .thenWaitUntil(() -> helper.assertTrue(viaCommand.get().done(), "work dig has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(viaTool.succeeded() && viaCommand.get().succeeded(),
                            "one of the two failed: " + viaTool.outcome() + " / " + viaCommand.get().outcome());
                    helper.assertTrue(companion.getInventory().countItem(Items.DRIED_KELP_BLOCK) == 2,
                            "the two calls did not gather one block each");
                    helper.assertTrue(viaTool.task().getToolName().equals("work_dig")
                                    && viaCommand.get().task().getToolName().equals("work dig"),
                            "the work is not named after the call: " + viaTool.task().getToolName() + " / "
                                    + viaCommand.get().task().getToolName());
                    helper.assertTrue(withoutNumbers(viaTool.outcome()).equals(withoutNumbers(viaCommand.get().outcome())),
                            "work_dig and work dig report differently: " + viaTool.outcome() + " / "
                                    + viaCommand.get().outcome());
                })
                .thenExecute(() -> CompanionFactory.despawn(level.getServer(), companion))
                .thenSucceed();
    }

    // ==================== 挖什么由区域的数据定 ====================

    /**
     * 挖一格:坐标就是只有一格的区域。一捆干草块在她跟前,{@code work dig x y z} 走过去挖掉、捡起来;受理回执说工作区在哪、
     * 区里要挖一格,收场说挖了 1/1 格。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_one_cell_by_its_coordinates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos hay = helper.absolutePos(new BlockPos(9, 2, 5));
        level.setBlockAndUpdate(hay, Blocks.HAY_BLOCK.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_one_cell", new BlockPos(3, 2, 5), false);
        BlockPos stand = companion.blockPosition();
        ToolRun dig = command(companion, "work dig " + xyz(hay));

        succeedWhen(helper, () -> {
            helper.assertTrue(dig.reply() != null && dig.reply().contains("My work area is within " + WorkArea.RADIUS
                            + " blocks of " + coords(stand) + ", where I stand now: 1 cell(s) of " + coords(hay)
                            + " to dig lie in it"),
                    "the acceptance does not say where the work area is and what lies in it: " + dig.reply());
            helper.assertTrue(dig.done(), "work dig has not finished");
            helper.assertTrue(dig.succeeded() && dig.outcome().startsWith("dug 1/1 cells of hay_block"),
                    "the cell was not dug: " + dig.outcome());
            helper.assertTrue(level.getBlockState(hay).isAir(), "the hay is still there");
            helper.assertTrue(companion.getInventory().countItem(Items.HAY_BLOCK) == 1, "the hay was not picked up");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 扫描来的一团只挖还是当时那种方块的格:四块铜矿扫进区域,开挖之前有人把其中一块换成了石头。她挖掉另外三块,那块石头
     * 原样留着,回执说有一格变了。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_a_scanned_cluster_digs_what_still_holds_the_scanned_block(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> ores = List.of(new BlockPos(8, 2, 6), new BlockPos(9, 2, 6), new BlockPos(8, 3, 6),
                new BlockPos(9, 2, 7));
        for (BlockPos rel : ores) {
            level.setBlockAndUpdate(helper.absolutePos(rel), Blocks.COPPER_ORE.defaultBlockState());
        }
        BlockPos changed = helper.absolutePos(ores.get(2));
        NumenPlayer companion = spawnAt(helper, "gametest_copper", new BlockPos(4, 2, 6), false);
        companion.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        ToolRun scanned = scanInto(companion, 7, "minecraft:copper_ore", "copper");
        ToolRun[] dig = new ToolRun[1];

        succeedWhen(helper, () -> {
            if (dig[0] == null) {
                helper.assertTrue(scanned.reply() != null, "the scan has not replied");
                level.setBlockAndUpdate(changed, Blocks.STONE.defaultBlockState());
                dig[0] = command(companion, "work dig copper");
            }
            helper.assertTrue(dig[0].done(), "work dig has not finished");
            helper.assertTrue(dig[0].succeeded() && dig[0].outcome().startsWith("dug 3/3 cells of copper_ore"),
                    "the three copper ores still there were not dug out: " + dig[0].outcome());
            for (BlockPos rel : ores) {
                BlockPos cell = helper.absolutePos(rel);
                helper.assertTrue(cell.equals(changed) ? level.getBlockState(cell).is(Blocks.STONE)
                                : level.getBlockState(cell).isAir(),
                        "the wrong cell was dug or left at " + rel.toShortString());
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 挖一块框出来的格:在她跟前框一个 3×3×3 的盒子(底层石头、中层泥土、顶层是空气),{@code work dig pit} 把盒子里是什么
     * 挖什么,空气跳过;挖完盒子里全是空气,泥土与圆石进了她的包。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_a_framed_pit_digs_whatever_it_holds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos low = helper.absolutePos(new BlockPos(7, 2, 5));
        BlockPos high = helper.absolutePos(new BlockPos(9, 4, 7));
        for (BlockPos pos : BlockPos.betweenClosed(low, high)) {
            BlockState fill = pos.getY() == high.getY() ? Blocks.AIR.defaultBlockState()
                    : pos.getY() == low.getY() ? Blocks.STONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
            level.setBlockAndUpdate(pos, fill);
        }
        NumenPlayer companion = spawnAt(helper, "gametest_pitman", new BlockPos(4, 2, 6), false);
        companion.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        companion.getInventory().add(new ItemStack(Items.IRON_SHOVEL));
        ToolRun made = command(companion, "area new pit");
        ToolRun framed = command(companion, "area add pit --box " + low.getX() + "," + low.getY() + "," + low.getZ()
                + ".." + high.getX() + "," + high.getY() + "," + high.getZ());
        ToolRun[] dig = new ToolRun[1];

        succeedWhen(helper, () -> {
            if (dig[0] == null) {
                helper.assertTrue(made.succeeded() && framed.reply() != null && framed.succeeded(),
                        "the pit was not framed: " + framed.reply());
                dig[0] = command(companion, "work dig pit");
            }
            helper.assertTrue(dig[0].done(), "work dig has not finished");
            helper.assertTrue(dig[0].succeeded() && dig[0].outcome().startsWith("dug 18/18 cells of"),
                    "the pit was not dug out: " + dig[0].outcome());
            for (BlockPos pos : BlockPos.betweenClosed(low, high)) {
                helper.assertTrue(level.getBlockState(pos).isAir(), "the pit still holds a block at " + pos.toShortString());
            }
            helper.assertTrue(companion.getInventory().countItem(Items.DIRT) >= 9
                            && companion.getInventory().countItem(Items.COBBLESTONE) >= 9,
                    "what came out of the pit was not picked up");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 矿脉跨过工作区的边:一排珠光蛙明灯从区里伸到区外,整排扫进区域。要 64 个:她把区里那几块挖完就收场,算成功,回执说区里
     * 没有了、区外还有几格、最近那格在哪,以及开路的写法;区外那几块一块不少。
     */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_the_part_of_a_vein_inside_its_work_area_and_report_the_rest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_vein_edge", new BlockPos(3, 2, 3), false);
        WorkArea area = WorkArea.around(companion);
        List<BlockPos> inside = new java.util.ArrayList<>();
        List<BlockPos> outside = new java.util.ArrayList<>();
        for (int x = 6; x <= 18; x++) {
            BlockPos cell = helper.absolutePos(new BlockPos(x, 2, 10));
            level.setBlockAndUpdate(cell, Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState());
            (area.contains(level.dimension(), cell) ? inside : outside).add(cell);
        }
        helper.assertTrue(!inside.isEmpty() && !outside.isEmpty(), "the vein does not straddle the edge as laid out");
        Mining mine = mineScanned(helper, companion, 18, "minecraft:pearlescent_froglight", "count", 64);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "dig has not finished");
            String said = mine.outcome();
            helper.assertTrue(mine.succeeded() && said.contains("gathered " + inside.size() + "/64")
                            && said.contains("nothing left to dig in my work area")
                            && said.contains(outside.size() + " cell(s) of " + MINED_AREA + " lie beyond it")
                            && said.contains("the nearest at " + coords(outside.get(0)))
                            && said.contains("`route new " + MINED_AREA + " --to " + MINED_AREA
                                    + " --arrive dig --alter natural`"),
                    "the reply does not account for the part of the vein beyond the work area: " + said);
            for (BlockPos cell : inside) {
                helper.assertTrue(level.getBlockState(cell).isAir(), "a froglight inside the area is still there at "
                        + cell.toShortString());
            }
            for (BlockPos cell : outside) {
                helper.assertTrue(level.getBlockState(cell).is(Blocks.PEARLESCENT_FROGLIGHT),
                        "a froglight beyond the area was dug at " + cell.toShortString());
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    // ==================== 区外:只报告,开路是一条路线 ====================

    /** 区外的几条用例用 52 格见方的场地:她站在一角,另一角离她六十多格。和平难度、正午。 */
    @BeforeBatch(batch = "numen_dig_far")
    public static void prepareDigFarBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** "x,y,z" 的写法,和回执里点坐标的一样。 */
    private static String coords(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /**
     * 区外的不去:两块赭黄蛙明灯在场地另一角、六十多格外,扫进区域后工作区里一格都没有。派发当场拒收,她不出发,回执说两格都在
     * 区外、最近那格在哪、多远,开路的两种写法都能照抄。
     */
    @GameTest(template = "floor52", timeoutTicks = 100000, batch = "numen_dig_far")
    public static void dig_stays_put_and_gives_the_way_when_the_blocks_lie_beyond_its_work_area(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos nearer = helper.absolutePos(new BlockPos(50, 2, 50));
        BlockPos farther = helper.absolutePos(new BlockPos(51, 2, 50));
        level.setBlockAndUpdate(nearer, Blocks.OCHRE_FROGLIGHT.defaultBlockState());
        level.setBlockAndUpdate(farther, Blocks.OCHRE_FROGLIGHT.defaultBlockState());
        BlockPos start = helper.absolutePos(new BlockPos(3, 2, 3));
        NumenPlayer companion = spawnAt(helper, "gametest_stay_put", new BlockPos(3, 2, 3), false);
        Mining mine = mineScanned(helper, companion, 72, "minecraft:ochre_froglight", "count", 1);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "dig has not finished");
            String said = mine.outcome();
            helper.assertTrue(!mine.succeeded() && mine.task() == null
                            && said.contains("all 2 cell(s) of " + MINED_AREA + " lie beyond my work area")
                            && said.contains("the nearest is at " + coords(nearer))
                            && said.contains("`route new " + MINED_AREA + " --to " + MINED_AREA
                                    + " --arrive dig --alter natural`, `route plan " + MINED_AREA + "`")
                            && said.contains("move_goto area:" + MINED_AREA + " arrive:dig alter:natural")
                            && said.contains("then `work dig " + MINED_AREA + "` again"),
                    "the reply does not say what lies beyond the work area and how to get there: " + said);
            helper.assertTrue(companion.blockPosition().distSqr(start) <= 4, "she set off for blocks beyond her work area");
            helper.assertTrue(level.getBlockState(nearer).is(Blocks.OCHRE_FROGLIGHT)
                    && level.getBlockState(farther).is(Blocks.OCHRE_FROGLIGHT), "a froglight beyond the area was dug");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 点名的部分整个在工作区外:远处一团与近处一团扫进同一块区域;点名远处那一部分,派发当场拒收,说它在哪、开路的写法,
     * 不派活,蛙明灯一块不少。
     */
    @GameTest(template = "floor52", timeoutTicks = 4000, batch = "numen_dig_far")
    public static void dig_a_part_beyond_the_work_area_is_refused_at_once(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> far = List.of(new BlockPos(48, 2, 50), new BlockPos(49, 2, 50), new BlockPos(50, 2, 50));
        for (BlockPos rel : far) {
            level.setBlockAndUpdate(helper.absolutePos(rel), Blocks.VERDANT_FROGLIGHT.defaultBlockState());
        }
        BlockPos nearRel = new BlockPos(8, 2, 8);
        level.setBlockAndUpdate(helper.absolutePos(nearRel), Blocks.VERDANT_FROGLIGHT.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_far_group", new BlockPos(3, 2, 3), false);
        ToolRun scanned = scanInto(companion, 80, "minecraft:verdant_froglight", "lights");
        String[] refusal = new String[2];

        succeedWhen(helper, () -> {
            if (refusal[0] == null) {
                helper.assertTrue(scanned.reply() != null, "scan_blocks has not replied");
                var groups = groupsIn(scanned.reply());
                var farGroup = groupHolding(groups, helper.absolutePos(far.get(0)));
                var nearGroup = groupHolding(groups, helper.absolutePos(nearRel));
                helper.assertTrue(farGroup != null && nearGroup != null && farGroup != nearGroup,
                        "the scan did not keep the far and the near froglights as two parts: " + scanned.reply());
                refusal[1] = farGroup.get("id").getAsString();
                ToolRun dig = call(companion, "work_dig", args("place", List.of(refusal[1])));
                helper.assertTrue(dig.task() == null, "a part wholly beyond the work area was accepted");
                refusal[0] = dig.reply();
            }
            helper.assertTrue(refusal[0] != null && refusal[0].contains("lie beyond my work area")
                            && refusal[0].contains("move_goto area:" + refusal[1] + " arrive:dig alter:natural"),
                    "the refusal does not say where the part is and how to get there: " + refusal[0]);
            for (BlockPos rel : far) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).is(Blocks.VERDANT_FROGLIGHT),
                        "a froglight of the refused part is gone at " + rel.toShortString());
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 开路再挖:一块远古残骸埋在二十多格外一座石头小丘的正中,四面都隔着两格石头。{@code move_goto … arrive:dig} 走到手够得着
     * 它的地方就停,残骸原样(到达只管站位,那一格留给挖的一方);接着 {@code work dig} 挖开挡着的石头,把它挖出来。
     */
    @GameTest(template = "floor52", timeoutTicks = 100000, batch = "numen_dig_far")
    public static void arrive_dig_reaches_a_buried_block_and_work_dig_digs_it_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (BlockPos rel : BlockPos.betweenClosed(new BlockPos(28, 2, 28), new BlockPos(32, 5, 32))) {
            level.setBlockAndUpdate(helper.absolutePos(rel), Blocks.STONE.defaultBlockState());
        }
        BlockPos debris = helper.absolutePos(new BlockPos(30, 3, 30));
        level.setBlockAndUpdate(debris, Blocks.ANCIENT_DEBRIS.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_debris", new BlockPos(3, 2, 3), false);
        companion.getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
        ToolRun walk = call(companion, "move_goto", args("x", debris.getX(), "y", debris.getY(), "z", debris.getZ(),
                "arrive", "dig", "alter", "natural"));
        ToolRun[] dig = new ToolRun[1];

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "move_goto has not finished");
            if (dig[0] == null) {
                helper.assertTrue(walk.succeeded() && walk.outcome().contains("within reach")
                                && walk.outcome().contains("`work dig " + xyz(debris) + "`"),
                        "the walk did not end within reach of the buried block: " + walk.outcome());
                helper.assertTrue(level.getBlockState(debris).is(Blocks.ANCIENT_DEBRIS),
                        "the walk dug the block it was only meant to reach");
                helper.assertTrue(companion.getEyePosition().distanceTo(Vec3.atCenterOf(debris))
                                <= companion.blockInteractionRange() + 1,
                        "she stopped out of reach of the buried block");
                dig[0] = command(companion, "work dig " + xyz(debris));
            }
            helper.assertTrue(dig[0].done(), "work dig has not finished");
            helper.assertTrue(dig[0].succeeded() && level.getBlockState(debris).isAir()
                            && companion.getInventory().countItem(Items.ANCIENT_DEBRIS) == 1,
                    "the buried block was not dug out: " + dig[0].outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 数字(坐标、件数、刻数)抹掉,比两份回执的措辞。 */
    private static String withoutNumbers(String reply) {
        return reply.replaceAll("-?\\d+", "#");
    }
}
