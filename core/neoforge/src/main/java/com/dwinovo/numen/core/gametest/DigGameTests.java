package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
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
 * 挖掘:{@code numen.work.dig} 只挖站在原地手够得着的格(扫描来的一团、框出来的坑、一格坐标),挡着的天然地形一并挖开,不走、不捡,
 * 够不着的如实报告并给能照抄的 {@code numen.move.to … arrive = "dig"};{@code arrive = "dig"} 走到一次够得着最多格、挖得成的地方;
 * {@code numen.work.collect} 只捡。挖一块区域的整件事是这三条原子命令一轮轮组合({@link GameTestKit#mine}):开门出屋、树林与埋矿、
 * 够不着时如实收场。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class DigGameTests {

    /**
     * 挖掘也走门:黑曜石屋(铁镐非正确工具,成本模型按不可破对待——拆墙不是廉价选项)关住她,矿在屋外,唯一通路是关着的橡木门。
     * {@code arrive = "dig"} 的寻路复用同一条开门链;收工后墙体完好(确实没打洞)。
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
        mineScanned(helper, companion, 9, "minecraft:gold_ore", 2);

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
        Mining mine = mineScanned(helper, companion, 9, "minecraft:acacia_log", 2);

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
     * 场地要够高:原木得在场地的屏障顶棚底下,垫了方块才真够得着。
     */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_out_of_reach_ends_instead_of_hanging(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos logRel = new BlockPos(8, 10, 8);
        level.setBlockAndUpdate(helper.absolutePos(logRel), Blocks.STRIPPED_BIRCH_LOG.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_skyward", new BlockPos(7, 2, 8), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        Mining mine = mineScanned(helper, companion, 9, "minecraft:stripped_birch_log", 1);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "dig has not finished");
            String reply = mine.outcome();
            helper.assertTrue(!mine.succeeded() && reply.contains("found no path to target")
                            && reply.contains("every way needs blocks to pillar or bridge with"),
                    "an out-of-reach log did not end as unreachable for want of blocks to pillar with: " + reply);
            helper.assertTrue(level.getBlockState(helper.absolutePos(logRel)).is(Blocks.STRIPPED_BIRCH_LOG),
                    "the out-of-reach log is gone");
            // 悬着的原木收走,后面批次的扫描不会把它当目标
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
     * 云杉林:三棵云杉围着她,树干六七格高,上半截一圈圈裹着树叶;手持铁斧砍 8 根原木——
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

        mineScanned(helper, companion, 10, "minecraft:spruce_log", 8);

        succeedWhen(helper, () -> {
            helper.assertTrue(companion.getInventory().countItem(Items.SPRUCE_LOG) >= 8,
                    "companion has not gathered 8 spruce logs");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 够数靠的那一件躺在别处:砍下的第一根原木已经算进数里,掉落物却挪到了几格外,而她站的地方手边还够得着下一根。
     * {@code numen.work.dig(…, {count = 1})} 挖够一格就停,不再砍手边那根;{@code numen.work.collect()} 走过去把那一根捡回来。
     *
     * <p>钉的是"挖几格"数的是挖掉的格,与掉落物在哪无关;捡是另一条命令的事。用场地里独一种的去皮橡木,免得看见别的用例的原木。
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
        // 第一根的掉落物一露面就挪开:挪到几格外,还在 numen.work.collect 默认看得见的八格以内
        Vec3 far = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(6, 2, 7)));
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
        Mining mine = mineScanned(helper, companion, 5, "minecraft:stripped_oak_log", 1);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "dig has not finished");
            helper.assertTrue(mine.succeeded() && companion.getInventory().countItem(Items.STRIPPED_OAK_LOG) == 1,
                    "the counted log was not fetched: " + mine.outcome());
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

        mineScanned(helper, companion, 8, "minecraft:deepslate_diamond_ore", 2);

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
        Mining mine = mineScanned(helper, companion, 8, "minecraft:iron_ore", 1);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "dig has not finished");
            helper.assertTrue(!mine.succeeded() && mine.outcome().contains("can't be dug: breaking it would let liquid in"),
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
        Mining mine = mineScanned(helper, companion, 8, "minecraft:diamond_ore", 1);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "dig has not finished");
            helper.assertTrue(!mine.succeeded() && mine.outcome().contains("my tools can't harvest diamond_ore"),
                    "the failure does not say the tool is short: " + mine.outcome());
            helper.assertTrue(helper.getLevel().getBlockState(ore).is(Blocks.DIAMOND_ORE), "the ore was broken");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 点名的方块都不在了:一块绿宝石矿,她看见之后被人挖走了;照看见的样子点名它挖,派发当场拒收,不满世界乱走,如实说点名的都不在了、
     * 下一步照抄哪一行再看。点名的几格本来就只是空气,也当场拒收,说那几格里没有可挖的。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_blocks_that_are_gone_says_so_and_how_to_look_again(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_prospector", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        BlockPos start = helper.absolutePos(new BlockPos(3, 2, 4));
        BlockPos gone = helper.absolutePos(new BlockPos(5, 2, 4));
        BlockPos air = helper.absolutePos(new BlockPos(5, 3, 4));
        ToolRun dig = lua(companion, "numen.work.dig({name = \"minecraft:emerald_ore\", pos = " + xyz(gone) + "})");
        ToolRun dug = lua(companion, "numen.work.dig(" + xyz(air) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(dig.done() && dug.done(), "dig has not finished");
            helper.assertTrue(!dig.succeeded() && dig.task() == null && "not_found".equals(dig.kind())
                            && dig.outcome().contains("the blocks given are all gone or have changed since they were "
                                    + "seen, so I did not start")
                            && "numen.scan.blocks(\"minecraft:emerald_ore\")".equals(dig.hint()),
                    "the refusal does not say the blocks are gone and how to look again: " + dig.outcome() + " | "
                            + dig.hint());
            helper.assertTrue(!dug.succeeded() && dug.task() == null && "not_found".equals(dug.kind())
                            && dug.outcome().contains("the 1 cell(s) given hold nothing to dig — air or fluid"),
                    "the refusal does not say the cells hold nothing: " + dug.outcome());
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
        Mining mine = mineScanned(helper, companion, 8, "minecraft:obsidian", 1);

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
        Mining mine = mineScanned(helper, companion, 7, "minecraft:gold_block", 12);

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
    // ==================== 挖点名的格 ====================

    /**
     * 挖一格:点名它的坐标。一捆干草块在她手边,{@code numen.work.dig(pos)} 当场挖掉;收场说挖了 1 格、掉下来的那一捆落在了哪一格
     * ——那段账与交回的 {@code drops} 说的是同一件:落地、在被挖那一格(或滑到隔壁一格)、还躺在那儿的那一件的编号。她一步没动。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_one_cell_by_its_coordinates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos hay = helper.absolutePos(new BlockPos(9, 2, 5));
        level.setBlockAndUpdate(hay, Blocks.HAY_BLOCK.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_one_cell", new BlockPos(6, 2, 5), false);
        BlockPos stand = companion.blockPosition();
        ToolRun dig = lua(companion, "numen.work.dig(" + xyz(hay) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(dig.done(), "work dig has not finished");
            helper.assertTrue(dig.succeeded() && dig.outcome().startsWith("dug 1 cell(s) of hay_block"),
                    "the cell was not dug: " + dig.outcome());
            var drop = onlyDrop(dig);
            BlockPos landed = drop.pos();
            helper.assertTrue(drop.fate() == com.dwinovo.numen.core.act.Drops.Fate.LANDED && drop.count() == 1
                            && landed.getY() == hay.getY() && Math.abs(landed.getX() - hay.getX()) <= 1
                            && Math.abs(landed.getZ() - hay.getZ()) <= 1,
                    "the drop did not land by the dug cell: " + drop);
            helper.assertTrue(dig.outcome().contains("Drops: 1 hay_block landed at " + xyz(landed) + "."),
                    "the reply does not say where the drop landed: " + dig.outcome());
            helper.assertTrue(drop.id().isPresent() && level.getEntity(drop.id().get())
                            instanceof net.minecraft.world.entity.item.ItemEntity item && item.getItem().is(Items.HAY_BLOCK),
                    "the id given is not the hay lying there: " + drop);
            helper.assertTrue(level.getBlockState(hay).isAir(), "the hay is still there");
            helper.assertTrue(companion.blockPosition().equals(stand), "she moved to dig a cell within her reach");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 挖岩浆上方的方块:黑曜石压在一池岩浆上,她站在池边够得着的地方挖。黑曜石掉下来落进岩浆烧掉了——挖的结果说清楚:回执那一句
     * 说它掉进岩浆烧掉了,数据里那一笔是 destroyed、毁它的是 minecraft:lava,没有一件落在地上。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_dig")
    public static void digging_above_lava_says_the_drop_burned(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 三乘三的一池岩浆嵌在地面里,底下垫石头;正中上面压一块黑曜石
        for (int x = 8; x <= 10; x++) {
            for (int z = 4; z <= 6; z++) {
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 0, z)), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 1, z)), Blocks.LAVA.defaultBlockState());
            }
        }
        BlockPos obsidian = helper.absolutePos(new BlockPos(9, 2, 5));
        level.setBlockAndUpdate(obsidian, Blocks.OBSIDIAN.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_over_lava", new BlockPos(5, 2, 5), false);
        companion.getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
        ToolRun dig = lua(companion, "numen.work.dig(" + xyz(obsidian) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(dig.done(), "work dig has not finished");
            helper.assertTrue(dig.succeeded() && dig.outcome().startsWith("dug 1 cell(s) of obsidian"),
                    "the obsidian was not dug: " + dig.outcome());
            var drop = onlyDrop(dig);
            helper.assertTrue(drop.fate() == com.dwinovo.numen.core.act.Drops.Fate.DESTROYED
                            && drop.cause().equals(java.util.Optional.of("minecraft:lava"))
                            && drop.item().equals("minecraft:obsidian") && drop.id().isEmpty(),
                    "the drop is not told as burned in the lava: " + drop);
            helper.assertTrue(dig.outcome().contains("Drops: 1 obsidian fell into lava and burned up at "),
                    "the reply does not say the drop burned in the lava: " + dig.outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 这一挖的结果里只有一笔掉落物去向,交回它。 */
    private static com.dwinovo.numen.core.act.Drops.Drop onlyDrop(ToolRun dig) {
        var drops = dig.result(com.dwinovo.numen.core.task.dig.DigCompanionTask.Dug.class).drops();
        if (drops.size() != 1) {
            throw new net.minecraft.gametest.framework.GameTestAssertException("expected one drop: " + drops);
        }
        return drops.get(0);
    }

    /**
     * 扫描来的一团只挖还是当时那种方块的格:四块铜矿一扫成一团,都在她手边;开挖之前有人把其中一块换成了石头。她照扫描给的方块
     * 原样点名,挖掉另外三块,那块石头原样留着。
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
        NumenPlayer companion = spawnAt(helper, "gametest_copper", new BlockPos(6, 2, 6), false);
        companion.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        ToolRun scanned = scan(companion, 7, "minecraft:copper_ore");
        ToolRun[] dig = new ToolRun[1];

        succeedWhen(helper, () -> {
            if (dig[0] == null) {
                helper.assertTrue(scanned.reply() != null, "the scan has not replied");
                level.setBlockAndUpdate(changed, Blocks.STONE.defaultBlockState());
                dig[0] = lua(companion, "numen.work.dig(" + blocksIn(scanned.reply()) + ")");
            }
            helper.assertTrue(dig[0].done(), "work dig has not finished");
            helper.assertTrue(dig[0].succeeded() && dig[0].outcome().startsWith("dug 3 cell(s) of copper_ore"),
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
     * 挖点名的格:她手边一个 3×3×3 的盒子(底层石头、中层泥土、顶层是空气),27 格的位置一起交给 {@code numen.work.dig},格里是什么
     * 挖什么,空气跳过;挖完盒子里全是空气。接着 {@code numen.work.collect},泥土与圆石进了她的包。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_given_cells_digs_whatever_they_hold(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos low = helper.absolutePos(new BlockPos(7, 2, 5));
        BlockPos high = helper.absolutePos(new BlockPos(9, 4, 7));
        for (BlockPos pos : BlockPos.betweenClosed(low, high)) {
            BlockState fill = pos.getY() == high.getY() ? Blocks.AIR.defaultBlockState()
                    : pos.getY() == low.getY() ? Blocks.STONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
            level.setBlockAndUpdate(pos, fill);
        }
        NumenPlayer companion = spawnAt(helper, "gametest_pitman", new BlockPos(8, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        companion.getInventory().add(new ItemStack(Items.IRON_SHOVEL));
        List<String> pit = new java.util.ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(low, high)) {
            pit.add(xyz(pos));
        }
        ToolRun[] dig = new ToolRun[1];
        ToolRun[] collect = new ToolRun[1];

        succeedWhen(helper, () -> {
            if (dig[0] == null) {
                dig[0] = lua(companion, "numen.work.dig({" + String.join(", ", pit) + "})");
            }
            helper.assertTrue(dig[0].done(), "work dig has not finished");
            helper.assertTrue(dig[0].succeeded() && dig[0].outcome().startsWith("dug 18 cell(s) of"),
                    "the pit was not dug out: " + dig[0].outcome());
            for (BlockPos pos : BlockPos.betweenClosed(low, high)) {
                helper.assertTrue(level.getBlockState(pos).isAir(), "the pit still holds a block at " + pos.toShortString());
            }
            if (collect[0] == null) {
                collect[0] = lua(companion, "numen.work.collect()");
            }
            helper.assertTrue(collect[0].done(), "work collect has not finished");
            helper.assertTrue(companion.getInventory().countItem(Items.DIRT) >= 9
                            && companion.getInventory().countItem(Items.COBBLESTONE) >= 9,
                    "what came out of the pit was not picked up: " + collect[0].outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 只挖手够得着的:一排珠光蛙明灯从她跟前伸到十格外,一扫成一团,整团交给 {@code numen.work.dig}。它挖掉手够得着的那几块就收场,算成功;
     * 她一步没动,回执说还剩几格够不着、最近那格在哪,以及能照抄的 {@code numen.move.to … arrive = "dig"};够不着的一块不少。
     */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_dig")
    public static void dig_digs_only_what_her_hand_reaches_and_reports_the_rest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_vein_edge", new BlockPos(7, 2, 8), false);
        BlockPos stand = companion.blockPosition();
        List<BlockPos> vein = new java.util.ArrayList<>();
        for (int x = 6; x <= 18; x++) {
            BlockPos cell = helper.absolutePos(new BlockPos(x, 2, 10));
            level.setBlockAndUpdate(cell, Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState());
            vein.add(cell);
        }
        ToolRun scanned = scan(companion, 18, "minecraft:pearlescent_froglight");
        ToolRun[] dig = new ToolRun[1];

        succeedWhen(helper, () -> {
            if (dig[0] == null) {
                helper.assertTrue(scanned.done() && scanned.succeeded(), "the scan has not replied: " + scanned.reply());
                dig[0] = lua(companion, "numen.work.dig(" + blocksIn(scanned.reply()) + ")");
            }
            helper.assertTrue(dig[0].done(), "work dig has not finished");
            List<BlockPos> dug = vein.stream().filter(c -> level.getBlockState(c).isAir()).toList();
            List<BlockPos> left = vein.stream().filter(c -> !level.getBlockState(c).isAir()).toList();
            helper.assertTrue(!dug.isEmpty() && !left.isEmpty(), "the vein was not split by her reach: " + dig[0].outcome());
            for (BlockPos cell : dug) {
                helper.assertTrue(companion.getEyePosition().distanceTo(Vec3.atCenterOf(cell))
                                <= companion.blockInteractionRange() + 1,
                        "a froglight beyond her reach was dug at " + cell.toShortString());
            }
            BlockPos nearest = left.stream().min(java.util.Comparator.comparingDouble(stand::distSqr)).orElseThrow();
            String said = dig[0].outcome();
            helper.assertTrue(dig[0].succeeded() && said.startsWith("dug " + dug.size() + " cell(s) of pearlescent_froglight")
                            && said.contains(left.size() + " more cell(s) of the " + vein.size()
                                    + " given are out of my reach from here, the nearest at " + coords(nearest))
                            && said.contains("to dig them: `numen.move.to(" + xyz(nearest) + ", {arrive = \"dig\"})`, "
                                    + "then dig the same blocks again"),
                    "the reply does not account for the cells beyond her reach: " + said);
            helper.assertTrue(companion.blockPosition().equals(stand), "she moved while digging");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * {@code arrive = "dig"} 几格,停在一次够得着最多格的地方:一条两格高的石头走廊里,她站在当中;一头是一块孤零零的铁矿,
     * 另一头一样远的地方并排两块。路一样长,她走到并排那两块跟前停下,站在那儿两块都够得着;接着 {@code numen.work.dig} 当场挖掉两块,
     * 一步不挪。
     */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_dig")
    public static void arrive_dig_stops_where_the_hand_reaches_the_most_cells(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 走廊沿 x,宽一格(z=10)、高两格;两侧与顶上是石头,两头各封一块石头
        for (int x = 1; x <= 19; x++) {
            for (int y = 2; y <= 4; y++) {
                for (int z = 9; z <= 11; z++) {
                    boolean hall = z == 10 && y <= 3 && x >= 2 && x <= 18;
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, z)),
                            hall ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState());
                }
            }
        }
        BlockPos lone = helper.absolutePos(new BlockPos(2, 2, 9));
        BlockPos pairA = helper.absolutePos(new BlockPos(18, 2, 9));
        BlockPos pairB = helper.absolutePos(new BlockPos(18, 2, 11));
        for (BlockPos ore : List.of(lone, pairA, pairB)) {
            level.setBlockAndUpdate(ore, Blocks.IRON_ORE.defaultBlockState());
        }
        NumenPlayer companion = spawnAt(helper, "gametest_coverer", new BlockPos(10, 2, 10), false);
        companion.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        String irons = "{" + xyz(lone) + ", " + xyz(pairA) + ", " + xyz(pairB) + "}";
        ToolRun[] walk = new ToolRun[1];
        ToolRun[] dig = new ToolRun[1];
        BlockPos[] stood = new BlockPos[1];

        succeedWhen(helper, () -> {
            if (walk[0] == null) {
                walk[0] = lua(companion, "numen.move.to(" + irons + ", {arrive = \"dig\"})");
            }
            helper.assertTrue(walk[0].done(), "move goto has not finished");
            if (dig[0] == null) {
                helper.assertTrue(walk[0].succeeded(), "the walk failed: " + walk[0].outcome());
                stood[0] = companion.blockPosition();
                helper.assertTrue(stood[0].getX() > helper.absolutePos(new BlockPos(10, 2, 10)).getX(),
                        "she went to the lone ore rather than the pair: stopped at " + stood[0].toShortString());
                dig[0] = lua(companion, "numen.work.dig(" + irons + ")");
            }
            helper.assertTrue(dig[0].done(), "work dig has not finished");
            helper.assertTrue(dig[0].succeeded() && dig[0].outcome().startsWith("dug 2 cell(s) of iron_ore")
                            && level.getBlockState(pairA).isAir() && level.getBlockState(pairB).isAir(),
                    "standing where she stopped, she did not dig both ores of the pair: " + dig[0].outcome());
            helper.assertTrue(level.getBlockState(lone).is(Blocks.IRON_ORE), "the lone ore was dug");
            helper.assertTrue(companion.blockPosition().equals(stood[0]), "she moved while digging");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    // ==================== 远处:只报告,怎么过去由模型写 ====================

    /** 远处的几条用例用 52 格见方的场地:她站在一角,另一角离她六十多格。和平难度、正午。 */
    @BeforeBatch(batch = "numen_dig_far")
    public static void prepareDigFarBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** "x,y,z" 的写法,和回执里点坐标的一样。 */
    private static String coords(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /**
     * 够不着的不去:两块赭黄蛙明灯在场地另一角、六十多格外,扫到之后整团交给 {@code numen.work.dig},她手边一格都没有。派发当场拒收,
     * 她不出发,回执说两格都
     * 够不着、最近那格在哪,以及能照抄的 {@code numen.move.to … arrive = "dig"} 与之后再挖的那一行。
     */
    @GameTest(template = "floor52", timeoutTicks = 100000, batch = "numen_dig_far")
    public static void dig_stays_put_and_gives_the_way_when_the_blocks_lie_beyond_her_reach(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos nearer = helper.absolutePos(new BlockPos(50, 2, 50));
        BlockPos farther = helper.absolutePos(new BlockPos(51, 2, 50));
        level.setBlockAndUpdate(nearer, Blocks.OCHRE_FROGLIGHT.defaultBlockState());
        level.setBlockAndUpdate(farther, Blocks.OCHRE_FROGLIGHT.defaultBlockState());
        BlockPos start = helper.absolutePos(new BlockPos(3, 2, 3));
        NumenPlayer companion = spawnAt(helper, "gametest_stay_put", new BlockPos(3, 2, 3), false);
        ToolRun scanned = scan(companion, 72, "minecraft:ochre_froglight");
        ToolRun[] dig = new ToolRun[1];
        String[] blocks = new String[1];

        succeedWhen(helper, () -> {
            helper.assertTrue(scanned.done(), "the scan has not replied");
            if (dig[0] == null) {
                blocks[0] = blocksIn(scanned.reply());
                dig[0] = lua(companion, "numen.work.dig(" + blocks[0] + ")");
            }
            helper.assertTrue(dig[0].done(), "dig has not finished");
            String said = dig[0].outcome();
            helper.assertTrue(!dig[0].succeeded() && dig[0].task() == null && "out_of_reach".equals(dig[0].kind())
                            && said.contains("I did not start: none of the cells of the 2 given still to dig is within my "
                                    + "reach where I stand")
                            && said.contains("2 more cell(s) of the 2 given are out of my reach from here, "
                                    + "the nearest at " + coords(nearer))
                            && ("numen.move.to(" + xyz(nearer) + ", {arrive = \"dig\"})\nnumen.work.dig("
                                    + blocks[0].substring(1, blocks[0].length() - 1) + ")").equals(dig[0].hint()),
                    "the reply does not say what lies beyond her reach and how to get there: " + said + " | "
                            + dig[0].kind() + " | " + dig[0].hint());
            helper.assertTrue(companion.blockPosition().distSqr(start) <= 4, "she set off for blocks beyond her reach");
            helper.assertTrue(level.getBlockState(nearer).is(Blocks.OCHRE_FROGLIGHT)
                    && level.getBlockState(farther).is(Blocks.OCHRE_FROGLIGHT), "a froglight beyond her reach was dug");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 点名的一团整个在手边以外:一次扫描找到远处一团与近处一团;把远处那一团交给 {@code numen.work.dig},派发当场拒收,说它在哪、
     * 怎么过去,不派活,蛙明灯一块不少。
     */
    @GameTest(template = "floor52", timeoutTicks = 4000, batch = "numen_dig_far")
    public static void dig_a_cluster_beyond_her_reach_is_refused_at_once(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> far = List.of(new BlockPos(48, 2, 50), new BlockPos(49, 2, 50), new BlockPos(50, 2, 50));
        for (BlockPos rel : far) {
            level.setBlockAndUpdate(helper.absolutePos(rel), Blocks.VERDANT_FROGLIGHT.defaultBlockState());
        }
        BlockPos nearRel = new BlockPos(8, 2, 8);
        level.setBlockAndUpdate(helper.absolutePos(nearRel), Blocks.VERDANT_FROGLIGHT.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_far_group", new BlockPos(3, 2, 3), false);
        ToolRun scanned = scan(companion, 80, "minecraft:verdant_froglight");
        String[] refusal = new String[2];

        succeedWhen(helper, () -> {
            if (refusal[0] == null) {
                helper.assertTrue(scanned.reply() != null, "scan_blocks has not replied");
                var clusters = clustersIn(scanned.reply());
                var farCluster = clusterHolding(clusters, helper.absolutePos(far.get(0)));
                var nearCluster = clusterHolding(clusters, helper.absolutePos(nearRel));
                helper.assertTrue(farCluster != null && nearCluster != null && farCluster != nearCluster
                                && clusters.get(0).getAsJsonObject() == nearCluster,
                        "the scan did not return the near and the far froglights as two clusters, near first: "
                                + scanned.reply());
                String blocks = blocksOf(farCluster);
                var nearest = farCluster.getAsJsonObject("nearest").getAsJsonObject("pos");
                refusal[1] = "numen.move.to({x = " + nearest.get("x").getAsInt() + ", y = " + nearest.get("y").getAsInt()
                        + ", z = " + nearest.get("z").getAsInt() + "}, {arrive = \"dig\"})\nnumen.work.dig("
                        + blocks.substring(1, blocks.length() - 1) + ")";
                ToolRun dig = lua(companion, "numen.work.dig(" + blocks + ")");
                helper.assertTrue(dig.task() == null, "a cluster wholly beyond her reach was accepted");
                refusal[0] = dig.kind() + " | " + dig.outcome() + " | " + dig.hint();
            }
            helper.assertTrue(refusal[0] != null && refusal[0].startsWith("out_of_reach | ")
                            && refusal[0].contains("are out of my reach from here")
                            && refusal[0].endsWith(" | " + refusal[1]),
                    "the refusal does not say where the cluster is and how to get there: " + refusal[0]);
            for (BlockPos rel : far) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).is(Blocks.VERDANT_FROGLIGHT),
                        "a froglight of the refused cluster is gone at " + rel.toShortString());
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 开路再挖:一块远古残骸埋在二十多格外一座石头小丘的正中,四面都隔着两格石头。{@code numen.move.to … arrive = "dig"} 走到手够得着
     * 它的地方就停,残骸原样(到达只管站位,那一格留给挖的一方);接着 {@code numen.work.dig} 挖开挡着的石头,把它挖出来,掉落物
     * 留在原地;再走进残骸那一格({@code numen.move.to … costs = {dig = true, place = true, consent = false}}),捡到手。
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
        ToolRun walk = lua(companion, "numen.move.to(" + xyz(debris) + ", {arrive = \"dig\", costs = {dig = true, place = true, consent = false}})");
        ToolRun[] dig = new ToolRun[1];
        ToolRun[] fetch = new ToolRun[1];

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "numen.move.to has not finished");
            if (dig[0] == null) {
                helper.assertTrue(walk.succeeded() && walk.outcome().contains("within reach")
                                && walk.outcome().contains("`numen.work.dig(" + xyz(debris) + ")`"),
                        "the walk did not end within reach of the buried block: " + walk.outcome());
                helper.assertTrue(level.getBlockState(debris).is(Blocks.ANCIENT_DEBRIS),
                        "the walk dug the block it was only meant to reach");
                helper.assertTrue(companion.getEyePosition().distanceTo(Vec3.atCenterOf(debris))
                                <= companion.blockInteractionRange() + 1,
                        "she stopped out of reach of the buried block");
                dig[0] = lua(companion, "numen.work.dig(" + xyz(debris) + ")");
            }
            helper.assertTrue(dig[0].done(), "work dig has not finished");
            helper.assertTrue(dig[0].succeeded() && level.getBlockState(debris).isAir(),
                    "the buried block was not dug out: " + dig[0].outcome());
            if (fetch[0] == null) {
                fetch[0] = lua(companion, "numen.move.to(" + xyz(debris) + ", {costs = {dig = true, place = true, consent = false}})");
            }
            helper.assertTrue(fetch[0].done(), "the walk to the drop has not finished");
            helper.assertTrue(companion.getInventory().countItem(Items.ANCIENT_DEBRIS) == 1,
                    "the buried block was not picked up: " + fetch[0].outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 数字(坐标、件数、刻数)抹掉,比两份回执的措辞。 */
    private static String withoutNumbers(String reply) {
        return reply.replaceAll("-?\\d+", "#");
    }

    /**
     * 上千格的一团交给服务端的函数:一层 32 x 32 的蛙明灯,{@code numen.scan.blocks} 交回的几团(每团一百多格,合起来上千)先摊成一串格,
     * 再整串作为 {@code avoid} 交给 {@code numen.route.plan}。程序跑在服务端,团不经网线,不受一个上行包的大小管(从前的 32 KB 上限
     * 一团就装不下)。
     */
    @GameTest(template = "floor52", timeoutTicks = 4000, batch = "numen_dig_far")
    public static void a_scanned_field_of_a_thousand_cells_goes_into_a_server_function_whole(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int cells = 0;
        for (int x = 10; x < 42; x++) {
            for (int z = 10; z < 42; z++) {
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 2, z)),
                        Blocks.VERDANT_FROGLIGHT.defaultBlockState());
                cells++;
            }
        }
        NumenPlayer companion = spawnAt(helper, "gametest_thousand", new BlockPos(3, 2, 3), false);
        BlockPos to = helper.absolutePos(new BlockPos(3, 2, 20));
        ToolRun run = lua(companion, """
                local all = {}
                for _, group in ipairs(numen.scan.blocks("minecraft:verdant_froglight", {radius = 60})) do
                  for _, block in ipairs(group.blocks) do
                    all[#all + 1] = block.pos
                  end
                end
                numen.route.plan({to = %s, avoid = all})
                return #all
                """.formatted(xyz(to)));
        int expected = cells;

        succeedWhen(helper, () -> {
            helper.assertTrue(run.done(), "the program has not ended");
            helper.assertTrue(run.ranToTheEnd(), "the program failed: " + run.receipt());
            helper.assertTrue(run.data().get("returned").getAsInt() == expected,
                    "the scan did not hand over every cell: " + run.receipt());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }
}
