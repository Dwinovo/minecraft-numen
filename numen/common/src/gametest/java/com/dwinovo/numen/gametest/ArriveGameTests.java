package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * 到达,从入口调:{@code numen.use.block} 够不着时自己走到看得见、点得到的地方(狭窄矿道里的熔炉、只有一面敞开的箱子、
 * 悬崖上的工作台),隔着高草时 {@code numen.work.dig} 先清掉再用;四面封死走不到、如实说挖哪一面;{@code numen.move.to} 到柱顶上面那一格、
 * 梯子上、水里的一格,或停在距离里;{@code numen.build.place} 走到够得着往一格半空里放的地方。
 */
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

    /** 右键那一格:够不着,她先走到能点的地方。 */
    private static ToolRun use(NumenPlayer companion, BlockPos target) {
        return lua(companion, "numen.use.block(" + xyz(target) + ")");
    }

    /** 她脚下那一格(相对场地,与 {@code absolutePos} 同一个原点)。 */
    private static BlockPos feet(GameTestHelper helper, NumenPlayer companion) {
        return companion.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO));
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
     * 箱子前立着高草:{@code numen.work.dig} 走过去挖掉高草,再 {@code numen.use.block} 打开箱子。右键是纯按键,
     * 清视线只归挖掘执行。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void use_block_opens_a_chest_once_work_dig_clears_the_tall_grass(GameTestHelper helper) {
        BlockPos chest = chestBehindTallGrass(helper);
        BlockPos grass = helper.absolutePos(new BlockPos(7, 2, 8));
        NumenPlayer companion = spawnAt(helper, "gametest_grass_cutter", new BlockPos(2, 2, 8), false);
        ToolRun[] dig = {lua(companion, "numen.work.dig(" + xyz(grass) + ")")};
        ToolRun[] press = {null};

        succeedWhen(helper, () -> {
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
     * 同一只箱子、同一株高草,不挖就右键:她走过去,点到的是高草,箱子没开,高草还在;回执说准星落在高草上,写出
     * {@code numen.work.dig} 挖掉它、或从另一面点这两条路。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void use_block_right_clicks_the_tall_grass_in_the_way_and_says_so(GameTestHelper helper) {
        BlockPos chest = chestBehindTallGrass(helper);
        BlockPos grass = helper.absolutePos(new BlockPos(7, 2, 8));
        NumenPlayer companion = spawnAt(helper, "gametest_grass_clicker", new BlockPos(2, 2, 8), false);
        ToolRun press0 = use(companion, chest);
        ToolRun[] press = {press0};

        succeedWhen(helper, () -> {
            helper.assertTrue(press[0].done(), "use block has not finished");
            String said = press[0].outcome();
            helper.assertTrue(press[0].succeeded() && said.contains("right-clicked tall_grass at")
                            && said.contains("the crosshair landed there, not on " + chest.getX() + "," + chest.getY()
                                    + "," + chest.getZ())
                            && said.contains("`numen.work.dig({") && said.contains("click it from another side"),
                    "the reply does not say the click landed on the grass and what to do next: " + said);
            helper.assertTrue(!(companion.containerMenu instanceof ChestMenu), "the chest opened through the grass");
            helper.assertTrue(helper.getLevel().getBlockState(grass.above()).is(Blocks.TALL_GRASS),
                    "the grass was cleared by a right click");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 熔炉四面与顶上都是石头、底下是地板:use.block 走不到,如实说四面封死、能挖开哪一面,什么都没点,她一步不动。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void use_on_a_walled_in_block_says_what_to_dig(GameTestHelper helper) {
        set(helper, 8, 2, 8, Blocks.FURNACE);
        set(helper, 7, 2, 8, Blocks.STONE);
        set(helper, 9, 2, 8, Blocks.STONE);
        set(helper, 8, 2, 7, Blocks.STONE);
        set(helper, 8, 2, 9, Blocks.STONE);
        set(helper, 8, 3, 8, Blocks.STONE);
        BlockPos furnace = helper.absolutePos(new BlockPos(8, 2, 8));
        NumenPlayer companion = spawnAt(helper, "gametest_sealed_cook", new BlockPos(2, 2, 8), false);
        BlockPos before = companion.blockPosition();
        ToolRun press = use(companion, furnace);

        succeedWhen(helper, () -> {
            helper.assertTrue(press.done() && !press.succeeded(), "it was not refused");
            helper.assertTrue(press.outcome().contains("walled in on every side")
                            && press.outcome().contains("`numen.work.dig({"),
                    "the refusal does not say it is sealed and what to dig: " + press.outcome());
            helper.assertTrue(companion.blockPosition().equals(before), "she moved");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 两格高的石柱,旁边一级台阶:move.to 给柱顶上面那一格,站上柱顶。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
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

    /** move.to 带距离 3:停在离那一格三格以内,不走到跟前。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void near_stops_within_the_distance(GameTestHelper helper) {
        BlockPos spot = helper.absolutePos(new BlockPos(13, 2, 13));
        NumenPlayer companion = spawnAt(helper, "gametest_nearby", new BlockPos(2, 2, 2), false);
        ToolRun walk = lua(companion, "numen.move.to(" + xyz(spot) + ", 3)");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            double d = Math.sqrt(companion.blockPosition().distSqr(spot));
            helper.assertTrue(walk.succeeded() && d <= 3 && d > 2,
                    "she did not stop just within three blocks: " + walk.outcome() + " at distance " + d);
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 一格半空(地面上两格、四周什么都没有):{@code numen.build.place} 自己走到手够得着往里放方块的地方,不站进那一格,放进去——
     * 够得着就是放得下。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void place_walks_within_hand_of_a_cell_in_the_air_and_fills_it(GameTestHelper helper) {
        BlockPos cell = helper.absolutePos(new BlockPos(11, 4, 11));
        NumenPlayer companion = spawnAt(helper, "gametest_reacher", new BlockPos(2, 2, 2), false);
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE));
        ToolRun run = lua(companion, "numen.build.place({{name = \"cobblestone\", pos = " + xyz(cell) + "}})");

        succeedWhen(helper, () -> {
            helper.assertTrue(run.receipt() != null, "the program has not finished");
            helper.assertTrue(run.ranToTheEnd(), "placing failed: " + run.receipt());
            helper.assertTrue(helper.getLevel().getBlockState(cell).is(Blocks.COBBLESTONE),
                    "the cell in the air was not filled");
            helper.assertTrue(!companion.blockPosition().equals(cell) && !companion.blockPosition().above().equals(cell),
                    "she stood in the cell she was to fill");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }
}
