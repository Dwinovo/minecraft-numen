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

/** 睡觉:{@code use sleep} 躺进够得着的床,睡没睡着以服务端为准,睡不了就把原版的理由递回去。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class SleepGameTests {

    /** 夜里的批次前置:和平难度(附近没怪)+ 半夜。 */
    @BeforeBatch(batch = "numen_sleep_night")
    public static void prepareNightBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, MIDNIGHT);
    }

    /** 有怪的夜里:普通难度 + 半夜,怪由用例自己摆。 */
    @BeforeBatch(batch = "numen_sleep_danger")
    public static void prepareDangerBatch(ServerLevel level) {
        settleWorld(level, Difficulty.NORMAL, MIDNIGHT);
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
        ToolRun sleep = command(companion, "use sleep");

        helper.succeedWhen(() -> {
            helper.assertTrue(sleep.done(), "use sleep has not replied");
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
        ToolRun sleep = command(companion, "use sleep");

        helper.succeedWhen(() -> {
            helper.assertTrue(sleep.done(), "use sleep has not replied");
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
        ToolRun sleep = command(companion, "use sleep");

        helper.succeedWhen(() -> {
            helper.assertTrue(sleep.done(), "use sleep has not replied");
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

    /** 给的坐标上不是床:如实说那儿没床,并指路怎么找床。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_sleep_night")
    public static void sleep_at_coordinates_without_a_bed_says_so(GameTestHelper helper) {
        BlockPos floor = helper.absolutePos(new BlockPos(5, 1, 5));
        NumenPlayer companion = spawnAt(helper, "gametest_misled", new BlockPos(4, 2, 5), false);
        ToolRun sleep = command(companion, "use sleep --x " + floor.getX() + " --y " + floor.getY() + " --z " + floor.getZ());

        helper.succeedWhen(() -> {
            helper.assertTrue(sleep.done(), "use sleep has not replied");
            helper.assertTrue(!sleep.succeeded() && sleep.outcome().contains("no bed at those coordinates")
                            && sleep.outcome().contains("scan_blocks"),
                    "the reply does not say there is no bed there: " + sleep.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 只给了床的一部分坐标:不猜是哪张床,也不改用手边那张,如实说三个要一起给;她没躺下。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_sleep_night")
    public static void sleep_with_part_of_the_coordinates_says_give_all_three(GameTestHelper helper) {
        placeBed(helper, new BlockPos(5, 2, 5));
        BlockPos head = helper.absolutePos(new BlockPos(6, 2, 5));
        NumenPlayer companion = spawnAt(helper, "gametest_halfsure", new BlockPos(4, 2, 5), false);
        ToolRun sleep = command(companion, "use sleep --x " + head.getX() + " --z " + head.getZ());

        helper.succeedWhen(() -> {
            helper.assertTrue(sleep.done(), "use sleep has not replied");
            helper.assertTrue(!sleep.succeeded() && sleep.outcome().contains("needs all of --x --y --z"),
                    "the reply does not ask for all three coordinates: " + sleep.outcome());
            helper.assertTrue(!companion.isSleeping(), "she lay down in a bed she did not fully name");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 半夜、床在手边,但三格外站着一只僵尸:原版不让睡,回执用原版的话说附近有怪,她没躺下。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_sleep_danger")
    public static void sleep_with_a_monster_nearby_hands_back_the_reason(GameTestHelper helper) {
        placeBed(helper, new BlockPos(5, 2, 5));
        var zombie = net.minecraft.world.entity.EntityType.ZOMBIE.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(9, 2, 5));
        zombie.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        zombie.setNoAi(true);
        helper.getLevel().addFreshEntity(zombie);
        NumenPlayer companion = spawnAt(helper, "gametest_wary", new BlockPos(4, 2, 5), false);
        ToolRun sleep = command(companion, "use sleep");

        helper.succeedWhen(() -> {
            helper.assertTrue(sleep.done(), "use sleep has not replied");
            helper.assertTrue(!sleep.succeeded() && !companion.isSleeping()
                            && sleep.outcome().contains("monsters nearby"),
                    "the reply does not say monsters are nearby: " + sleep.outcome());
            zombie.discard();
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 给了一张远处床的坐标:原版嫌床太远,回执照原话说,她没躺下,也没自己走过去。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_sleep_night")
    public static void sleep_in_a_bed_too_far_away_says_so(GameTestHelper helper) {
        placeBed(helper, new BlockPos(12, 2, 12));
        BlockPos head = helper.absolutePos(new BlockPos(13, 2, 12));
        NumenPlayer companion = spawnAt(helper, "gametest_faraway", new BlockPos(3, 2, 3), false);
        BlockPos start = companion.blockPosition();
        ToolRun sleep = command(companion, "use sleep --x " + head.getX() + " --y " + head.getY() + " --z " + head.getZ());

        helper.succeedWhen(() -> {
            helper.assertTrue(sleep.done(), "use sleep has not replied");
            helper.assertTrue(!sleep.succeeded() && !companion.isSleeping()
                            && sleep.outcome().contains("too far away"),
                    "the reply does not say the bed is too far away: " + sleep.outcome());
            helper.assertTrue(companion.blockPosition().equals(start), "she walked off toward the bed");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }
}
