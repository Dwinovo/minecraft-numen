package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.Checks;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import com.dwinovo.numen.api.gametest.BeforeBatch;
import com.dwinovo.numen.api.gametest.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/** 钓鱼:{@code fish} 在附近的水边用原版的抛竿、咬钩、收线钓上来。 */
public class FishGameTests {

    /** 钓鱼批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_fish")
    public static void prepareFishBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 站在池沿上、手里有竿:抛一竿钓上来,背包里多了钓上来的东西、回执点名是什么,鱼竿掉了耐久。
     * 原版咬钩前的等待随机在 100–600 刻、鱼游过来再 20–80 刻,加上抛竿与收竿,最坏要七百来刻;时限按这个上界留,所以在慢批。
     */
    @GameTest(template = "floor16", timeoutTicks = 924, batch = "numen_fish")
    public static void fish_reels_in_from_a_pool(GameTestHelper helper) {
        pool(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_angler", new BlockPos(4, 3, 7), false);
        companion.getInventory().add(new ItemStack(Items.FISHING_ROD));
        ToolRun fish = lua(companion, "numen.work.fish()");

        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, fish.done(), "work fish has not finished");
            Checks.assertTrue(helper, fish.succeeded(), "fishing failed: " + fish.outcome());
            var inv = companion.getInventory();
            // 背包里比开钓时多了东西:她只带了一根竿,钓上来的可能也是一根竿(原版杂物表里有,权重 2),所以按件数比,不按"竿以外的东西"
            boolean caught = inv.getNonEquipmentItems().stream().mapToInt(ItemStack::getCount).sum() >= 2;
            boolean worn = inv.getNonEquipmentItems().stream().anyMatch(s -> s.is(Items.FISHING_ROD) && s.getDamageValue() > 0);
            Checks.assertTrue(helper, caught && worn, "nothing was reeled in, or the rod took no wear: " + fish.outcome());
            Checks.assertTrue(helper, fish.outcome().startsWith("reeled in minecraft:"),
                    "the result does not name the catch: " + fish.outcome());
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
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_fish")
    public static void task_stop_while_fishing_reels_the_line_in(GameTestHelper helper) {
        pool(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_recalled", new BlockPos(4, 3, 7), false);
        companion.getInventory().add(new ItemStack(Items.FISHING_ROD));
        ToolRun fish = lua(companion, "numen.work.fish()");
        java.util.concurrent.atomic.AtomicReference<ToolRun> stop = new java.util.concurrent.atomic.AtomicReference<>();

        steps(helper)
                .thenWaitUntil(() -> Checks.assertTrue(helper, companion.fishing != null, "she has not cast yet"))
                .thenExecute(() -> stop.set(lua(companion, "numen.task.stop()")))
                .thenWaitUntil(() -> Checks.assertTrue(helper, stop.get().succeeded() && fish.done()
                                && fish.task().getState() == com.dwinovo.numen.api.task.TaskState.CANCELLED,
                        "fishing was not stopped: " + stop.get().reply() + " / " + fish.outcome()))
                .thenWaitUntil(() -> Checks.assertTrue(helper, companion.fishing == null
                                && helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.FishingHook.class,
                                        new net.minecraft.world.phys.AABB(companion.blockPosition()).inflate(16)).isEmpty(),
                        "the bobber is still out after the stop"))
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }
}
