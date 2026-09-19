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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 睡觉:{@code sleep} 躺进够得着的床,睡没睡着以服务端为准,睡不了就把原版的理由递回去。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class SleepGameTests {

    /** 夜里的批次前置:和平难度(附近没怪)+ 半夜。 */
    @BeforeBatch(batch = "numen_sleep_night")
    public static void prepareNightBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, MIDNIGHT);
    }

    /** 白天的批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_sleep_day")
    public static void prepareDayBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 半夜、床就在身边:不给坐标也找得到手边那张,服务端确认她睡着了。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_sleep_night")
    public static void sleep_in_a_bed_within_reach(GameTestHelper helper) {
        placeBed(helper, new BlockPos(5, 2, 5));
        NumenPlayer companion = spawnAt(helper, "gametest_sleeper", new BlockPos(4, 2, 5), false);
        ToolRun sleep = call(companion, "sleep", args());

        helper.succeedWhen(() -> {
            helper.assertTrue(sleep.done(), "sleep has not replied");
            helper.assertTrue(sleep.succeeded() && companion.isSleeping(),
                    "she is not asleep: " + sleep.outcome());
            companion.stopSleeping();
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 手边没有床、身上却带着一张:回执让她先把带着的那张放下,不叫她满世界去找。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_sleep_night")
    public static void sleep_without_a_bed_but_carrying_one_says_place_it(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_camper", new BlockPos(4, 2, 5), false);
        companion.getInventory().add(new ItemStack(Items.WHITE_BED));
        ToolRun sleep = call(companion, "sleep", args());

        helper.succeedWhen(() -> {
            helper.assertTrue(sleep.done(), "sleep has not replied");
            helper.assertTrue(!sleep.succeeded() && sleep.outcome().contains("You are carrying")
                            && sleep.outcome().contains("place it"),
                    "the reply does not point at the bed she carries: " + sleep.outcome());
            helper.assertTrue(!companion.isSleeping(), "she is asleep without a bed");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 大白天:原版不让睡,回执带着原版的理由和那张床的位置,她也确实没躺下。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_sleep_day")
    public static void sleep_in_daylight_hands_back_the_reason(GameTestHelper helper) {
        placeBed(helper, new BlockPos(5, 2, 5));
        NumenPlayer companion = spawnAt(helper, "gametest_napper", new BlockPos(4, 2, 5), false);
        ToolRun sleep = call(companion, "sleep", args());

        helper.succeedWhen(() -> {
            helper.assertTrue(sleep.done(), "sleep has not replied");
            helper.assertTrue(!sleep.succeeded() && sleep.outcome().contains("(bed at "),
                    "the refusal does not come back with the bed: " + sleep.outcome());
            helper.assertTrue(!companion.isSleeping(), "she is asleep in daylight");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 一张朝东的红床:床脚在 rel,床头在它东边一格。 */
    private static void placeBed(GameTestHelper helper, BlockPos footRel) {
        BlockState foot = Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT);
        helper.getLevel().setBlock(helper.absolutePos(footRel), foot, 3);
        helper.getLevel().setBlock(helper.absolutePos(footRel.east()), foot.setValue(BedBlock.PART, BedPart.HEAD), 3);
    }
}
