package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.Checks;
import static com.dwinovo.numen.gametest.GameTestKit.*;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import com.dwinovo.numen.api.gametest.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * 去一串格:{@code numen.move.walk} 给一串方块走到其中之一旁边,给距离停在离它们不远处。格子由程序写出来,和她拿扫描结果
 * 原样交过去一样;都从脚本入口进。
 */
public class CellRouteGameTests {

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

    /**
     * 几个方块走到其中之一旁边:两格各是一口箱子(写成带 name 的 Block),一口在她跟前、一口在场地对角。没说距离,她走到近的那一口旁边,
     * 不走进去。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = ON_FOOT)
    public static void goto_several_blocks_goes_beside_the_nearest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos near = helper.absolutePos(new BlockPos(7, 2, 2));
        BlockPos far = helper.absolutePos(new BlockPos(13, 2, 13));
        level.setBlockAndUpdate(near, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(far, Blocks.CHEST.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_chestuser", new BlockPos(2, 2, 2), false);
        ToolRun walk = lua(companion, "numen.move.walk({" + block(near) + ", " + block(far) + "})");

        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, walk.done(), "numen.move.walk has not finished");
            Checks.assertTrue(helper, walk.succeeded() && walk.outcome().contains("within 2 blocks"),
                    "she did not go beside the near chest: " + walk.outcome());
            Checks.assertTrue(helper, Math.sqrt(companion.blockPosition().distSqr(near)) <= 2
                    && !companion.blockPosition().equals(near), "she is not beside the near chest");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 停在几格附近:离其中某一格不超过 3 格就算到,回执照说;不走进去。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = ON_FOOT)
    public static void goto_near_several_cells_stops_within_range_of_them(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_hoverer", new BlockPos(2, 2, 2), false);
        List<BlockPos> pen = box(helper, new BlockPos(10, 2, 10), new BlockPos(13, 2, 13));
        ToolRun walk = lua(companion, "numen.move.walk(" + cells(pen) + ", 3)");

        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, walk.done(), "numen.move.walk has not finished");
            Checks.assertTrue(helper, walk.succeeded() && walk.outcome().contains("arrived within 3 blocks of 16 cells"),
                    "she did not stop near the cells: " + walk.outcome());
            BlockPos at = companion.blockPosition();
            double nearest = nearest(pen, at);
            Checks.assertTrue(helper, nearest <= 3, "she stopped " + nearest + " blocks from the cells");
            Checks.assertTrue(helper, !pen.contains(at), "near should stop at the edge, not walk in");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }
}
