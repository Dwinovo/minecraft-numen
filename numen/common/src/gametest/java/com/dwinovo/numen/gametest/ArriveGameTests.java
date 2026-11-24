package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * 到达,从入口调:{@code numen.use.block} 够不着时自己走到看得见、点得到的地方(狭窄矿道里的熔炉、只有一面敞开的箱子、
 * 悬崖上的工作台),隔着高草时 {@code numen.work.dig} 先清掉再用;四面封死走不到、如实说挖哪一面;{@code numen.move.walk} 到
 * 梯子上、水里的一格。
 */
public class ArriveGameTests {

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
     * 箱子前立着高草:{@code numen.work.dig} 走过去挖掉高草,再 {@code numen.use.block} 点到的就是箱子,箱子接了这一下。右键是
     * 纯按键,清视线只归挖掘执行。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = ON_FOOT)
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
            helper.assertTrue(press[0].succeeded() && press[0].outcome().contains("right-clicked " + chest.getX() + ","
                            + chest.getY() + "," + chest.getZ() + " — accepted"),
                    "the chest did not take the click after the grass was dug: " + press[0].outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 同一只箱子、同一株高草,不挖就右键:她走过去,点到的是高草,这一下没人接,高草还在;回执说准星落在高草上,写出
     * {@code numen.work.dig} 挖掉它、或从另一面点这两条路。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = ON_FOOT)
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
            helper.assertTrue(said.contains("not accepted"), "the chest took the click through the grass: " + said);
            helper.assertTrue(helper.getLevel().getBlockState(grass.above()).is(Blocks.TALL_GRASS),
                    "the grass was cleared by a right click");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

}
