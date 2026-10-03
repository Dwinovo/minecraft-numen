package com.dwinovo.numen.core.gametest;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

import java.util.List;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.build.Built;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.PlacedBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 路线的描述收一片格子:终点是带 {@code cells} 的表时,走进其中任意一格、用其中的箱子之一、停在离它们不远处;{@code avoid}
 * 收一个盒子(两个对角)就绕开那块地;{@code avoid_break} 收一个盒子就不挖那几格、改从别处挖出去;她自己盖的房子不用点名也不挖。
 * 全部从脚本入口进。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class AreaRouteGameTests {

    private static final String BATCH = "numen_area_route";
    private static final String CHANGES = "costs = {dig = true, place = true, consent = false}";

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 两个对角(相对坐标)之间的每一格,绝对坐标。 */
    private static List<BlockPos> box(GameTestHelper helper, BlockPos from, BlockPos to) {
        return BlockPos.betweenClosedStream(helper.absolutePos(from), helper.absolutePos(to)).map(BlockPos::immutable)
                .toList();
    }

    /** 一片格子写成脚本里带 {@code cells} 的表(扫描给的一团就是这个样子)。 */
    private static String cells(List<BlockPos> cells) {
        StringBuilder sb = new StringBuilder("{cells = {");
        for (int i = 0; i < cells.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(xyz(cells.get(i)));
        }
        return sb.append("}}").toString();
    }

    /** 一个盒子写成脚本里的两个对角。 */
    private static String corners(GameTestHelper helper, BlockPos from, BlockPos to) {
        return "{" + at(helper, from) + ", " + at(helper, to) + "}";
    }

    /** 走进一片格子:场地一角的一片空地(脚所在的那一层),她从另一角出发,停在其中某一格。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void goto_cells_walks_into_any_of_them(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_penwalker", new BlockPos(2, 2, 2), false);
        List<BlockPos> pen = box(helper, new BlockPos(10, 2, 10), new BlockPos(12, 2, 12));
        ToolRun walk = lua(companion, "move.to(" + cells(pen) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "move.to has not finished");
            helper.assertTrue(walk.succeeded() && walk.outcome().contains("reached"),
                    "she did not walk into the cells: " + walk.outcome());
            helper.assertTrue(pen.contains(companion.blockPosition()),
                    "she stopped outside the cells at " + companion.blockPosition().toShortString());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 用其中的箱子之一:两格各是一口箱子,一口在她跟前、一口在场地对角。她去用近的那一口:停在看得见、够得着它的地方,
     * 回执点名是哪一口。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void goto_use_cells_uses_one_of_its_chests(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos near = helper.absolutePos(new BlockPos(7, 2, 2));
        BlockPos far = helper.absolutePos(new BlockPos(13, 2, 13));
        level.setBlockAndUpdate(near, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(far, Blocks.CHEST.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_chestuser", new BlockPos(2, 2, 2), false);
        ToolRun walk = lua(companion, "move.to(" + cells(List.of(near, far)) + ", {arrive = \"use\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "move.to has not finished");
            helper.assertTrue(walk.succeeded() && walk.outcome().contains("chest at " + near.getX() + ","
                            + near.getY() + "," + near.getZ()),
                    "she did not go to use the near chest: " + walk.outcome());
            helper.assertTrue(companion.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(near))
                    <= companion.blockInteractionRange() + 1, "the chest is out of her reach");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 停在一片格子附近:离其中某一格不超过 3 格就算到,回执照说。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void goto_near_cells_stops_within_range_of_them(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_hoverer", new BlockPos(2, 2, 2), false);
        List<BlockPos> pen = box(helper, new BlockPos(10, 2, 10), new BlockPos(13, 2, 13));
        ToolRun walk = lua(companion, "move.to(" + cells(pen) + ", {arrive = \"near\", range = 3})");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "move.to has not finished");
            helper.assertTrue(walk.succeeded() && walk.outcome().contains("arrived within 3 blocks of 16 cells"),
                    "she did not stop near the cells: " + walk.outcome());
            BlockPos at = companion.blockPosition();
            double nearest = pen.stream().mapToDouble(c -> Math.sqrt(c.distSqr(at))).min().orElseThrow();
            helper.assertTrue(nearest <= 3, "she stopped " + nearest + " blocks from the cells");
            helper.assertTrue(!pen.contains(at), "near should stop at the edge, not walk in");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 绕开一块地:农田横在她与终点之间,只在场地一边留出一条路。{@code avoid} 收那块地的两个对角,这一趟一路没进农田
     * (身子不在里面、脚下不踩它),照样走到。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void avoiding_a_box_walks_around_it(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_skirter", new BlockPos(2, 2, 6), false);
        BlockPos lo = new BlockPos(5, 1, 0);
        BlockPos hi = new BlockPos(10, 3, 11);
        List<BlockPos> farm = box(helper, lo, hi);
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 6));
        ToolRun walk = lua(companion, "move.to(" + xyz(target) + ", {avoid = {" + corners(helper, lo, hi) + "}})");
        boolean[] entered = new boolean[1];
        helper.onEachTick(() -> {
            BlockPos feet = companion.blockPosition();
            entered[0] |= farm.contains(feet) || farm.contains(feet.above())
                    || companion.onGround() && farm.contains(feet.below());
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "move.to has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().equals(target),
                    "she did not arrive: " + walk.outcome());
            helper.assertTrue(!entered[0], "she walked into the farm");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 不挖房子的那一面:关在木板屋里,终点在屋东边。许挖许放、{@code avoid_break} 收东墙的两个对角,这一趟挖开别的墙绕出去,
     * 东墙一块不少,回执照实记下挖了哪几块木板。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void avoid_break_a_box_digs_out_elsewhere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        plankRoomAround(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_sparer", new BlockPos(7, 2, 7), false);
        BlockPos lo = new BlockPos(9, 2, 5);
        BlockPos hi = new BlockPos(9, 4, 9);
        List<BlockPos> eastWall = box(helper, lo, hi);
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 7));
        ToolRun walk = lua(companion, "move.to(" + xyz(target) + ", {" + CHANGES + ", avoid_break = {"
                + corners(helper, lo, hi) + "}})");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "move.to has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().distSqr(target) <= 2,
                    "she did not get out: " + walk.outcome());
            eastWall.forEach(cell -> helper.assertTrue(level.getBlockState(cell).is(Blocks.OAK_PLANKS),
                    "a plank of the house was broken at " + cell.toShortString()));
            helper.assertTrue(plankCount(helper, 7, 7) < planksBefore, "she got out without breaking any plank?");
            helper.assertTrue(walk.outcome().contains("oak_planks"), "the reply does not say what was broken: "
                    + walk.outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 路上不挖她盖的房子:关在木板屋里,终点在屋东边;东墙是她照设计盖的一栋({@link Built} 记着这几格,放置记号也是她自己)。
     * 许挖许放的一趟不点名任何禁区,也挖开别的墙绕出去,东墙一块不少——日式小屋卡在底层来回挖自己刚放下的格,
     * 就是路线把房子的格当成了能挖的地形。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void a_walk_never_digs_a_building_she_built(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        plankRoomAround(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_keeper", new BlockPos(7, 2, 7), false);
        String her = companion.getGameProfile().getName();
        Built.Site site = new Built.Site("gt_east_wall", level.dimension().location(),
                helper.absolutePos(new BlockPos(9, 2, 5)), 0);
        List<BlockPos> wall = new java.util.ArrayList<>();
        for (int y = 2; y <= 4; y++) {
            for (int z = 5; z <= 9; z++) {
                BlockPos cell = helper.absolutePos(new BlockPos(9, y, z));
                wall.add(cell);
                Built.of(level.getServer()).placed(site, her, level.getGameTime(), cell, Blocks.OAK_PLANKS);
                PlacedBlocks.of(level).record(cell, new PlacedBlocks.Placer(companion.getUUID(), her));
            }
        }
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 7));
        ToolRun walk = lua(companion, "move.to(" + xyz(target) + ", {" + CHANGES + "})");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "move.to has not finished");
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
