package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
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

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * 容器:{@code numen.use.block} 右键打开、{@code numen.gui.view} 看格子、{@code numen.gui.quick} 整叠挪到另一边或 {@code numen.gui.move} 放进指定的一格、
 * {@code numen.gui.close} 合上。
 */
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
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_container")
    public static void take_from_a_chest_open_look_move_close(GameTestHelper helper) {
        BlockPos chest = chestWithDiamonds(helper, new BlockPos(5, 2, 4), 5);
        NumenPlayer companion = spawnAt(helper, "gametest_looter", new BlockPos(3, 2, 4), false);
        AtomicReference<ToolRun> step = new AtomicReference<>();

        // 动作放 thenExecute、断言放 thenWaitUntil:原版序列里 thenExecute 的断言失败后,后面的步骤照样在同一刻
        // 跑下去,报出来的是最后一个失败;等在 thenWaitUntil 里,哪一步没过就停在哪一步、报哪一步
        steps(helper)
                .thenExecute(() -> step.set(lua(companion, "numen.use.block(" + xyz(chest) + ")")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.containerMenu instanceof ChestMenu,
                        "the chest did not open: " + step.get().outcome()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.view()")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().succeeded()
                                && step.get().reply().contains("{\"index\":0,\"side\":\"container\",\"item\":\"minecraft:diamond\",\"count\":5}"),
                        "gui view does not show the diamonds in slot 0: " + step.get().reply()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.quick(0)")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.getInventory().countItem(Items.DIAMOND) == 5
                                && ((ChestBlockEntity) helper.getLevel().getBlockEntity(chest)).isEmpty(),
                        "the diamonds did not move from the chest into her inventory: " + step.get().outcome()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.close()")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().succeeded()
                                && companion.containerMenu == companion.inventoryMenu,
                        "gui close did not close the chest: " + step.get().reply()))
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /** 没开任何容器就搬东西:她自己的背包界面里没有箱子那一段,越界的格子号如实报出来,什么都不动。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_container")
    public static void transfer_with_no_container_open_moves_nothing(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_shuffler", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(Items.DIAMOND, 5));
        ToolRun transfer = lua(companion, "numen.gui.quick(90)");

        succeedWhen(helper, () -> {
            helper.assertTrue(transfer.done(), "transfer has not finished");
            helper.assertTrue(transfer.outcome().contains("OUT OF RANGE"),
                    "the reply does not say the slot is out of range: " + transfer.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 5, "the diamonds moved");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 没开着任何方块界面时关界面:不算错,交回 false——没有开着的界面可关。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_container")
    public static void close_gui_with_nothing_open_says_so(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_tidy", new BlockPos(3, 2, 3), false);
        ToolRun close = lua(companion, "numen.gui.close()");

        succeedWhen(helper, () -> {
            helper.assertTrue(close.succeeded() && Boolean.FALSE.equals(close.value()),
                    "the reply does not say nothing was open: " + close.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 往塞满圆石的箱子里存钻石:打开箱子,把背包里那叠钻石往箱子那一段送。箱子一格都放不下,这一步的回执说没搬动、
     * 那边满了;钻石还在她身上,箱子原样。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_container")
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

        steps(helper)
                .thenExecute(() -> step.set(lua(companion, "numen.use.block(" + xyz(chest) + ")")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.containerMenu instanceof ChestMenu,
                        "the chest did not open: " + step.get().outcome()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.quick(" + menuSlotOf(companion, Items.DIAMOND) + ")")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done()
                                && step.get().outcome().contains("didn't move"),
                        "the reply does not say the diamonds stayed: " + step.get().outcome()))
                .thenWaitUntil(() -> helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 5
                                && box.countItem(Items.COBBLESTONE) == box.getContainerSize() * 64
                                && box.countItem(Items.DIAMOND) == 0,
                        "something moved between her and the full chest"))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.close()")))
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /** 从箱子里按数量拿:五颗钻石里拿两颗放进她背包的一个空格,正好两颗过来,箱子里剩三颗。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_container")
    public static void transfer_an_exact_count_into_a_slot(GameTestHelper helper) {
        BlockPos chest = chestWithDiamonds(helper, new BlockPos(5, 2, 4), 5);
        ChestBlockEntity box = (ChestBlockEntity) helper.getLevel().getBlockEntity(chest);
        NumenPlayer companion = spawnAt(helper, "gametest_counter", new BlockPos(3, 2, 4), false);
        AtomicReference<ToolRun> step = new AtomicReference<>();

        steps(helper)
                .thenExecute(() -> step.set(lua(companion, "numen.use.block(" + xyz(chest) + ")")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.containerMenu instanceof ChestMenu,
                        "the chest did not open: " + step.get().outcome()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.move(0, " + menuSlotOf(companion, Items.AIR) + ", {count = 2})")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && step.get().outcome().contains("moved 2 diamond"),
                        "the reply does not say two diamonds moved: " + step.get().outcome()))
                .thenWaitUntil(() -> helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 2
                                && box.countItem(Items.DIAMOND) == 3,
                        "she has " + companion.getInventory().countItem(Items.DIAMOND) + " and the chest "
                                + box.countItem(Items.DIAMOND)))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.close()")))
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /** 附魔台:看三个选项,放剑和青金石,选第一项——剑上了附魔,青金石扣了一块,经验扣了一级。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_container")
    public static void gui_choose_enchants_the_item_for_levels_and_lapis(GameTestHelper helper) {
        BlockPos table = place(helper, new BlockPos(5, 2, 4), net.minecraft.world.level.block.Blocks.ENCHANTING_TABLE);
        NumenPlayer companion = spawnAt(helper, "gametest_enchanter", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(Items.DIAMOND_SWORD));
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(Items.LAPIS_LAZULI, 3));
        companion.giveExperienceLevels(30);
        AtomicReference<ToolRun> view = new AtomicReference<>();

        Steps steps = steps(helper);
        steps = call(helper, steps, companion, "numen.use.block(" + xyz(table) + ")");
        steps = call(helper, steps, companion, "numen.gui.put(\"minecraft:diamond_sword\")");
        steps = call(helper, steps, companion, "numen.gui.put(\"minecraft:lapis_lazuli\")");
        steps = steps.thenExecute(() -> view.set(lua(companion, "numen.gui.view()")))
                .thenWaitUntil(() -> helper.assertTrue(view.get().succeeded() && view.get().reply().contains("\"options\"")
                                && view.get().reply().contains("\"cost\""),
                        "gui view does not show the enchanting options: " + view.get().reply()));
        steps = call(helper, steps, companion, "numen.gui.choose(1)");
        steps.thenWaitUntil(() -> {
            var menu = (net.minecraft.world.inventory.EnchantmentMenu) companion.containerMenu;
            helper.assertTrue(menu.getSlot(0).getItem().isEnchanted(), "the sword was not enchanted");
            helper.assertTrue(menu.getGoldCount() == 2, "the table holds " + menu.getGoldCount() + " lapis, not 2");
            helper.assertTrue(companion.experienceLevel == 29, "her level is " + companion.experienceLevel + ", not 29");
        }).thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion)).thenSucceed();
    }

    /** 等级不够:原版什么也不做,回执如实说这一项要多少级、她有几级,剑没附魔、青金石还在。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_container")
    public static void gui_choose_without_the_levels_says_what_it_needs(GameTestHelper helper) {
        BlockPos table = place(helper, new BlockPos(5, 2, 4), net.minecraft.world.level.block.Blocks.ENCHANTING_TABLE);
        NumenPlayer companion = spawnAt(helper, "gametest_poor_enchanter", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(Items.DIAMOND_SWORD));
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(Items.LAPIS_LAZULI, 3));
        AtomicReference<ToolRun> choose = new AtomicReference<>();

        Steps steps = steps(helper);
        steps = call(helper, steps, companion, "numen.use.block(" + xyz(table) + ")");
        steps = call(helper, steps, companion, "numen.gui.put(\"minecraft:diamond_sword\")");
        steps = call(helper, steps, companion, "numen.gui.put(\"minecraft:lapis_lazuli\")");
        steps.thenExecute(() -> choose.set(lua(companion, "numen.gui.choose(1)")))
                .thenWaitUntil(() -> {
                    helper.assertTrue(choose.get().done() && !choose.get().succeeded()
                                    && choose.get().outcome().contains("experience levels")
                                    && choose.get().outcome().contains("you have 0 levels"),
                            "the refusal does not say what it needs: " + choose.get().outcome());
                    var menu = (net.minecraft.world.inventory.EnchantmentMenu) companion.containerMenu;
                    helper.assertTrue(!menu.getSlot(0).getItem().isEnchanted() && menu.getGoldCount() == 3,
                            "the table changed although it refused");
                }).thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion)).thenSucceed();
    }

    /** 切石机:放石头,看配方,选第一个,取成品——拿到的就是第一个配方的产物。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_container")
    public static void gui_choose_a_stonecutter_recipe_and_take_the_result(GameTestHelper helper) {
        BlockPos cutter = place(helper, new BlockPos(5, 2, 4), net.minecraft.world.level.block.Blocks.STONECUTTER);
        NumenPlayer companion = spawnAt(helper, "gametest_mason", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(Items.STONE, 4));
        AtomicReference<ToolRun> view = new AtomicReference<>();

        Steps steps = steps(helper);
        steps = call(helper, steps, companion, "numen.use.block(" + xyz(cutter) + ")");
        steps = call(helper, steps, companion, "numen.gui.put(\"minecraft:stone\", 1)");
        steps = steps.thenExecute(() -> view.set(lua(companion, "numen.gui.view()")))
                .thenWaitUntil(() -> helper.assertTrue(view.get().succeeded() && view.get().reply().contains("\"recipes\""),
                        "gui view does not list the recipes: " + view.get().reply()));
        steps = call(helper, steps, companion, "numen.gui.choose(1)");
        var made = new AtomicReference<net.minecraft.world.item.ItemStack>();
        steps = steps.thenExecute(() -> made.set(companion.containerMenu.getSlot(1).getItem().copy()));
        steps = call(helper, steps, companion, "numen.gui.quick(1)");
        steps.thenWaitUntil(() -> {
            helper.assertTrue(!made.get().isEmpty() && companion.getInventory().countItem(made.get().getItem())
                            == made.get().getCount(), "she did not take the first recipe's result " + made.get());
        }).thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion)).thenSucceed();
    }

    /** 织布机:放旗帜和染料,选第一个图案,取成品——旗帜上织了一层图案。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_container")
    public static void gui_choose_a_loom_pattern_and_take_the_banner(GameTestHelper helper) {
        BlockPos loom = place(helper, new BlockPos(5, 2, 4), net.minecraft.world.level.block.Blocks.LOOM);
        NumenPlayer companion = spawnAt(helper, "gametest_weaver", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(Items.WHITE_BANNER));
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(Items.RED_DYE));

        Steps steps = steps(helper);
        steps = call(helper, steps, companion, "numen.use.block(" + xyz(loom) + ")");
        steps = call(helper, steps, companion, "numen.gui.put(\"minecraft:white_banner\")");
        steps = call(helper, steps, companion, "numen.gui.put(\"minecraft:red_dye\")");
        steps = call(helper, steps, companion, "numen.gui.choose(1)");
        steps = call(helper, steps, companion, "numen.gui.quick(3)");
        steps.thenWaitUntil(() -> {
            var banner = companion.getInventory().items.stream().filter(it -> it.is(Items.WHITE_BANNER)).findFirst();
            helper.assertTrue(banner.isPresent() && banner.get().getOrDefault(
                            net.minecraft.core.component.DataComponents.BANNER_PATTERNS,
                            net.minecraft.world.level.block.entity.BannerPatternLayers.EMPTY).layers().size() == 1,
                    "she carries no banner with one woven pattern");
        }).thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion)).thenSucceed();
    }

    /** 村民交易:看交易表,选第一笔(原版自己从背包填付款),取成品——面包到手,这一笔的已用次数跟着涨。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_container")
    public static void gui_choose_a_trade_and_take_the_result(GameTestHelper helper) {
        var villager = net.minecraft.world.entity.EntityType.VILLAGER.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(8, 2, 8));
        villager.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        villager.setNoAi(true);
        villager.setVillagerData(villager.getVillagerData()
                .setProfession(net.minecraft.world.entity.npc.VillagerProfession.FARMER));
        var offers = villager.getOffers();
        offers.clear();
        offers.add(new net.minecraft.world.item.trading.MerchantOffer(
                new net.minecraft.world.item.trading.ItemCost(Items.EMERALD, 2),
                new net.minecraft.world.item.ItemStack(Items.BREAD, 6), 5, 1, 0.0f));
        helper.getLevel().addFreshEntity(villager);
        NumenPlayer companion = spawnAt(helper, "gametest_buyer", new BlockPos(6, 2, 8), false);
        com.dwinovo.numen.api.permission.Permission.setMode(companion, com.dwinovo.numen.permission.Mode.BYPASS);
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(Items.EMERALD, 5));
        AtomicReference<ToolRun> view = new AtomicReference<>();

        Steps steps = steps(helper);
        steps = call(helper, steps, companion, "numen.use.entity(" + ref(villager) + ")");
        steps = steps.thenExecute(() -> view.set(lua(companion, "numen.gui.view()")))
                .thenWaitUntil(() -> helper.assertTrue(view.get().succeeded() && view.get().reply().contains("\"trades\"")
                                && view.get().reply().contains("minecraft:bread") && view.get().reply().contains("\"max\":5"),
                        "gui view does not list the trade: " + view.get().reply()));
        steps = call(helper, steps, companion, "numen.gui.choose(1)");
        steps = call(helper, steps, companion, "numen.gui.quick(2)");
        steps.thenWaitUntil(() -> {
            // 原版把背包里的翠绿宝石一次全填进付款格,Shift 点结果格是能买几次就买几次
            int uses = offers.get(0).getUses();
            helper.assertTrue(uses >= 1 && companion.getInventory().countItem(Items.BREAD) == 6 * uses,
                    "she holds " + companion.getInventory().countItem(Items.BREAD) + " bread after " + uses + " uses");
        }).thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion)).thenSucceed();
    }

    /** 信标:付一块铁锭,选急迫——信标的主效果是急迫,付款被收走。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_container")
    public static void gui_choose_sets_the_beacon_effect(GameTestHelper helper) {
        BlockPos beacon = place(helper, new BlockPos(5, 2, 4), net.minecraft.world.level.block.Blocks.BEACON);
        for (int x = 4; x <= 6; x++) {
            for (int z = 3; z <= 5; z++) {
                place(helper, new BlockPos(x, 1, z), net.minecraft.world.level.block.Blocks.IRON_BLOCK);
            }
        }
        NumenPlayer companion = spawnAt(helper, "gametest_keeper", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(Items.IRON_INGOT, 2));
        AtomicReference<ToolRun> view = new AtomicReference<>();

        Steps steps = steps(helper);
        steps = call(helper, steps, companion, "numen.use.block(" + xyz(beacon) + ")");
        // 金字塔层数要等信标自己的检查刻才算出来;直接定下一层,免得用例等它
        steps = steps.thenExecute(() -> ((net.minecraft.world.inventory.BeaconMenu) companion.containerMenu).setData(0, 1));
        steps = call(helper, steps, companion, "numen.gui.put(\"minecraft:iron_ingot\", 1)");
        steps = steps.thenExecute(() -> view.set(lua(companion, "numen.gui.view()")))
                .thenWaitUntil(() -> helper.assertTrue(view.get().succeeded()
                                && view.get().reply().contains("\"primaries\":[\"speed\",\"haste\"]"),
                        "gui view does not list the one-level effects: " + view.get().reply()));
        steps = call(helper, steps, companion, "numen.gui.choose(\"haste\")");
        steps.thenWaitUntil(() -> {
            var menu = (net.minecraft.world.inventory.BeaconMenu) companion.containerMenu;
            helper.assertTrue(menu.getPrimaryEffect() != null
                            && menu.getPrimaryEffect().is(net.minecraft.world.effect.MobEffects.DIG_SPEED),
                    "the beacon's primary effect is " + menu.getPrimaryEffect());
            helper.assertTrue(!menu.hasPayment(), "the payment was not taken");
        }).thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion)).thenSucceed();
    }

    /** 铁砧改名:放剑,改名,取成品——剑叫新名字,扣了经验。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_container")
    public static void gui_rename_names_the_item_in_the_anvil(GameTestHelper helper) {
        BlockPos anvil = place(helper, new BlockPos(5, 2, 4), net.minecraft.world.level.block.Blocks.ANVIL);
        NumenPlayer companion = spawnAt(helper, "gametest_smith", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(Items.IRON_SWORD));
        companion.giveExperienceLevels(5);

        Steps steps = steps(helper);
        steps = call(helper, steps, companion, "numen.use.block(" + xyz(anvil) + ")");
        steps = call(helper, steps, companion, "numen.gui.put(\"minecraft:iron_sword\")");
        steps = call(helper, steps, companion, "numen.gui.rename(\"Excalibur\")");
        steps = call(helper, steps, companion, "numen.gui.quick(2)");
        steps.thenWaitUntil(() -> {
            var sword = companion.getInventory().items.stream().filter(it -> it.is(Items.IRON_SWORD)).findFirst();
            helper.assertTrue(sword.isPresent() && sword.get().getHoverName().getString().equals("Excalibur"),
                    "no sword named Excalibur in her inventory");
            helper.assertTrue(companion.experienceLevel < 5, "the rename cost no levels");
        }).thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion)).thenSucceed();
    }

    /** 讲台:看书的页数,翻到第三页,把书取下来——页翻到了,书进了背包,讲台空了。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_container")
    public static void gui_page_turns_the_lectern_and_take_gets_the_book(GameTestHelper helper) {
        BlockPos lectern = place(helper, new BlockPos(5, 2, 4), net.minecraft.world.level.block.Blocks.LECTERN);
        var book = new net.minecraft.world.item.ItemStack(Items.WRITTEN_BOOK);
        book.set(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT,
                new net.minecraft.world.item.component.WrittenBookContent(
                        net.minecraft.server.network.Filterable.passThrough("Tale"), "someone", 0,
                        java.util.List.of(page("one"), page("two"), page("three")), true));
        net.minecraft.world.level.block.LecternBlock.tryPlaceBook(null, helper.getLevel(), lectern,
                helper.getLevel().getBlockState(lectern), book);
        NumenPlayer companion = spawnAt(helper, "gametest_reader", new BlockPos(3, 2, 4), false);
        AtomicReference<ToolRun> view = new AtomicReference<>();

        Steps steps = steps(helper);
        steps = call(helper, steps, companion, "numen.use.block(" + xyz(lectern) + ")");
        steps = steps.thenExecute(() -> view.set(lua(companion, "numen.gui.view()")))
                .thenWaitUntil(() -> helper.assertTrue(view.get().succeeded() && view.get().reply().contains("\"pages\":3")
                                && view.get().reply().contains("\"text\":\"one\""),
                        "gui view does not show the book: " + view.get().reply()));
        steps = call(helper, steps, companion, "numen.gui.page(3)");
        steps = steps.thenWaitUntil(() -> helper.assertTrue(
                ((net.minecraft.world.inventory.LecternMenu) companion.containerMenu).getPage() == 2,
                "the lectern is not on page 3"));
        steps = call(helper, steps, companion, "numen.gui.take(\"minecraft:written_book\")");
        steps.thenWaitUntil(() -> {
            helper.assertTrue(companion.getInventory().countItem(Items.WRITTEN_BOOK) == 1, "she did not take the book");
            helper.assertTrue(!helper.getLevel().getBlockState(lectern)
                            .getValue(net.minecraft.world.level.block.LecternBlock.HAS_BOOK),
                    "the lectern still has the book");
        }).thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion)).thenSucceed();
    }

    private static net.minecraft.server.network.Filterable<net.minecraft.network.chat.Component> page(String text) {
        return net.minecraft.server.network.Filterable.passThrough(net.minecraft.network.chat.Component.literal(text));
    }

    /** 合成器:关上中间一格——这一格关上了。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_container")
    public static void gui_toggle_closes_a_crafter_slot(GameTestHelper helper) {
        BlockPos crafter = place(helper, new BlockPos(5, 2, 4), net.minecraft.world.level.block.Blocks.CRAFTER);
        NumenPlayer companion = spawnAt(helper, "gametest_crafter", new BlockPos(3, 2, 4), false);

        Steps steps = steps(helper);
        steps = call(helper, steps, companion, "numen.use.block(" + xyz(crafter) + ")");
        steps = call(helper, steps, companion, "numen.gui.toggle(4)");
        steps.thenWaitUntil(() -> helper.assertTrue(
                ((net.minecraft.world.inventory.CrafterMenu) companion.containerMenu).isSlotDisabled(4),
                "slot 4 of the crafter is still open")).thenExecute(
                () -> CompanionFactory.despawn(helper.getLevel().getServer(), companion)).thenSucceed();
    }

    /** 骑着驯服的马按 E:打开的是马的物品栏。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_container")
    public static void gui_open_on_a_horse_opens_the_horse_inventory(GameTestHelper helper) {
        var horse = net.minecraft.world.entity.EntityType.HORSE.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(6, 2, 6));
        horse.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        horse.setNoAi(true);
        horse.setTamed(true);
        helper.getLevel().addFreshEntity(horse);
        NumenPlayer companion = spawnAt(helper, "gametest_rider", new BlockPos(6, 2, 6), false);
        companion.startRiding(horse, true);

        Steps steps = steps(helper);
        steps = call(helper, steps, companion, "numen.gui.open()");
        steps.thenWaitUntil(() -> helper.assertTrue(
                companion.containerMenu instanceof net.minecraft.world.inventory.HorseInventoryMenu,
                "the horse's inventory did not open")).thenExecute(
                () -> CompanionFactory.despawn(helper.getLevel().getServer(), companion)).thenSucceed();
    }

    /** 她打开的界面里,她自己背包那一段第一个装着 {@code item} 的格子号(AIR = 第一个空格)——模型从 numen.gui.view 读到的就是它。 */
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
