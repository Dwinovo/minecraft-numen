package com.dwinovo.numen.core.gametest;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

import java.util.List;

import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.area.AreaStore;
import com.dwinovo.numen.area.Cells;
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
 * 路线收区域:{@code numen.move.goto_ --area} 走进区域里任意一格、用区域里的箱子之一、停在区域附近;{@code --avoid area:…} 绕开一块地;
 * {@code --avoid_break area:…} 不挖那几格、改从别处挖出去;路线存的是区域的名字,区域删了 {@code numen.route.plan} 与 {@code numen.move.go}
 * 如实说是哪一段、哪块区域不在了。区域由主人的存档直接建(命令组另有测试),其余都从工具入口进。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class AreaRouteGameTests {

    private static final String BATCH = "numen_area_route";

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    private static AreaStore areasOf(NumenPlayer companion) {
        return AreaStore.of(companion.getServer(), companion.getOwnerUuid());
    }

    /** 两个对角(相对坐标)框出的盒子,那一部分是 {@code b1}。 */
    private static Area box(GameTestHelper helper, BlockPos from, BlockPos to) {
        return Area.of(helper.getLevel().dimension(), Area.Kind.BOX,
                Cells.box(helper.absolutePos(from), helper.absolutePos(to)));
    }

    /** 走进一块区域:场地一角的一片空地(脚所在的那一层),她从另一角出发,停在区域里的某一格,回执点名区域。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void goto_an_area_walks_into_any_of_its_cells(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_penwalker", new BlockPos(2, 2, 2), false);
        Area pen = box(helper, new BlockPos(10, 2, 10), new BlockPos(12, 2, 12));
        areasOf(companion).create("pen", pen);
        ToolRun walk = lua(companion, "numen.move.goto_(\"pen\")");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "move_goto has not finished");
            helper.assertTrue(walk.succeeded() && walk.outcome().contains("reached area pen"),
                    "she did not walk into the area: " + walk.outcome());
            helper.assertTrue(pen.contains(level.dimension(), companion.blockPosition()),
                    "she stopped outside the area at " + companion.blockPosition().toShortString());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 用区域里的箱子之一:区域两部分,各是一口箱子,一口在她跟前、一口在场地对角。她去用近的那一口:停在看得见、够得着它的地方,
     * 回执点名是哪一口。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void goto_use_an_area_uses_one_of_its_chests(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos near = helper.absolutePos(new BlockPos(7, 2, 2));
        BlockPos far = helper.absolutePos(new BlockPos(13, 2, 13));
        level.setBlockAndUpdate(near, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(far, Blocks.CHEST.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_chestuser", new BlockPos(2, 2, 2), false);
        areasOf(companion).create("chests", Area.of(level.dimension(), Area.Kind.POINT, Cells.point(near))
                .with(Area.Kind.POINT, Cells.point(far)));
        ToolRun walk = lua(companion, "numen.move.goto_(\"chests\", {arrive = \"use\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "move goto has not finished");
            helper.assertTrue(walk.succeeded() && walk.outcome().contains("chest at " + near.getX() + ","
                            + near.getY() + "," + near.getZ()) && walk.outcome().contains("of area chests"),
                    "she did not go to use the near chest: " + walk.outcome());
            helper.assertTrue(companion.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(near))
                    <= companion.blockInteractionRange() + 1, "the chest is out of her reach");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 停在区域附近:离区域里某一格不超过 3 格就算到,回执照说。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void goto_near_an_area_stops_within_reach_of_it(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_hoverer", new BlockPos(2, 2, 2), false);
        Area pen = box(helper, new BlockPos(10, 2, 10), new BlockPos(13, 2, 13));
        areasOf(companion).create("pen", pen);
        ToolRun walk = lua(companion, "numen.move.goto_(\"pen\", {arrive = \"near\", near = 3})");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "move goto has not finished");
            helper.assertTrue(walk.succeeded() && walk.outcome().contains("arrived within 3 blocks of area pen"),
                    "she did not stop near the area: " + walk.outcome());
            BlockPos at = companion.blockPosition();
            double nearest = Math.sqrt(pen.cells().nearest(at).distSqr(at));
            helper.assertTrue(nearest <= 3, "she stopped " + nearest + " blocks from the area");
            helper.assertTrue(!pen.contains(level.dimension(), at), "near should stop at the edge, not walk in");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 绕开一块地:农田横在她与终点之间,只在场地一边留出一条路。{@code --avoid area:farm} 的一趟一路没进农田(身子不在里面、脚下
     * 不踩它),照样走到。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void avoiding_an_area_walks_around_it(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_skirter", new BlockPos(2, 2, 6), false);
        Area farm = box(helper, new BlockPos(5, 1, 0), new BlockPos(10, 3, 11));
        areasOf(companion).create("farm", farm);
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 6));
        ToolRun walk = lua(companion, "numen.move.goto_(" + xyz(target) + ", {avoid = \"area:farm\"})");
        boolean[] entered = new boolean[1];
        helper.onEachTick(() -> {
            BlockPos feet = companion.blockPosition();
            entered[0] |= farm.contains(level.dimension(), feet) || farm.contains(level.dimension(), feet.above())
                    || companion.onGround() && farm.contains(level.dimension(), feet.below());
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "move goto has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().equals(target),
                    "she did not arrive: " + walk.outcome());
            helper.assertTrue(!entered[0], "she walked into the farm");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 不挖房子的那一面:关在木板屋里,终点在屋东边。东墙是区域 {@code house};{@code alter = "natural" --avoid_break area:house}
     * 的一趟挖开别的墙绕出去,东墙一块不少,回执照实记下挖了哪几块木板。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void avoid_break_an_area_digs_out_elsewhere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        plankRoomAround(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_sparer", new BlockPos(7, 2, 7), false);
        Area eastWall = box(helper, new BlockPos(9, 2, 5), new BlockPos(9, 4, 9));
        areasOf(companion).create("house", eastWall);
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 7));
        ToolRun walk = lua(companion, "numen.move.goto_(" + xyz(target) + ", {alter = \"natural\", avoid_break = \"area:house\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "move_goto has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().distSqr(target) <= 2,
                    "she did not get out: " + walk.outcome());
            eastWall.cells().forEach((x, y, z, seen) -> helper.assertTrue(
                    level.getBlockState(new BlockPos(x, y, z)).is(Blocks.OAK_PLANKS),
                    "a plank of the house was broken at " + x + "," + y + "," + z));
            helper.assertTrue(plankCount(helper, 7, 7) < planksBefore, "she got out without breaking any plank?");
            helper.assertTrue(walk.outcome().contains("oak_planks"), "the reply does not say what was broken: "
                    + walk.outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 路上不挖她盖的房子:关在木板屋里,终点在屋东边;东墙是她照设计盖的一栋({@link Built} 记着这几格,放置记号也是她自己)。
     * {@code alter = "natural"} 的一趟不点名任何禁区,也挖开别的墙绕出去,东墙一块不少——日式小屋卡在底层来回挖自己刚放下的格,
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
        ToolRun walk = lua(companion, "numen.move.goto_(" + xyz(target) + ", {alter = \"natural\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "numen.move.goto_ has not finished");
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

    /**
     * 路线存的是区域的名字:终点是区域 {@code pen}、标志避开区域 {@code farm} 的两条路线建好之后,把两块区域删掉。{@code numen.route.plan}
     * 说第一段去的区域不在了;两条的 {@code numen.move.go} 都不出发,说是哪块区域不在了、主人还有哪些;{@code numen.move.goto_ --area pen}
     * 受理当场就说。她一步没动。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void a_route_to_a_deleted_area_says_so(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_forgetful", new BlockPos(2, 2, 2), false);
        BlockPos start = companion.blockPosition();
        AreaStore areas = areasOf(companion);
        areas.create("pen", box(helper, new BlockPos(10, 2, 10), new BlockPos(12, 2, 12)));
        areas.create("farm", box(helper, new BlockPos(5, 1, 5), new BlockPos(7, 3, 7)));
        areas.create("shed", box(helper, new BlockPos(0, 2, 14), new BlockPos(1, 2, 15)));
        ToolRun toPen = lua(companion, "numen.route.new(\"topen\", {to = \"pen\"})");
        ToolRun around = lua(companion, "numen.route.new(\"around\", {to = " + at(helper, new BlockPos(12, 2, 2)) + ", avoid = \"area:farm\"})");
        helper.assertTrue(toPen.succeeded() && around.succeeded(), "making the routes failed: " + toPen.reply()
                + " / " + around.reply());
        areas.delete("pen");
        areas.delete("farm");
        ToolRun plan = lua(companion, "numen.route.plan(\"topen\")");
        ToolRun direct = lua(companion, "numen.move.goto_(\"pen\")");
        ToolRun[] goPen = new ToolRun[1];
        ToolRun[] goAround = new ToolRun[1];

        steps(helper)
                .thenExecute(() -> {
                    helper.assertTrue(plan.done() && !plan.succeeded()
                                    && plan.reply().contains("leg 1 to area pen: can't be walked: there is no area named "
                                    + "pen; your owner's areas are shed"),
                            "route plan does not say the area is gone: " + plan.reply());
                    helper.assertTrue(!direct.succeeded() && direct.reply().contains("there is no area named pen"),
                            "move goto pen was not refused at once: " + direct.reply());
                    goPen[0] = lua(companion, "numen.move.go(\"topen\")");
                })
                .thenWaitUntil(() -> helper.assertTrue(goPen[0].done(), "move go topen has not finished"))
                // 区域没了之后 around 还没规划过:规划它,说是哪个标志点了不在的区域
                .thenExecute(() -> goAround[0] = lua(companion, "numen.route.plan(\"around\")"))
                .thenWaitUntil(() -> helper.assertTrue(goAround[0].done(), "route plan around has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(!goPen[0].succeeded() && goPen[0].outcome().contains(
                                    "can't walk route topen as it stands: there is no area named pen"),
                            "move go does not say the area is gone: " + goPen[0].outcome());
                    helper.assertTrue(!goAround[0].succeeded()
                                    && goAround[0].outcome().contains("avoid area:farm: there is no area named farm"),
                            "route plan does not say which flag names a missing area: " + goAround[0].outcome());
                    helper.assertTrue(companion.blockPosition().equals(start), "she moved");
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }
}
