package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.Bodies;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * 挖掘:{@code numen.work.dig} 挖点名的格(扫描来的一团、框出来的坑、一格坐标):手够得着的先挖,够不着的走到够得着剩下最多格的地方再挖,
 * 挡着的天然地形一并挖开,不捡;{@code numen.work.collect} 只捡。挖一块区域的整件事是这两条原子命令一轮轮组合({@link GameTestKit#mine}):
 * 树冠上的原木与埋矿。
 */
public class DigGameTests {

    /**
     * 树冠上的原木站在地上挖:两根金合欢原木悬在她脚上五格、六格,四周一圈树叶,她身上只有一把斧头,
     * 没有垫脚的方块。爬上去贴着它们是做不到的;站在底下仰头,眼睛离它们 3.38 格、4.38 格,在交互距离里——
     * 斜着看过去挡着的树叶先挖开,再挖原木。原木正下方留空,掉落物落回地面。
     */
    @GameTest(template = "floor16", timeoutTicks = 207, batch = ON_FOOT)
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
        dropsFallStraight(helper);
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
    @GameTest(template = "floor20", timeoutTicks = 200, batch = ON_FOOT)
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

    /**
     * 埋在石头里的钻石:一块九乘九、六层高的深板岩,中间埋着四颗深板岩钻石矿;她站在顶上,手持铁镐
     * 往下挖进去,采得 2 颗钻石。盯的是埋矿的站位:脚不能高于矿,得一路挖着往下走,再就地挖矿。
     */
    @GameTest(template = "floor20", timeoutTicks = 390, batch = ON_FOOT)
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
        NumenPlayer companion = Bodies.spawn(level.getServer(), UUID.randomUUID(),
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
    @GameTest(template = "floor16", timeoutTicks = 200, batch = ON_FOOT)
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

    /**
     * 服务端挖掘状态机里"正在挖一格方块"的私有字段:挖掘器照真客户端发 START/STOP,服务端收没收下这一下只记在它上面,
     * 读它区分"服务端还在挖"与"服务端把这一下退回来了"。GameTest 里的名字是 Mojang 名,各平台一致。
     */
    private static final java.lang.invoke.VarHandle DESTROYING_BLOCK = gameModeField("isDestroyingBlock");

    /** 服务端挖掘状态机里"挂着一格延迟破坏"的私有字段:用例读它确认场景搭对了(鼠标不读)。 */
    private static final java.lang.invoke.VarHandle DELAYED_DESTROY = gameModeField("hasDelayedDestroy");

    private static java.lang.invoke.VarHandle gameModeField(String name) {
        try {
            return java.lang.invoke.MethodHandles
                    .privateLookupIn(net.minecraft.server.level.ServerPlayerGameMode.class, java.lang.invoke.MethodHandles.lookup())
                    .findVarHandle(net.minecraft.server.level.ServerPlayerGameMode.class, name, boolean.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** 服务端这一刻认不认为她正在挖一格方块。 */
    private static boolean digging(NumenPlayer companion) {
        return (boolean) DESTROYING_BLOCK.get(companion.gameMode);
    }

    /** 手里的镐挖不下这种矿(木镐对钻石矿):当场失败,说清楚是工具不够,矿原样留着。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = ON_FOOT)
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
    @GameTest(template = "floor16", timeoutTicks = 200, batch = ON_FOOT)
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
    @GameTest(template = "floor16", timeoutTicks = 200, batch = ON_FOOT)
    public static void owner_stop_mid_dig_leaves_the_block(GameTestHelper helper) {
        BlockPos block = helper.absolutePos(new BlockPos(5, 2, 4));
        helper.getLevel().setBlockAndUpdate(block, Blocks.OBSIDIAN.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_interrupted", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
        Mining mine = mineScanned(helper, companion, 8, "minecraft:obsidian", 1);

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(digging(companion),
                        "she has not started digging the obsidian"))
                .thenExecute(() -> com.dwinovo.numen.api.task.CompanionTickDispatcher.cancelFor(companion))
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
    @GameTest(template = "floor16", timeoutTicks = 600, batch = ON_FOOT)
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
     * 只挖点名的那一格:旁边另一捆没点名的干草原样立着。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = ON_FOOT)
    public static void dig_one_cell_by_its_coordinates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos hay = helper.absolutePos(new BlockPos(9, 2, 5));
        level.setBlockAndUpdate(hay, Blocks.HAY_BLOCK.defaultBlockState());
        BlockPos spare = helper.absolutePos(new BlockPos(9, 2, 8));
        level.setBlockAndUpdate(spare, Blocks.HAY_BLOCK.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_one_cell", new BlockPos(6, 2, 5), false);
        BlockPos stand = companion.blockPosition();
        ToolRun dig = lua(companion, "numen.work.dig(" + xyz(hay) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(dig.done(), "work dig has not finished");
            helper.assertTrue(dig.succeeded() && dig.outcome().startsWith("dug 1 cell(s) of hay_block"),
                    "the cell was not dug: " + dig.outcome());
            var drop = onlyDrop(dig);
            BlockPos landed = drop.pos();
            helper.assertTrue(drop.fate() == com.dwinovo.numen.act.Drops.Fate.LANDED && drop.count() == 1
                            && landed.getY() == hay.getY() && Math.abs(landed.getX() - hay.getX()) <= 1
                            && Math.abs(landed.getZ() - hay.getZ()) <= 1,
                    "the drop did not land by the dug cell: " + drop);
            helper.assertTrue(dig.outcome().contains("Drops: 1 hay_block landed at " + xyz(landed) + "."),
                    "the reply does not say where the drop landed: " + dig.outcome());
            helper.assertTrue(drop.id().isPresent() && new com.dwinovo.numen.api.sdk.EntityRef(drop.id().get()).in(level)
                            instanceof net.minecraft.world.entity.item.ItemEntity item && item.getItem().is(Items.HAY_BLOCK),
                    "the id given is not the hay lying there: " + drop);
            helper.assertTrue(level.getBlockState(hay).isAir(), "the hay is still there");
            helper.assertTrue(level.getBlockState(spare).is(Blocks.HAY_BLOCK), "the hay that was not named was dug");
            helper.assertTrue(companion.blockPosition().equals(stand), "she moved to dig a cell within her reach");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 挖岩浆上方的方块:黑曜石压在一池岩浆上,她站在池边够得着的地方挖。黑曜石掉下来落进岩浆烧掉了——挖的结果说清楚:回执那一句
     * 说它掉进岩浆烧掉了,数据里那一笔是 destroyed、毁它的是 minecraft:lava,没有一件落在地上。
     */
    @GameTest(template = "floor16", timeoutTicks = 600, batch = ON_FOOT)
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
            helper.assertTrue(drop.fate() == com.dwinovo.numen.act.Drops.Fate.DESTROYED
                            && drop.cause().equals(java.util.Optional.of("minecraft:lava"))
                            && drop.item().equals("minecraft:obsidian") && drop.id().isEmpty(),
                    "the drop is not told as burned in the lava: " + drop);
            helper.assertTrue(dig.outcome().contains("Drops: 1 obsidian fell into lava and burned up at "),
                    "the reply does not say the drop burned in the lava: " + dig.outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 服务端把挖到一半的一格挂成延迟破坏:她这头在一个游戏刻里连按,累满 1 就送 STOP,服务端那头才过了零刻、进度远不够,
     * 黑曜石还在。她照原版客户端,方块还在、键还按着就缓过手同一格再挖一轮,方块没了就是挖掉了:中途不报"没让挖",最后报挖掉了,
     * 掉落物对得上。(场景的前提——第一次 STOP 之后服务端确实挂着延迟破坏——由用例读服务端的记账确认,鼠标不读。)
     */
    @GameTest(template = "floor16", timeoutTicks = 561, batch = ON_FOOT)
    public static void the_mouse_waits_for_the_delayed_destroy_the_server_still_holds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos obsidian = helper.absolutePos(new BlockPos(8, 2, 8));
        level.setBlockAndUpdate(obsidian, Blocks.OBSIDIAN.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_delayed", new BlockPos(6, 2, 8), false);
        companion.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        com.dwinovo.numen.api.permission.Permission.setMode(companion, com.dwinovo.numen.permission.Mode.BYPASS);
        var mouse = companion.mouse();
        com.dwinovo.numen.api.entity.Mouse.Strike[] refused = {null};

        steps(helper)
                .thenExecute(() -> companion.look().at(Vec3.atCenterOf(obsidian)))
                .thenExecute(() -> {
                    // 一个刻里连按到她这头送出 STOP 为止
                    for (int presses = 0; presses < 2000 && !mouse.stopSent(); presses++) {
                        mouse.dig();
                    }
                    helper.assertTrue(level.getBlockState(obsidian).is(Blocks.OBSIDIAN) && mouse.stopSent()
                                    && (boolean) DELAYED_DESTROY.get(companion.gameMode),
                            "the server did not hold the block as a delayed destroy: " + level.getBlockState(obsidian));
                })
                .thenWaitUntil(() -> {
                    var strike = mouse.dig();
                    if (strike instanceof com.dwinovo.numen.api.entity.Mouse.Strike.Refused && refused[0] == null) {
                        refused[0] = strike;
                    }
                    helper.assertTrue(refused[0] == null, "the mouse gave up on a block that was still there: " + refused[0]);
                    helper.assertTrue(strike instanceof com.dwinovo.numen.api.entity.Mouse.Strike.Broke,
                            "the obsidian has not broken yet: " + strike);
                    helper.assertTrue(level.getBlockState(obsidian).isAir(), "the block is still there");
                    boolean dropped = !level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                            new net.minecraft.world.phys.AABB(obsidian).inflate(4),
                            item -> item.getItem().is(Items.OBSIDIAN)).isEmpty()
                            || companion.getInventory().countItem(Items.OBSIDIAN) > 0;
                    helper.assertTrue(dropped, "the obsidian drop is nowhere");
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * 服务端把这一格挂成延迟破坏、自己在两次调用之间把它挖掉:挖掉了就是挖掉了,回执报挖到一格、掉落物对得上。
     * 做法:她带着急迫效果挖黑曜石,这一头的进度累得快;累到九成时去掉效果,于是送 STOP 那一刻服务端按没有效果的速度算,进度不够,
     * 挂成延迟破坏。服务端随后在自己的刻里挖掉它——那一刻方块不再"要挖",任务不能把它当"不用挖了"丢掉而报"一格没挖"。
     */
    @GameTest(template = "floor16", timeoutTicks = 600, batch = ON_FOOT)
    public static void dig_counts_a_block_the_server_breaks_as_a_delayed_destroy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos obsidian = helper.absolutePos(new BlockPos(8, 2, 8));
        level.setBlockAndUpdate(obsidian, Blocks.OBSIDIAN.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_hasty", new BlockPos(6, 2, 8), false);
        companion.getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
        companion.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.DIG_SPEED,
                100000, 4, false, false));
        ToolRun dig = lua(companion, "numen.work.dig(" + xyz(obsidian) + ")");

        succeedWhen(helper, () -> {
            if (companion.mouse().progress() >= 0.9f && companion.hasEffect(net.minecraft.world.effect.MobEffects.DIG_SPEED)) {
                companion.removeAllEffects();
            }
            helper.assertTrue(dig.done(), "work dig has not finished");
            helper.assertTrue(dig.succeeded() && dig.outcome().startsWith("dug 1 cell(s) of obsidian"),
                    "the obsidian the server broke was not counted: " + dig.outcome());
            var drop = onlyDrop(dig);
            helper.assertTrue(drop.item().equals("minecraft:obsidian"), "the drop does not match: " + drop);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 这一挖的结果里只有一笔掉落物去向,交回它。 */
    private static com.dwinovo.numen.act.Drops.Drop onlyDrop(ToolRun dig) {
        var drops = dig.result(com.dwinovo.numen.task.dig.DigCompanionTask.Dug.class).drops();
        if (drops.size() != 1) {
            throw new net.minecraft.gametest.framework.GameTestAssertException("expected one drop: " + drops);
        }
        return drops.get(0);
    }

    /**
     * 扫描来的一团只挖还是当时那种方块的格:四块铜矿一扫成一团,都在她手边;开挖之前有人把其中一块换成了石头。她照扫描给的方块
     * 原样点名,挖掉另外三块,那块石头原样留着。
     */
    @GameTest(template = "floor16", timeoutTicks = 288, batch = ON_FOOT)
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
     * 够不着就走过去:一排珠光蛙明灯从她跟前伸到十格外,一扫成一团,整团交给 {@code numen.work.dig}。她挖掉手够得着的那几块,
     * 走到够得着剩下最多块的地方接着挖,全挖掉才收场,回执说挖了几格、没有剩下够不着的。
     */
    @GameTest(template = "floor20", timeoutTicks = 600, batch = ON_FOOT)
    public static void dig_walks_up_to_what_is_out_of_reach_and_digs_it_all(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> vein = new java.util.ArrayList<>();
        for (int x = 6; x <= 18; x++) {
            BlockPos cell = helper.absolutePos(new BlockPos(x, 2, 10));
            level.setBlockAndUpdate(cell, Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState());
            vein.add(cell);
        }
        NumenPlayer companion = spawnAt(helper, "gametest_vein_edge", new BlockPos(7, 2, 8), false);
        BlockPos stand = companion.blockPosition();
        ToolRun scanned = scan(companion, 18, "minecraft:pearlescent_froglight");
        ToolRun[] dig = new ToolRun[1];

        succeedWhen(helper, () -> {
            if (dig[0] == null) {
                helper.assertTrue(scanned.done() && scanned.succeeded(), "the scan has not replied: " + scanned.reply());
                dig[0] = lua(companion, "numen.work.dig(" + blocksIn(scanned.reply()) + ")");
            }
            helper.assertTrue(dig[0].done(), "work dig has not finished");
            String said = dig[0].outcome();
            helper.assertTrue(dig[0].succeeded() && said.startsWith("dug " + vein.size() + " cell(s) of pearlescent_froglight")
                            && !said.contains("out of my reach"),
                    "the reply does not say the whole vein was dug: " + said);
            for (BlockPos cell : vein) {
                helper.assertTrue(level.getBlockState(cell).isAir(), "a froglight is left at " + cell.toShortString());
            }
            helper.assertTrue(companion.blockPosition().distSqr(stand) > 9, "she dug it all without walking along it");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    // ==================== 远处:只报告,怎么过去由模型写 ====================

    /** "x,y,z" 的写法,和回执里点坐标的一样。 */
    private static String coords(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /** 数字(坐标、件数、刻数)抹掉,比两份回执的措辞。 */
    private static String withoutNumbers(String reply) {
        return reply.replaceAll("-?\\d+", "#");
    }
}
