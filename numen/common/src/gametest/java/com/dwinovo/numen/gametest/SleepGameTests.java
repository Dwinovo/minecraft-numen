package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
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

import static com.dwinovo.numen.gametest.GameTestKit.*;

/** 睡觉:{@code numen.use.sleep} 躺进够得着的床,睡没睡着以服务端为准,睡不了就把原版的理由递回去。 */
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

    /** 半夜、床就在身边:不给坐标也找得到手边那张,服务端确认她睡着了。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_sleep_night")
    public static void sleep_in_a_bed_within_reach(GameTestHelper helper) {
        placeBed(helper, new BlockPos(5, 2, 5));
        NumenPlayer companion = spawnAt(helper, "gametest_sleeper", new BlockPos(4, 2, 5), false);
        ToolRun sleep = lua(companion, "numen.use.sleep()");

        succeedWhen(helper, () -> {
            helper.assertTrue(sleep.done(), "use sleep has not replied");
            helper.assertTrue(sleep.succeeded() && companion.isSleeping(),
                    "she is not asleep: " + sleep.outcome());
            companion.stopSleeping();
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

    /** 半夜、床在手边,但三格外站着一只僵尸:原版不让睡,回执用原版的话说附近有怪、带着那张床的位置,她没躺下,也没走开。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_sleep_danger")
    public static void sleep_with_a_monster_nearby_hands_back_the_reason(GameTestHelper helper) {
        placeBed(helper, new BlockPos(5, 2, 5));
        var zombie = net.minecraft.world.entity.EntityType.ZOMBIE.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(9, 2, 5));
        zombie.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        zombie.setNoAi(true);
        helper.getLevel().addFreshEntity(zombie);
        NumenPlayer companion = spawnAt(helper, "gametest_wary", new BlockPos(4, 2, 5), false);
        BlockPos start = companion.blockPosition();
        ToolRun sleep = lua(companion, "numen.use.sleep()");

        succeedWhen(helper, () -> {
            helper.assertTrue(sleep.done(), "use sleep has not replied");
            helper.assertTrue(!sleep.succeeded() && !companion.isSleeping()
                            && sleep.outcome().contains("monsters nearby") && sleep.outcome().contains("(bed at "),
                    "the reply does not say monsters are nearby, with the bed: " + sleep.outcome());
            helper.assertTrue(companion.blockPosition().equals(start), "she walked off after the refusal");
            zombie.discard();
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 躺下之后 use.wake 起床:服务端确认她不再睡着。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_sleep_night")
    public static void wake_gets_her_out_of_bed(GameTestHelper helper) {
        placeBed(helper, new BlockPos(5, 2, 5));
        NumenPlayer companion = spawnAt(helper, "gametest_riser", new BlockPos(4, 2, 5), false);

        Steps steps = steps(helper);
        steps = call(helper, steps, companion, "numen.use.sleep()");
        steps = steps.thenWaitUntil(() -> helper.assertTrue(companion.isSleeping(), "she is not asleep"));
        steps = call(helper, steps, companion, "numen.use.wake()");
        steps.thenWaitUntil(() -> helper.assertTrue(!companion.isSleeping(), "she is still asleep"))
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion)).thenSucceed();
    }
}
