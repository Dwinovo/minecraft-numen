package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * 到达方式,从 {@code numen.move.to} 的入口:{@code arrive:use} 走到看得见、点得到的地方(狭窄矿道里的熔炉、只有一面敞开的箱子、
 * 悬崖上的工作台),隔着高草时 {@code numen.use.block} 先清掉再用;四面封死受理即提醒、不出发;{@code arrive:at} 到柱顶上面那一格、
 * 梯子上、水里的一格;{@code arrive:near} 停在范围里。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class ArriveGameTests {

    private static final String BATCH = "numen_arrive";

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    private static void fill(GameTestHelper helper, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (BlockPos pos : BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1)) {
            helper.getLevel().setBlockAndUpdate(helper.absolutePos(pos), state);
        }
    }

    private static void set(GameTestHelper helper, int x, int y, int z, Block block) {
        helper.getLevel().setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, z)), block.defaultBlockState());
    }

    private static ToolRun gotoUse(NumenPlayer companion, BlockPos target) {
        return lua(companion, "numen.move.to(" + xyz(target) + ", {arrive = \"use\"})");
    }

    /** 她脚下那一格(相对场地,与 {@code absolutePos} 同一个原点)。 */
    private static BlockPos feet(GameTestHelper helper, NumenPlayer companion) {
        return companion.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO));
    }

    /**
     * 狭窄矿道里的熔炉:两条平行的一格宽两格高矿道,一头连通;熔炉嵌在南边那条的北壁上,只有朝矿道的南面敞开。她在北边那条里,
     * 隔着一层石头几何上够得着它、却看不见:arrive:use 绕到南边矿道、站到正对开口的一侧;接着 numen.use.block 打开它。
     */
    @GameTest(template = "floor16", timeoutTicks = 1200, batch = BATCH)
    public static void use_walks_to_the_open_side_of_a_furnace_in_a_narrow_tunnel(GameTestHelper helper) {
        fill(helper, 1, 2, 1, 14, 4, 8, Blocks.STONE.defaultBlockState());
        fill(helper, 3, 2, 3, 12, 3, 3, Blocks.AIR.defaultBlockState());
        fill(helper, 3, 2, 6, 12, 3, 6, Blocks.AIR.defaultBlockState());
        fill(helper, 3, 2, 3, 3, 3, 6, Blocks.AIR.defaultBlockState());
        set(helper, 8, 2, 5, Blocks.FURNACE);
        BlockPos furnace = helper.absolutePos(new BlockPos(8, 2, 5));
        NumenPlayer companion = spawnAt(helper, "gametest_tunnel_cook", new BlockPos(8, 2, 3), false);
        ToolRun walk = gotoUse(companion, furnace);
        ToolRun[] press = {null};

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(walk.succeeded() && feet(helper, companion).getZ() == 6,
                    "she is not in the south tunnel facing the furnace: " + walk.outcome() + " at "
                            + feet(helper, companion));
            if (press[0] == null) {
                press[0] = lua(companion, "numen.use.block(" + xyz(furnace) + ")");
            }
            helper.assertTrue(press[0].done(), "use block has not finished");
            helper.assertTrue(press[0].succeeded() && companion.containerMenu instanceof FurnaceMenu,
                    "the furnace did not open: " + press[0].outcome());
            companion.closeContainer();
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 箱子三面与顶上罩着(顶上是玻璃),只有西面敞开;她在东面:arrive:use 绕到西面。 */
    @GameTest(template = "floor16", timeoutTicks = 1200, batch = BATCH)
    public static void use_walks_to_the_only_open_side_of_a_chest(GameTestHelper helper) {
        set(helper, 8, 2, 8, Blocks.CHEST);
        set(helper, 8, 2, 7, Blocks.STONE);
        set(helper, 8, 2, 9, Blocks.STONE);
        set(helper, 9, 2, 8, Blocks.STONE);
        set(helper, 8, 3, 8, Blocks.GLASS);
        BlockPos chest = helper.absolutePos(new BlockPos(8, 2, 8));
        NumenPlayer companion = spawnAt(helper, "gametest_chest_side", new BlockPos(13, 2, 8), false);
        ToolRun walk = gotoUse(companion, chest);

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(walk.succeeded() && feet(helper, companion).getX() < 8,
                    "she did not come round to the open west side: " + walk.outcome() + " at "
                            + feet(helper, companion));
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 工作台在三格高的悬崖上、离崖边三格;她在崖脚,几何上够得着它,隔着崖壁的石头看不见。旁边一道台阶能上去:arrive:use
     * 不停在崖底,上到崖顶。
     */
    @GameTest(template = "floor16", timeoutTicks = 1200, batch = BATCH)
    public static void use_climbs_to_a_table_on_a_cliff_instead_of_stopping_below(GameTestHelper helper) {
        fill(helper, 7, 2, 0, 15, 4, 15, Blocks.STONE.defaultBlockState());
        set(helper, 4, 2, 12, Blocks.STONE);
        fill(helper, 5, 2, 12, 5, 3, 12, Blocks.STONE.defaultBlockState());
        fill(helper, 6, 2, 12, 6, 4, 12, Blocks.STONE.defaultBlockState());
        set(helper, 10, 5, 6, Blocks.CRAFTING_TABLE);
        BlockPos table = helper.absolutePos(new BlockPos(10, 5, 6));
        NumenPlayer companion = spawnAt(helper, "gametest_cliff_crafter", new BlockPos(6, 2, 6), false);
        ToolRun walk = gotoUse(companion, table);

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(walk.succeeded() && feet(helper, companion).getY() == 5,
                    "she did not go up the cliff: " + walk.outcome() + " at " + feet(helper, companion));
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 箱子只有西面敞开,面前立着一株高草(高草要长在泥土上,不然一次方块更新就掉了)。交出箱子那一格。 */
    private static BlockPos chestBehindTallGrass(GameTestHelper helper) {
        set(helper, 8, 2, 8, Blocks.CHEST);
        set(helper, 8, 2, 7, Blocks.STONE);
        set(helper, 8, 2, 9, Blocks.STONE);
        set(helper, 9, 2, 8, Blocks.STONE);
        set(helper, 8, 3, 8, Blocks.GLASS);
        set(helper, 7, 1, 8, Blocks.GRASS_BLOCK);
        BlockState grass = Blocks.TALL_GRASS.defaultBlockState();
        helper.getLevel().setBlock(helper.absolutePos(new BlockPos(7, 2, 8)),
                grass.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER), 2);
        helper.getLevel().setBlock(helper.absolutePos(new BlockPos(7, 3, 8)),
                grass.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), 2);
        return helper.absolutePos(new BlockPos(8, 2, 8));
    }

    /**
     * 箱子前立着高草:arrive:use 照样走到西面;{@code numen.work.dig} 挖掉高草,再 {@code numen.use.block} 打开箱子。右键是纯按键,
     * 清视线只归挖掘执行。
     */
    @GameTest(template = "floor16", timeoutTicks = 1200, batch = BATCH)
    public static void use_block_opens_a_chest_once_work_dig_clears_the_tall_grass(GameTestHelper helper) {
        BlockPos chest = chestBehindTallGrass(helper);
        BlockPos grass = helper.absolutePos(new BlockPos(7, 2, 8));
        NumenPlayer companion = spawnAt(helper, "gametest_grass_cutter", new BlockPos(2, 2, 8), false);
        ToolRun walk = gotoUse(companion, chest);
        ToolRun[] dig = {null};
        ToolRun[] press = {null};

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(walk.succeeded(), "goto failed: " + walk.outcome());
            if (dig[0] == null) {
                dig[0] = lua(companion, "numen.work.dig(" + xyz(grass) + ")");
            }
            helper.assertTrue(dig[0].done(), "work dig has not finished");
            helper.assertTrue(dig[0].succeeded(), "work dig failed: " + dig[0].outcome());
            helper.assertTrue(helper.getLevel().getBlockState(grass).isAir()
                            && helper.getLevel().getBlockState(grass.above()).isAir(),
                    "the grass is still there after work dig: " + dig[0].outcome());
            if (press[0] == null) {
                press[0] = lua(companion, "numen.use.block(" + xyz(chest) + ")");
            }
            helper.assertTrue(press[0].done(), "use block has not finished");
            helper.assertTrue(press[0].succeeded() && companion.containerMenu instanceof ChestMenu,
                    "the chest was not opened after the grass was dug: " + press[0].outcome());
            companion.closeContainer();
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 同一只箱子、同一株高草,不挖就右键:点到的是高草,箱子没开,高草还在,她一步没动;回执说准星落在高草上,写出
     * {@code numen.work.dig} 挖掉它、或走到看得见另一面的地方这两条路。
     */
    @GameTest(template = "floor16", timeoutTicks = 1200, batch = BATCH)
    public static void use_block_right_clicks_the_tall_grass_in_the_way_and_says_so(GameTestHelper helper) {
        BlockPos chest = chestBehindTallGrass(helper);
        BlockPos grass = helper.absolutePos(new BlockPos(7, 2, 8));
        NumenPlayer companion = spawnAt(helper, "gametest_grass_clicker", new BlockPos(2, 2, 8), false);
        ToolRun walk = gotoUse(companion, chest);
        ToolRun[] press = {null};
        BlockPos[] stood = {null};

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(walk.succeeded(), "goto failed: " + walk.outcome());
            if (press[0] == null) {
                stood[0] = companion.blockPosition();
                press[0] = lua(companion, "numen.use.block(" + xyz(chest) + ")");
            }
            helper.assertTrue(press[0].done(), "use block has not finished");
            String said = press[0].outcome();
            helper.assertTrue(press[0].succeeded() && said.contains("right-clicked tall_grass at")
                            && said.contains("the crosshair landed there, not on " + chest.getX() + "," + chest.getY()
                                    + "," + chest.getZ())
                            && said.contains("`numen.work.dig({") && said.contains("{arrive = \"use\"})`"),
                    "the reply does not say the click landed on the grass and what to do next: " + said);
            helper.assertTrue(!(companion.containerMenu instanceof ChestMenu), "the chest opened through the grass");
            helper.assertTrue(helper.getLevel().getBlockState(grass.above()).is(Blocks.TALL_GRASS),
                    "the grass was cleared by a right click");
            helper.assertTrue(companion.blockPosition().equals(stood[0]), "she moved while clicking");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 熔炉四面与顶上都是石头、底下是地板:arrive:use 受理当场提醒四面封死、说能挖开哪一面,不派活,她一步不动。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void use_on_a_walled_in_block_is_refused_at_once(GameTestHelper helper) {
        set(helper, 8, 2, 8, Blocks.FURNACE);
        set(helper, 7, 2, 8, Blocks.STONE);
        set(helper, 9, 2, 8, Blocks.STONE);
        set(helper, 8, 2, 7, Blocks.STONE);
        set(helper, 8, 2, 9, Blocks.STONE);
        set(helper, 8, 3, 8, Blocks.STONE);
        BlockPos furnace = helper.absolutePos(new BlockPos(8, 2, 8));
        NumenPlayer companion = spawnAt(helper, "gametest_sealed_cook", new BlockPos(2, 2, 8), false);
        BlockPos before = companion.blockPosition();
        ToolRun walk = gotoUse(companion, furnace);

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.task() == null && walk.done() && !walk.succeeded(), "it was not refused at once");
            helper.assertTrue(walk.outcome().contains("walled in on every side")
                            && walk.outcome().contains("{arrive = \"dig\"})`, then `numen.work.dig({"),
                    "the refusal does not say it is sealed and what to dig: " + walk.outcome());
            helper.assertTrue(companion.blockPosition().equals(before), "she moved");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 两格高的石柱,旁边一级台阶:arrive:at 给柱顶上面那一格,站上柱顶。 */
    @GameTest(template = "floor16", timeoutTicks = 1200, batch = BATCH)
    public static void at_the_cell_above_a_pillar_stands_on_top_of_it(GameTestHelper helper) {
        fill(helper, 8, 2, 8, 8, 3, 8, Blocks.STONE.defaultBlockState());
        set(helper, 7, 2, 8, Blocks.STONE);
        BlockPos top = helper.absolutePos(new BlockPos(8, 3, 8));
        NumenPlayer companion = spawnAt(helper, "gametest_pillar_sitter", new BlockPos(2, 2, 8), false);
        ToolRun walk = lua(companion, "numen.move.to(" + xyz(top.above()) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().equals(top.above()),
                    "she is not on top of the pillar: " + walk.outcome() + " at " + feet(helper, companion));
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 贴墙一道梯子:arrive:at 到梯子半截的一格,停在那一格上,挂在梯子上。 */
    @GameTest(template = "floor16", timeoutTicks = 1200, batch = BATCH)
    public static void at_stops_on_a_cell_of_a_ladder(GameTestHelper helper) {
        fill(helper, 9, 2, 6, 12, 6, 10, Blocks.STONE.defaultBlockState());
        for (int y = 2; y <= 6; y++) {
            helper.getLevel().setBlockAndUpdate(helper.absolutePos(new BlockPos(8, y, 8)),
                    Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST));
        }
        BlockPos rung = helper.absolutePos(new BlockPos(8, 4, 8));
        NumenPlayer companion = spawnAt(helper, "gametest_ladder_hanger", new BlockPos(2, 2, 8), false);
        ToolRun walk = lua(companion, "numen.move.to(" + xyz(rung) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().equals(rung) && companion.onClimbable(),
                    "she is not hanging on that rung: " + walk.outcome() + " at " + feet(helper, companion));
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 场地垫高两层,中间一个两格深的水池:arrive:at 到水面那一层的一格,她在水里。 */
    @GameTest(template = "floor16", timeoutTicks = 1200, batch = BATCH)
    public static void at_stops_on_a_cell_in_water(GameTestHelper helper) {
        fill(helper, 0, 2, 0, 15, 3, 15, Blocks.STONE.defaultBlockState());
        fill(helper, 7, 2, 7, 10, 3, 10, Blocks.WATER.defaultBlockState());
        BlockPos cell = helper.absolutePos(new BlockPos(8, 3, 8));
        NumenPlayer companion = spawnAt(helper, "gametest_floater", new BlockPos(2, 4, 8), false);
        ToolRun walk = lua(companion, "numen.move.to(" + xyz(cell) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(walk.succeeded() && companion.isInWater(),
                    "she is not in the water: " + walk.outcome() + " at " + feet(helper, companion));
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** arrive:near 带 near:3:停在离那一格三格以内,不走到跟前。 */
    @GameTest(template = "floor16", timeoutTicks = 1200, batch = BATCH)
    public static void near_stops_within_the_distance(GameTestHelper helper) {
        BlockPos spot = helper.absolutePos(new BlockPos(13, 2, 13));
        NumenPlayer companion = spawnAt(helper, "gametest_nearby", new BlockPos(2, 2, 2), false);
        ToolRun walk = lua(companion, "numen.move.to(" + xyz(spot) + ", {arrive = \"near\", range = " + 3 + "})");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            double d = Math.sqrt(companion.blockPosition().distSqr(spot));
            helper.assertTrue(walk.succeeded() && d <= 3 && d > 2,
                    "she did not stop just within three blocks: " + walk.outcome() + " at distance " + d);
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * arrive "reach":一格半空(地面上两格、四周什么都没有),她走到手够得着往里放方块的地方停下,不站进那一格;接着
     * {@code numen.build.place} 原地放进去——够得着就是放得下。
     */
    @GameTest(template = "floor16", timeoutTicks = 1200, batch = BATCH)
    public static void reach_walks_within_hand_of_a_cell_in_the_air_and_it_takes_a_block(GameTestHelper helper) {
        BlockPos cell = helper.absolutePos(new BlockPos(11, 4, 11));
        NumenPlayer companion = spawnAt(helper, "gametest_reacher", new BlockPos(2, 2, 2), false);
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE));
        ToolRun run = lua(companion, "numen.move.to(" + xyz(cell) + ", {arrive = \"place\"})\n"
                + "numen.build.place({{name = \"cobblestone\", pos = " + xyz(cell) + "}})");

        succeedWhen(helper, () -> {
            helper.assertTrue(run.receipt() != null, "the program has not finished");
            helper.assertTrue(run.ranToTheEnd(), "walking within reach and placing failed: " + run.receipt());
            helper.assertTrue(helper.getLevel().getBlockState(cell).is(Blocks.COBBLESTONE),
                    "the cell in the air was not filled");
            helper.assertTrue(!companion.blockPosition().equals(cell) && !companion.blockPosition().above().equals(cell),
                    "she stood in the cell she was to fill");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** arrive "reach" 说的是一格:只给 {x, z} 当场拒,说要给 y,不出发。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void reach_without_a_height_is_refused_at_once(GameTestHelper helper) {
        BlockPos cell = helper.absolutePos(new BlockPos(11, 4, 11));
        NumenPlayer companion = spawnAt(helper, "gametest_reach_flat", new BlockPos(2, 2, 2), false);
        BlockPos start = companion.blockPosition();
        ToolRun run = lua(companion, "numen.move.to({x = " + cell.getX() + ", z = " + cell.getZ() + "}, {arrive = \"place\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(run.receipt() != null, "the program has not finished");
            helper.assertTrue(!run.ranToTheEnd() && run.receipt().contains("arrive = \\\"place\\\" names one block"),
                    "the refusal does not say reach needs a y: " + run.receipt());
            helper.assertTrue(run.tasks("numen.move.go").isEmpty() && companion.blockPosition().equals(start),
                    "she set off anyway");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }
}
