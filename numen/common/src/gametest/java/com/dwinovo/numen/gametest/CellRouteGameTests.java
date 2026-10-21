package com.dwinovo.numen.gametest;

import static com.dwinovo.numen.gametest.GameTestKit.*;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.build.Built;
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
 * 去一串格:{@code numen.move.to} 给一串格(Cells)走进其中任意一格,给一串方块走到其中之一旁边,给距离停在离它们不远处;她自己盖的房子不用
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
     * 路上不挖她盖的房子:关在木板屋里,终点在屋东边;东墙是她照蓝图盖的一栋({@link Built} 记着这几格,放置记号也是她自己)。
     * 不点名任何禁区,她也挖开别的墙绕出去,东墙一块不少——日式小屋卡在底层来回挖自己刚放下的格,
     * 就是路线把房子的格当成了能挖的地形。
     */
    @GameTest(template = "floor16", timeoutTicks = 528, batch = BATCH)
    public static void a_walk_never_digs_a_building_she_built(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        plankRoomAround(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_keeper", new BlockPos(7, 2, 7), false);
        String her = companion.getGameProfile().getName();
        Built.Site site = new Built.Site("gt_east_wall", level.dimension().location(),
                helper.absolutePos(new BlockPos(9, 2, 5)), 0);
        List<BlockPos> wall = new ArrayList<>();
        for (int y = 2; y <= 4; y++) {
            for (int z = 5; z <= 9; z++) {
                BlockPos cell = helper.absolutePos(new BlockPos(9, y, z));
                wall.add(cell);
                Built.of(level.getServer()).placed(site, her, level.getGameTime(), cell, Blocks.OAK_PLANKS);
                PlacedBlocks.of(level).record(cell, new PlacedBlocks.Placer(companion.getUUID(), her));
            }
        }
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 7));
        ToolRun walk = lua(companion, "numen.move.to(" + xyz(target) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "numen.move.to has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().distSqr(target) <= 2,
                    "she did not get out: " + walk.outcome());
            for (BlockPos cell : wall) {
                helper.assertTrue(level.getBlockState(cell).is(Blocks.OAK_PLANKS),
                        "a plank of her building was broken at " + cell.toShortString() + ": " + walk.outcome());
            }
            helper.assertTrue(plankCount(helper, 7, 7) < planksBefore, "she got out without breaking any plank?");
            wall.forEach(cell -> Built.of(level.getServer()).cleared(site, level.getGameTime(), cell));
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }
}
