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

/** 容器:{@code use block} 右键打开、{@code use gui} 看格子、{@code transfer} 搬东西、{@code use close} 合上。 */
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
                .thenExecute(() -> step.set(command(companion, "use block right " + xyz(chest))))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.containerMenu instanceof ChestMenu,
                        "the chest did not open: " + step.get().outcome()))
                .thenExecute(() -> step.set(command(companion, "use gui")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().succeeded() && step.get().reply().contains("0: ")
                                && step.get().reply().contains("diamond"),
                        "use gui does not show the diamonds in slot 0: " + step.get().reply()))
                .thenExecute(() -> step.set(call(companion, "transfer", args("moves", List.of(args("from", 0))))))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.getInventory().countItem(Items.DIAMOND) == 5
                                && ((ChestBlockEntity) helper.getLevel().getBlockEntity(chest)).isEmpty(),
                        "the diamonds did not move from the chest into her inventory: " + step.get().outcome()))
                .thenExecute(() -> step.set(command(companion, "use close")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().succeeded()
                                && companion.containerMenu == companion.inventoryMenu,
                        "use close did not close the chest: " + step.get().reply()))
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

    /** 没开着任何方块界面时关界面:不算错,回执如实说没有开着的方块界面。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_container")
    public static void close_gui_with_nothing_open_says_so(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_tidy", new BlockPos(3, 2, 3), false);
        ToolRun close = command(companion, "use close");

        helper.succeedWhen(() -> {
            helper.assertTrue(close.succeeded() && close.reply().contains("no block GUI was open"),
                    "the reply does not say nothing was open: " + close.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 往塞满圆石的箱子里存钻石:打开箱子,把背包里那叠钻石往箱子那一段送。箱子一格都放不下,这一步的回执说没搬动、
     * 那边满了;钻石还在她身上,箱子原样。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_container")
    public static void transfer_into_a_full_chest_says_nothing_moved(GameTestHelper helper) {
        BlockPos chest = helper.absolutePos(new BlockPos(5, 2, 4));
        helper.getLevel().setBlockAndUpdate(chest, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        ChestBlockEntity box = (ChestBlockEntity) helper.getLevel().getBlockEntity(chest);
        for (int i = 0; i < box.getContainerSize(); i++) {
            box.setItem(i, new net.minecraft.world.item.ItemStack(Items.COBBLESTONE, 64));
        }
        NumenPlayer companion = spawnAt(helper, "gametest_depositor", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(Items.DIAMOND, 5));
        AtomicReference<ToolRun> step = new AtomicReference<>();

        helper.startSequence()
                .thenExecute(() -> step.set(command(companion, "use block right " + xyz(chest))))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.containerMenu instanceof ChestMenu,
                        "the chest did not open: " + step.get().outcome()))
                .thenExecute(() -> step.set(call(companion, "transfer",
                        args("moves", List.of(args("from", menuSlotOf(companion, Items.DIAMOND)))))))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done()
                                && step.get().outcome().contains("didn't move"),
                        "the reply does not say the diamonds stayed: " + step.get().outcome()))
                .thenWaitUntil(() -> helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 5
                                && box.countItem(Items.COBBLESTONE) == box.getContainerSize() * 64
                                && box.countItem(Items.DIAMOND) == 0,
                        "something moved between her and the full chest"))
                .thenExecute(() -> step.set(command(companion, "use close")))
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /** 从箱子里按数量拿:五颗钻石里拿两颗放进她背包的一个空格,正好两颗过来,箱子里剩三颗。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_container")
    public static void transfer_an_exact_count_into_a_slot(GameTestHelper helper) {
        BlockPos chest = chestWithDiamonds(helper, new BlockPos(5, 2, 4), 5);
        ChestBlockEntity box = (ChestBlockEntity) helper.getLevel().getBlockEntity(chest);
        NumenPlayer companion = spawnAt(helper, "gametest_counter", new BlockPos(3, 2, 4), false);
        AtomicReference<ToolRun> step = new AtomicReference<>();

        helper.startSequence()
                .thenExecute(() -> step.set(command(companion, "use block right " + xyz(chest))))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.containerMenu instanceof ChestMenu,
                        "the chest did not open: " + step.get().outcome()))
                .thenExecute(() -> step.set(call(companion, "transfer", args("moves", List.of(
                        args("from", 0, "to", menuSlotOf(companion, Items.AIR), "count", 2))))))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && step.get().outcome().contains("moved 2 diamond"),
                        "the reply does not say two diamonds moved: " + step.get().outcome()))
                .thenWaitUntil(() -> helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 2
                                && box.countItem(Items.DIAMOND) == 3,
                        "she has " + companion.getInventory().countItem(Items.DIAMOND) + " and the chest "
                                + box.countItem(Items.DIAMOND)))
                .thenExecute(() -> step.set(command(companion, "use close")))
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /** 她打开的界面里,她自己背包那一段第一个装着 {@code item} 的格子号(AIR = 第一个空格)——模型从 use gui 读到的就是它。 */
    private static int menuSlotOf(NumenPlayer companion, net.minecraft.world.item.Item item) {
        var slots = companion.containerMenu.slots;
        for (int i = 0; i < slots.size(); i++) {
            var slot = slots.get(i);
            if (slot.container == companion.getInventory()
                    && (item == Items.AIR ? slot.getItem().isEmpty() : slot.getItem().is(item))) {
                return i;
            }
        }
        throw new IllegalStateException("no slot of hers holds " + item);
    }
}
