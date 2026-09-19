package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;
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

/** 挖掘:{@code mine} 的站位、够得着、开门出屋、树林与埋矿、够不着时如实收工。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class MineGameTests {

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
        TaskRecord record = call(companion, "mine", args(
                "block_ids", List.of("minecraft:gold_ore"),
                "count", 2)).task();

        BlockPos wallProbe = helper.absolutePos(new BlockPos(1, 3, 3));
        helper.succeedWhen(() -> {
            helper.assertTrue(companion.getInventory().countItem(Items.RAW_GOLD) >= 2,
                    "companion has not mined the gold outside the door");
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
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_mine")
    public static void mine_canopy_logs_from_the_ground(GameTestHelper helper) {
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
        TaskRecord record = call(companion, "mine", args(
                "block_ids", List.of("minecraft:acacia_log"),
                "count", 2)).task();

        helper.succeedWhen(() -> {
            String reply = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null, "mine has not finished");
            helper.assertTrue(record.getResult().success() && companion.getInventory().countItem(Items.ACACIA_LOG) >= 2,
                    "the canopy logs were not gathered from the ground: " + reply);
            for (BlockPos rel : logs) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).isAir(),
                        "a canopy log is still up at " + rel.toShortString());
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 够不着就如实收工:一根原木悬在她脚上八格,站在底下眼睛离它 5.38 格,出了交互距离;她没有垫脚的方块,
     * 爬不上去。任务不该站着一遍遍重搜同一条走不通的路,而是按 NO_PATH 收场、说清楚够不着。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_mine")
    public static void mine_out_of_reach_ends_instead_of_hanging(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos logRel = new BlockPos(8, 10, 8);
        level.setBlockAndUpdate(helper.absolutePos(logRel), Blocks.ACACIA_LOG.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_skyward", new BlockPos(7, 2, 8), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        TaskRecord record = call(companion, "mine", args(
                "block_ids", List.of("minecraft:acacia_log"),
                "count", 1)).task();

        helper.succeedWhen(() -> {
            String reply = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null, "mine has not finished");
            helper.assertTrue(!record.getResult().success() && reply.contains("could not reach"),
                    "an out-of-reach log did not end as unreachable: " + reply);
            helper.assertTrue(level.getBlockState(helper.absolutePos(logRel)).is(Blocks.ACACIA_LOG),
                    "the out-of-reach log is gone");
            // 悬在模板外的原木不收走,后面批次的大半径找方块会把它当目标
            level.removeBlock(helper.absolutePos(logRel), false);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    // ==================== 真实地形挖掘用例(模板取自实际存档地形)====================

    /** 挖掘批次前置:和平难度 + 正午,排除怪物袭扰与昼夜随机性。 */
    @BeforeBatch(batch = "numen_mine")
    public static void prepareMineBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 云杉林:三棵云杉,树干六七格高,上半截一圈圈裹着树叶;手持铁斧砍 8 根原木。
     *
     * <p>超时按游戏刻给得很宽:无头测试服不限速(数百 tps),而寻路搜索预算是墙钟毫秒——
     * 一次 200ms 的真实搜索在这里折合上百游戏刻,超时必须覆盖"搜索墙钟 × tps"的放大。走完整生产链路——目标索引注册与
     * 查询、复合站位、就地挖掘与挖开挡在视线上的树叶、掉落拾取、背包计数。
     */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_mine")
    public static void mine_spruce_grove(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        spruceTree(helper, new BlockPos(5, 2, 5), 6);
        spruceTree(helper, new BlockPos(13, 2, 6), 6);
        spruceTree(helper, new BlockPos(8, 2, 14), 7);
        BlockPos spawn = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                "gametest_logger", UUID.randomUUID(), level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));

        TaskRecord record = call(companion, "mine", args(
                "block_ids", List.of("minecraft:spruce_log"),
                "count", 8)).task();

        helper.succeedWhen(() -> {
            helper.assertTrue(companion.getInventory().countItem(Items.SPRUCE_LOG) >= 8,
                    "companion has not gathered 8 spruce logs");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 埋在石头里的钻石:一块九乘九、六层高的深板岩,中间埋着四颗深板岩钻石矿;她站在顶上,手持铁镐
     * 往下挖进去,采得 2 颗钻石。盯的是埋矿的站位:脚不能高于矿,得一路挖着往下走,再就地挖矿。
     */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_mine")
    public static void mine_buried_diamonds(GameTestHelper helper) {
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

        TaskRecord record = call(companion, "mine", args(
                "block_ids", List.of("minecraft:deepslate_diamond_ore"),
                "count", 2)).task();

        helper.succeedWhen(() -> {
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) >= 2,
                    "companion has not gathered 2 diamonds");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 矿紧挨着岩浆:挖开它岩浆就会淌出来,所以这一格不挖。回执交代它挖不成的原因(贴着流体),矿与岩浆都原样。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_mine")
    public static void mine_leaves_ore_that_borders_lava(GameTestHelper helper) {
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
        ToolRun mine = call(companion, "mine", args("block_ids", List.of("minecraft:iron_ore"), "count", 1));

        helper.succeedWhen(() -> {
            helper.assertTrue(mine.done(), "mine has not finished");
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
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_mine")
    public static void mine_without_a_harvesting_tool_says_the_tool_is_short(GameTestHelper helper) {
        BlockPos ore = helper.absolutePos(new BlockPos(6, 2, 4));
        helper.getLevel().setBlockAndUpdate(ore, Blocks.DIAMOND_ORE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_underequipped", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE));
        ToolRun mine = call(companion, "mine", args("block_ids", List.of("minecraft:diamond_ore"), "count", 1));

        helper.succeedWhen(() -> {
            helper.assertTrue(mine.done(), "mine has not finished");
            helper.assertTrue(!mine.succeeded() && mine.outcome().contains("current tools"),
                    "the failure does not say the tool is short: " + mine.outcome());
            helper.assertTrue(helper.getLevel().getBlockState(ore).is(Blocks.DIAMOND_ORE), "the ore was broken");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 附近根本没有要挖的东西:不满世界乱走,如实说找不到。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_mine")
    public static void mine_with_nothing_in_range_says_none_found(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_prospector", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        BlockPos start = helper.absolutePos(new BlockPos(3, 2, 4));
        ToolRun mine = call(companion, "mine", args("block_ids", List.of("minecraft:emerald_ore"), "count", 1));

        helper.succeedWhen(() -> {
            helper.assertTrue(mine.done(), "mine has not finished");
            helper.assertTrue(!mine.succeeded() && mine.outcome().contains("no reachable"),
                    "the failure does not say nothing was found: " + mine.outcome());
            helper.assertTrue(companion.blockPosition().distSqr(start) <= 4, "she wandered off looking for it");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 挖到一半主人按停止:活按主人停止收场,那块黑曜石还在,挖掘的裂纹也收掉了。 */
    @GameTest(template = "floor16", timeoutTicks = 2000, batch = "numen_mine")
    public static void owner_stop_mid_dig_leaves_the_block(GameTestHelper helper) {
        BlockPos block = helper.absolutePos(new BlockPos(5, 2, 4));
        helper.getLevel().setBlockAndUpdate(block, Blocks.OBSIDIAN.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_interrupted", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
        ToolRun mine = call(companion, "mine", args("block_ids", List.of("minecraft:obsidian"), "count", 1));

        helper.startSequence()
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
}
