package com.dwinovo.numen.gametest;

import static com.dwinovo.numen.gametest.GameTestKit.*;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.permission.PlacedBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.block.Blocks;

/**
 * 去一串格:{@code numen.move.to} 给一串格(Cells)走进其中任意一格,给一串方块走到其中之一旁边,给距离停在离它们不远处;玩家放下的方块(放置记号)不用
 * 点名也不挖。格子由程序写出来,和她拿扫描结果原样交过去一样;都从脚本入口进。
 */
public class CellRouteGameTests {

    private static final String BATCH = "numen_cell_route";

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 两个对角(相对坐标)之间的每一格,绝对坐标。 */
    private static List<BlockPos> box(GameTestHelper helper, BlockPos from, BlockPos to) {
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos rel : BlockPos.betweenClosed(from, to)) {
            cells.add(helper.absolutePos(rel));
        }
        return cells;
    }

    /** 几格写成一串 Pos。 */
    private static String cells(List<BlockPos> cells) {
        return "{" + String.join(", ", cells.stream().map(GameTestKit::xyz).toList()) + "}";
    }

    /** 一口箱子写成查询交回的那种 Block。 */
    private static String block(BlockPos chest) {
        return "{name = \"minecraft:chest\", pos = " + xyz(chest) + "}";
    }

    private static double nearest(List<BlockPos> cells, BlockPos at) {
        return cells.stream().mapToDouble(c -> Math.sqrt(c.distSqr(at))).min().orElseThrow();
    }

    /** 走进几格之一:场地一角的一片空地(脚所在的那一层),她从另一角出发,停在其中某一格。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void goto_several_cells_walks_into_any_of_them(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_penwalker", new BlockPos(2, 2, 2), false);
        List<BlockPos> pen = box(helper, new BlockPos(10, 2, 10), new BlockPos(12, 2, 12));
        ToolRun walk = lua(companion, "numen.move.to(" + cells(pen) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "numen.move.to has not finished");
            helper.assertTrue(walk.succeeded() && walk.outcome().contains("reached"),
                    "she did not walk into the cells: " + walk.outcome());
            helper.assertTrue(pen.contains(companion.blockPosition()),
                    "she stopped outside the cells at " + companion.blockPosition().toShortString());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 几个方块走到其中之一旁边:两格各是一口箱子(写成带 name 的 Block),一口在她跟前、一口在场地对角。没说距离,她走到近的那一口旁边,
     * 不走进去。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void goto_several_blocks_goes_beside_the_nearest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos near = helper.absolutePos(new BlockPos(7, 2, 2));
        BlockPos far = helper.absolutePos(new BlockPos(13, 2, 13));
        level.setBlockAndUpdate(near, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(far, Blocks.CHEST.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_chestuser", new BlockPos(2, 2, 2), false);
        ToolRun walk = lua(companion, "numen.move.to({" + block(near) + ", " + block(far) + "})");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "numen.move.to has not finished");
            helper.assertTrue(walk.succeeded() && walk.outcome().contains("within 2 blocks"),
                    "she did not go beside the near chest: " + walk.outcome());
            helper.assertTrue(Math.sqrt(companion.blockPosition().distSqr(near)) <= 2
                    && !companion.blockPosition().equals(near), "she is not beside the near chest");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 停在几格附近:离其中某一格不超过 3 格就算到,回执照说;不走进去。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void goto_near_several_cells_stops_within_range_of_them(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_hoverer", new BlockPos(2, 2, 2), false);
        List<BlockPos> pen = box(helper, new BlockPos(10, 2, 10), new BlockPos(13, 2, 13));
        ToolRun walk = lua(companion, "numen.move.to(" + cells(pen) + ", 3)");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "numen.move.to has not finished");
            helper.assertTrue(walk.succeeded() && walk.outcome().contains("arrived within 3 blocks of 16 cells"),
                    "she did not stop near the cells: " + walk.outcome());
            BlockPos at = companion.blockPosition();
            double nearest = nearest(pen, at);
            helper.assertTrue(nearest <= 3, "she stopped " + nearest + " blocks from the cells");
            helper.assertTrue(!pen.contains(at), "near should stop at the edge, not walk in");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 路上不挖放置记号的格:关在石屋里,终点在屋东边;东墙带着放置记号(她自己放的,和日式小屋卡在底层来回挖自己刚放下的格同一件事),
     * 别的墙没有(石头是地形,她许挖)。不点名任何禁区,她挖开别的墙绕出去,东墙一块不少。
     */
    @GameTest(template = "floor16", timeoutTicks = 528, batch = BATCH)
    public static void a_walk_never_digs_what_a_player_placed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        roomAround(helper, 7, 7, Blocks.STONE);
        int wallBefore = roomCount(helper, 7, 7, Blocks.STONE);
        NumenPlayer companion = spawnBare(helper, "gametest_keeper", new BlockPos(7, 2, 7), false);
        List<BlockPos> wall = new ArrayList<>();
        for (int y = 2; y <= 4; y++) {
            for (int z = 5; z <= 9; z++) {
                BlockPos cell = helper.absolutePos(new BlockPos(9, y, z));
                wall.add(cell);
                PlacedBlocks.of(level).record(cell, new PlacedBlocks.Placer(companion.getUUID(),
                        companion.getGameProfile().getName()));
            }
        }
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 7));
        ToolRun walk = lua(companion, "numen.move.to(" + xyz(target) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "numen.move.to has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().distSqr(target) <= 2,
                    "she did not get out: " + walk.outcome());
            for (BlockPos cell : wall) {
                helper.assertTrue(level.getBlockState(cell).is(Blocks.STONE),
                        "a block a player placed was broken at " + cell.toShortString() + ": " + walk.outcome());
            }
            helper.assertTrue(roomCount(helper, 7, 7, Blocks.STONE) < wallBefore, "she got out without breaking any block?");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 别的玩家放下的一堵墙挡在去路上:{@code numen.move.to} 绕过去,不挖它。墙两格高、七格长,正好横在她和终点之间;
     * 创造模式挖一块墙几乎不花时间,路线若把它当地形,一定直接挖穿——绕开靠的是放置记号,不是挖不动。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = BATCH)
    public static void a_walk_goes_round_a_wall_another_player_placed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnBare(helper, "gametest_goer", new BlockPos(8, 2, 3), true);
        PlacedBlocks.Placer stranger = new PlacedBlocks.Placer(java.util.UUID.randomUUID(), "gametest_stranger");
        List<BlockPos> wall = new ArrayList<>();
        for (int x = 5; x <= 11; x++) {
            for (int y = 2; y <= 3; y++) {
                BlockPos cell = helper.absolutePos(new BlockPos(x, y, 8));
                level.setBlockAndUpdate(cell, Blocks.STONE_BRICKS.defaultBlockState());
                PlacedBlocks.of(level).record(cell, stranger);
                wall.add(cell);
            }
        }
        BlockPos target = helper.absolutePos(new BlockPos(8, 2, 13));
        ToolRun walk = lua(companion, "numen.move.to(" + xyz(target) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "numen.move.to has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().distSqr(target) <= 2,
                    "she did not get round the wall: " + walk.outcome());
            for (BlockPos cell : wall) {
                helper.assertTrue(level.getBlockState(cell).is(Blocks.STONE_BRICKS),
                        "a block of the wall a player placed was dug at " + cell.toShortString() + ": " + walk.outcome());
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }
}
