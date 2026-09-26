package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * 找地方:{@code locate biome}、{@code locate structure},从 {@code command} 入口调。测试服的世界是超平坦,只有平原、
 * 不生成结构——所以测的是"脚下的群系找得到"和"没有的东西如实说没有、说清楚找了多远"。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class LocateGameTests {

    /** 找地方批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_locate")
    public static void prepareLocateBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 找平原:她就站在平原上,报出来的最近一处带坐标和方向。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_locate")
    public static void locate_biome_finds_the_plains_underfoot(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_cartographer", new BlockPos(3, 2, 3), false);
        ToolRun locate = command(companion, "locate biome minecraft:plains");

        helper.succeedWhen(() -> {
            helper.assertTrue(locate.done(), "locate biome has not finished");
            helper.assertTrue(locate.succeeded() && locate.outcome().startsWith("nearest ")
                            && locate.outcome().contains("plains"),
                    "the plains underfoot were not found: " + locate.outcome());
            helper.assertTrue(locate.task().getToolName().equals("locate biome"),
                    "the search is not named after the command: " + locate.task().getToolName());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 找沙漠:这个世界里没有,回执说没找到、找了多远,不编一个坐标出来。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_locate")
    public static void locate_biome_that_is_not_here_says_how_far_it_looked(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_wanderer", new BlockPos(3, 2, 3), false);
        ToolRun locate = command(companion, "locate biome minecraft:desert");

        helper.succeedWhen(() -> {
            helper.assertTrue(locate.done(), "locate biome has not finished");
            helper.assertTrue(locate.outcome().startsWith("no ") && locate.outcome().contains("desert")
                            && locate.outcome().contains("within"),
                    "the reply does not say the desert was not found within the search: " + locate.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 把结构当群系找:当场失败,并指她去用 locate structure。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_locate")
    public static void locate_biome_given_a_structure_points_to_locate_structure(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mixedup", new BlockPos(3, 2, 3), false);
        ToolRun locate = command(companion, "locate biome minecraft:village_plains");

        helper.succeedWhen(() -> {
            helper.assertTrue(locate.done(), "locate biome has not finished");
            helper.assertTrue(!locate.succeeded()
                            && locate.outcome().contains("locate structure minecraft:village_plains"),
                    "the failure does not point at locate structure: " + locate.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 找村庄:这个世界不生成结构,回执如实说没有,不报一个坐标。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_locate")
    public static void locate_structure_that_is_not_here_says_so(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_pilgrim", new BlockPos(3, 2, 3), false);
        ToolRun locate = command(companion, "locate structure minecraft:village");

        helper.succeedWhen(() -> {
            helper.assertTrue(locate.done(), "locate structure has not finished");
            helper.assertTrue(!locate.outcome().startsWith("nearest ") && locate.outcome().contains("village"),
                    "the reply claims a village or does not name it: " + locate.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }
}
