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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 钓鱼:{@code fish} 在附近的水边用原版的抛竿、咬钩、收线钓上来。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class FishGameTests {

    /** 钓鱼批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_fish")
    public static void prepareFishBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 站在池沿上、手里有竿:钓上一次,背包里多了钓上来的东西,鱼竿掉了耐久。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_fish")
    public static void fish_reels_in_from_a_pool(GameTestHelper helper) {
        pool(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_angler", new BlockPos(4, 3, 7), false);
        companion.getInventory().add(new ItemStack(Items.FISHING_ROD));
        ToolRun fish = call(companion, "fish", args("count", 1));

        helper.succeedWhen(() -> {
            helper.assertTrue(fish.done(), "fish has not finished");
            helper.assertTrue(fish.succeeded(), "fishing failed: " + fish.outcome());
            var inv = companion.getInventory();
            boolean caught = inv.items.stream().anyMatch(s -> !s.isEmpty() && !s.is(Items.FISHING_ROD));
            boolean worn = inv.items.stream().anyMatch(s -> s.is(Items.FISHING_ROD) && s.getDamageValue() > 0);
            helper.assertTrue(caught && worn, "nothing was reeled in, or the rod took no wear: " + fish.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 没带鱼竿:当场失败,说清楚缺的是鱼竿。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_fish")
    public static void fish_without_a_rod_says_so(GameTestHelper helper) {
        pool(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_rodless", new BlockPos(4, 3, 7), false);
        ToolRun fish = call(companion, "fish", args("count", 1));

        helper.succeedWhen(() -> {
            helper.assertTrue(fish.done(), "fish has not finished");
            helper.assertTrue(!fish.succeeded() && fish.outcome().contains("fishing rod"),
                    "the failure does not name the missing rod: " + fish.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 地板上用一圈石头围出一池 5×5 的水:石圈占 x/z 4..10 的外沿,水在里面一层;人站在西边的池沿上。 */
    private static void pool(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 4; x <= 10; x++) {
            for (int z = 4; z <= 10; z++) {
                boolean rim = x == 4 || x == 10 || z == 4 || z == 10;
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 2, z)),
                        rim ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState());
            }
        }
    }

    /** 钓到一半叫停:活按叫停收场,抛出去的鱼钩收回来了,水里不留浮漂。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_fish")
    public static void task_stop_while_fishing_reels_the_line_in(GameTestHelper helper) {
        pool(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_recalled", new BlockPos(4, 3, 7), false);
        companion.getInventory().add(new ItemStack(Items.FISHING_ROD));
        ToolRun fish = call(companion, "fish", args("count", 5));
        java.util.concurrent.atomic.AtomicReference<ToolRun> stop = new java.util.concurrent.atomic.AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(companion.fishing != null, "she has not cast yet"))
                .thenExecute(() -> stop.set(call(companion, "task_stop", args())))
                .thenWaitUntil(() -> helper.assertTrue(stop.get().succeeded() && fish.done()
                                && fish.task().getState() == com.dwinovo.numen.task.TaskState.CANCELLED,
                        "fishing was not stopped: " + stop.get().reply() + " / " + fish.outcome()))
                .thenWaitUntil(() -> helper.assertTrue(companion.fishing == null
                                && helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.FishingHook.class,
                                        new net.minecraft.world.phys.AABB(companion.blockPosition()).inflate(16)).isEmpty(),
                        "the bobber is still out after the stop"))
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }
}
