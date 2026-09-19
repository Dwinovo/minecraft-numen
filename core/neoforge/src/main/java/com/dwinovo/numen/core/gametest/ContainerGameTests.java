package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 容器:{@code interact_at} 右键打开、{@code inspect_gui} 看格子、{@code transfer} 搬东西、{@code close_gui} 合上。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class ContainerGameTests {

    /** 容器批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_container")
    public static void prepareContainerBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 模型取箱子里的东西就是这四步:右键打开箱子,看一眼格子,把第 0 格整叠拿进背包,合上。每一步都照模型的
     * 样子从工具入口调;五颗钻石到了她身上,箱子空了,界面合上。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_container")
    public static void take_from_a_chest_open_look_move_close(GameTestHelper helper) {
        BlockPos chest = chestWithDiamonds(helper, new BlockPos(5, 2, 4), 5);
        NumenPlayer companion = spawnAt(helper, "gametest_looter", new BlockPos(3, 2, 4), false);
        AtomicReference<ToolRun> step = new AtomicReference<>();

        // 动作放 thenExecute、断言放 thenWaitUntil:原版序列里 thenExecute 的断言失败后,后面的步骤照样在同一刻
        // 跑下去,报出来的是最后一个失败;等在 thenWaitUntil 里,哪一步没过就停在哪一步、报哪一步
        helper.startSequence()
                .thenExecute(() -> step.set(call(companion, "interact_at",
                        args("button", "right", "x", chest.getX(), "y", chest.getY(), "z", chest.getZ()))))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.containerMenu instanceof ChestMenu,
                        "the chest did not open: " + step.get().outcome()))
                .thenExecute(() -> step.set(call(companion, "inspect_gui", args())))
                .thenWaitUntil(() -> helper.assertTrue(step.get().succeeded() && step.get().reply().contains("0: ")
                                && step.get().reply().contains("diamond"),
                        "inspect_gui does not show the diamonds in slot 0: " + step.get().reply()))
                .thenExecute(() -> step.set(call(companion, "transfer", args("moves", List.of(args("from", 0))))))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.getInventory().countItem(Items.DIAMOND) == 5
                                && ((ChestBlockEntity) helper.getLevel().getBlockEntity(chest)).isEmpty(),
                        "the diamonds did not move from the chest into her inventory: " + step.get().outcome()))
                .thenExecute(() -> step.set(call(companion, "close_gui", args())))
                .thenWaitUntil(() -> helper.assertTrue(step.get().succeeded()
                                && companion.containerMenu == companion.inventoryMenu,
                        "close_gui did not close the chest: " + step.get().reply()))
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /** 没开任何容器就搬东西:她自己的背包界面里没有箱子那一段,越界的格子号如实报出来,什么都不动。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_container")
    public static void transfer_with_no_container_open_moves_nothing(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_shuffler", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(Items.DIAMOND, 5));
        ToolRun transfer = call(companion, "transfer", args("moves", List.of(args("from", 90))));

        helper.succeedWhen(() -> {
            helper.assertTrue(transfer.done(), "transfer has not finished");
            helper.assertTrue(transfer.outcome().contains("OUT OF RANGE"),
                    "the reply does not say the slot is out of range: " + transfer.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 5, "the diamonds moved");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }
}
