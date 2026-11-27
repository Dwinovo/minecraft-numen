package com.dwinovo.numen.plugins.minecraft.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import static com.dwinovo.numen.plugins.minecraft.gametest.GameTestKit.*;

/**
 * 对一个目标做一件事的那几组函数:{@code minecraft.enchant}、{@code anvil}、{@code stonecutter}、{@code loom}、{@code trade}、
 * {@code beacon}、{@code lectern}、{@code crafter}、{@code brewing}、{@code smithing}、{@code grindstone}、{@code cartography},和在
 * 工作台上合的 {@code minecraft.inv.craft}。每一条都照模型的样子从函数入口调一次:走过去、开、做、合全在那一次调用里,看世界里的结果、
 * 交回的值,和做完之后她面前没有开着的界面。
 */
public class StationGameTests {

    /** 这一批的前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_station")
    public static void prepareStationBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 附魔台:先看三项(剑放进去再拿回来,什么都不花),再照第一项附魔——剑上了附魔回到她手里,青金石扣一块、经验扣一级。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_station")
    public static void enchant_options_then_apply_brings_the_enchanted_item_back(GameTestHelper helper) {
        BlockPos table = place(helper, new BlockPos(5, 2, 4), Blocks.ENCHANTING_TABLE);
        NumenPlayer companion = spawnAt(helper, "gametest_enchanter", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
        companion.getInventory().add(new ItemStack(Items.LAPIS_LAZULI, 3));
        companion.giveExperienceLevels(30);
        ToolRun run = lua(companion, "local t = " + xyz(table) + "\nlocal o = minecraft.enchant.options(t, \"diamond_sword\")\n"
                + "local r = minecraft.enchant.apply(t, \"diamond_sword\", 1)\n"
                + "return #o.options .. \"/\" .. tostring(o.options[1].offered) .. \"/\" .. o.levels .. \"/\" .. r.item "
                + ".. \"/\" .. tostring(r.details.enchantments ~= nil)");

        succeedWhen(helper, () -> {
            helper.assertTrue(run.done(), "the program has not finished");
            helper.assertTrue(run.ranToTheEnd() && run.receipt().contains("3/true/30/minecraft:diamond_sword/true"),
                    "options and apply did not hand back the three options and the enchanted sword: " + run.receipt());
            var sword = companion.getInventory().items.stream().filter(it -> it.is(Items.DIAMOND_SWORD)).findFirst();
            helper.assertTrue(sword.isPresent() && sword.get().isEnchanted(), "she carries no enchanted sword");
            helper.assertTrue(companion.getInventory().countItem(Items.LAPIS_LAZULI) == 2
                            && companion.experienceLevel == 29,
                    "she has " + companion.getInventory().countItem(Items.LAPIS_LAZULI) + " lapis and "
                            + companion.experienceLevel + " levels, not 2 and 29");
            helper.assertTrue(companion.containerMenu == companion.inventoryMenu, "the enchanting table was left open");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 等级不够:原版什么也不做,这次调用如实失败——这一项要几级、她有几级;剑没附魔,和青金石一起回到她的背包。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_station")
    public static void enchant_apply_without_the_levels_says_what_it_asks_and_gives_all_back(GameTestHelper helper) {
        BlockPos table = place(helper, new BlockPos(5, 2, 4), Blocks.ENCHANTING_TABLE);
        NumenPlayer companion = spawnAt(helper, "gametest_poor_enchanter", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
        companion.getInventory().add(new ItemStack(Items.LAPIS_LAZULI, 3));
        ToolRun apply = lua(companion, "minecraft.enchant.apply(" + xyz(table) + ", \"diamond_sword\", 1)");

        succeedWhen(helper, () -> {
            helper.assertTrue(apply.done(), "the call has not finished");
            helper.assertTrue(!apply.succeeded() && apply.outcome().contains("experience levels")
                            && apply.outcome().contains("you have 0 levels")
                            && apply.outcome().contains("gave back 1 diamond_sword, 1 lapis_lazuli"),
                    "the refusal does not say what it asks and what came back: " + apply.outcome());
            var sword = companion.getInventory().items.stream().filter(it -> it.is(Items.DIAMOND_SWORD)).findFirst();
            helper.assertTrue(sword.isPresent() && !sword.get().isEnchanted()
                            && companion.getInventory().countItem(Items.LAPIS_LAZULI) == 3,
                    "the sword and the three lapis are not back in her inventory as they were");
            helper.assertTrue(companion.containerMenu == companion.inventoryMenu, "the enchanting table was left open");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 铁砧的两件事:给剑改名,用一块铁锭修镐。剑叫新名字、镐的损耗少了、铁锭用掉,各花一级经验(交回的值里说花了几级),界面合上。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_station")
    public static void anvil_rename_and_combine_take_the_result_and_spend_levels(GameTestHelper helper) {
        BlockPos anvil = place(helper, new BlockPos(5, 2, 4), Blocks.ANVIL);
        NumenPlayer companion = spawnAt(helper, "gametest_smith", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.IRON_SWORD));
        ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamageValue(100);
        companion.getInventory().add(worn);
        companion.getInventory().add(new ItemStack(Items.IRON_INGOT));
        companion.giveExperienceLevels(5);
        ToolRun run = lua(companion, "local a = " + xyz(anvil) + "\n"
                + "local n = minecraft.anvil.rename(a, \"iron_sword\", \"Excalibur\")\n"
                + "local c = minecraft.anvil.combine(a, \"iron_pickaxe\", \"iron_ingot\")\n"
                + "return n.item.item .. \"/\" .. n.levels .. \"/\" .. c.item.item .. \"/\" .. c.levels");

        succeedWhen(helper, () -> {
            helper.assertTrue(run.done(), "the program has not finished");
            helper.assertTrue(run.ranToTheEnd()
                            && run.receipt().contains("minecraft:iron_sword/1/minecraft:iron_pickaxe/1"),
                    "rename and combine did not hand back the two items at one level each: " + run.receipt());
            var sword = companion.getInventory().items.stream().filter(it -> it.is(Items.IRON_SWORD)).findFirst();
            helper.assertTrue(sword.isPresent() && sword.get().getHoverName().getString().equals("Excalibur"),
                    "no sword named Excalibur in her inventory");
            var pick = companion.getInventory().items.stream().filter(it -> it.is(Items.IRON_PICKAXE)).findFirst();
            helper.assertTrue(pick.isPresent() && pick.get().getDamageValue() < 100
                            && companion.getInventory().countItem(Items.IRON_INGOT) == 0,
                    "the pickaxe was not repaired with the ingot");
            helper.assertTrue(companion.experienceLevel == 3, "her level is " + companion.experienceLevel + ", not 3");
            helper.assertTrue(companion.containerMenu == companion.inventoryMenu, "the anvil was left open");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 切石机:四块石头切成石砖,按产物点名配方。四块都切了(交回的是到手的件数,不是成品格里的那一件),界面合上。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_station")
    public static void stonecutter_cut_cuts_every_item_into_the_named_result(GameTestHelper helper) {
        BlockPos cutter = place(helper, new BlockPos(5, 2, 4), Blocks.STONECUTTER);
        NumenPlayer companion = spawnAt(helper, "gametest_mason", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.STONE, 4));
        ToolRun cut = lua(companion, "return minecraft.stonecutter.cut(" + xyz(cutter) + ", \"stone\", \"stone_bricks\")");

        succeedWhen(helper, () -> {
            helper.assertTrue(cut.done(), "the program has not finished");
            helper.assertTrue(cut.ranToTheEnd() && Long.valueOf(4).equals(cut.value()),
                    "cut did not hand back 4: " + cut.receipt());
            helper.assertTrue(companion.getInventory().countItem(Items.STONE_BRICKS) == 4
                            && companion.getInventory().countItem(Items.STONE) == 0,
                    "she carries " + companion.getInventory().countItem(Items.STONE_BRICKS) + " stone bricks and "
                            + companion.getInventory().countItem(Items.STONE) + " stone");
            helper.assertTrue(companion.containerMenu == companion.inventoryMenu, "the stonecutter was left open");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 织布机:按图案的 id 往白旗上织一层红色的底条。旗帜带着这一层回到她手里,染料用掉,界面合上。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_station")
    public static void loom_weave_puts_the_named_pattern_on_the_banner(GameTestHelper helper) {
        BlockPos loom = place(helper, new BlockPos(5, 2, 4), Blocks.LOOM);
        NumenPlayer companion = spawnAt(helper, "gametest_weaver", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.WHITE_BANNER));
        companion.getInventory().add(new ItemStack(Items.RED_DYE));
        ToolRun weave = lua(companion, "local layer = minecraft.loom.weave(" + xyz(loom)
                + ", \"white_banner\", \"red_dye\", \"stripe_bottom\").details.banner.layers[1]\n"
                + "return layer.pattern .. \" \" .. layer.color");

        succeedWhen(helper, () -> {
            helper.assertTrue(weave.done(), "the program has not finished");
            helper.assertTrue(weave.ranToTheEnd() && weave.receipt().contains("minecraft:stripe_bottom red"),
                    "weave did not hand back the banner with its pattern: " + weave.receipt());
            var banner = companion.getInventory().items.stream().filter(it -> it.is(Items.WHITE_BANNER)).findFirst();
            var layers = banner.map(it -> it.getOrDefault(DataComponents.BANNER_PATTERNS,
                    net.minecraft.world.level.block.entity.BannerPatternLayers.EMPTY).layers()).orElse(java.util.List.of());
            helper.assertTrue(layers.size() == 1 && layers.get(0).pattern()
                            .is(net.minecraft.world.level.block.entity.BannerPatterns.STRIPE_BOTTOM),
                    "she carries no banner with the bottom stripe woven on it");
            helper.assertTrue(companion.getInventory().countItem(Items.RED_DYE) == 0, "the dye was not used");
            helper.assertTrue(companion.containerMenu == companion.inventoryMenu, "the loom was left open");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 和村民交易:看它的交易表,把第一笔(两颗绿宝石换六个面包)做两次。她带的绿宝石够做三次,做的正好是两次:面包十二个,绿宝石剩三颗,
     * 这一笔的已用次数是二,付款格里剩的回到背包,界面合上。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_station")
    public static void trade_buy_makes_the_trade_exactly_as_many_times_as_asked(GameTestHelper helper) {
        var villager = net.minecraft.world.entity.EntityType.VILLAGER.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(8, 2, 8));
        villager.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        villager.setNoAi(true);
        villager.setVillagerData(villager.getVillagerData()
                .setProfession(net.minecraft.world.entity.npc.VillagerProfession.FARMER));
        var offers = villager.getOffers();
        offers.clear();
        offers.add(new net.minecraft.world.item.trading.MerchantOffer(
                new net.minecraft.world.item.trading.ItemCost(Items.EMERALD, 2), new ItemStack(Items.BREAD, 6), 5, 1, 0.0f));
        helper.getLevel().addFreshEntity(villager);
        NumenPlayer companion = spawnAt(helper, "gametest_buyer", new BlockPos(6, 2, 8), false);
        // 这条测的是交易本身;右键别人的村民要不要问主人另有用例,这里让主人选"全放行"
        com.dwinovo.numen.api.permission.PermissionStore.of(companion.getServer(), companion.getOwnerUuid())
                .preset(companion.getUUID(), com.dwinovo.numen.permission.Preset.BYPASS);
        companion.getInventory().add(new ItemStack(Items.EMERALD, 7));
        ToolRun run = lua(companion, "local v = " + ref(villager) + "\nlocal o = minecraft.trade.offers(v)\n"
                + "local b = minecraft.trade.buy(v, 1, 2)\n"
                + "return #o.offers .. \"/\" .. o.offers[1].gives.item .. \"/\" .. o.offers[1].asks[1].count .. \"/\" "
                + ".. o.offers[1].max .. \"/\" .. b.trades .. \"/\" .. b.got.count");

        succeedWhen(helper, () -> {
            helper.assertTrue(run.done(), "the program has not finished");
            helper.assertTrue(run.ranToTheEnd() && run.receipt().contains("1/minecraft:bread/2/5/2/12"),
                    "offers and buy did not hand back the trade and the two purchases: " + run.receipt());
            helper.assertTrue(companion.getInventory().countItem(Items.BREAD) == 12
                            && companion.getInventory().countItem(Items.EMERALD) == 3 && offers.get(0).getUses() == 2,
                    "she holds " + companion.getInventory().countItem(Items.BREAD) + " bread and "
                            + companion.getInventory().countItem(Items.EMERALD) + " emeralds after "
                            + offers.get(0).getUses() + " uses");
            helper.assertTrue(companion.containerMenu == companion.inventoryMenu, "the trade was left open");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 信标:一层金字塔,付一块铁锭定成急迫。信标的主效果是急迫,铁锭用掉一块,界面合上。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_station")
    public static void beacon_set_pays_and_sets_the_named_effect(GameTestHelper helper) {
        BlockPos beacon = place(helper, new BlockPos(5, 2, 4), Blocks.BEACON);
        for (int x = 4; x <= 6; x++) {
            for (int z = 3; z <= 5; z++) {
                place(helper, new BlockPos(x, 1, z), Blocks.IRON_BLOCK);
            }
        }
        NumenPlayer companion = spawnAt(helper, "gametest_keeper", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
        AtomicReference<ToolRun> set = new AtomicReference<>();

        steps(helper)
                // 金字塔有几层由信标自己每 80 刻数一次:等它数出来再叫她去定
                .thenWaitUntil(() -> helper.assertTrue(beaconData(helper, beacon).getInt("Levels") == 1,
                        "the beacon has not counted its pyramid yet"))
                .thenExecute(() -> set.set(lua(companion, "local b = minecraft.beacon.set(" + xyz(beacon)
                        + ", \"iron_ingot\", \"haste\")\nreturn b.primary .. \"/\" .. b.levels")))
                .thenWaitUntil(() -> {
                    helper.assertTrue(set.get().done(), "the program has not finished");
                    helper.assertTrue(set.get().ranToTheEnd() && set.get().receipt().contains("haste/1"),
                            "set did not hand back haste on one level: " + set.get().receipt());
                    helper.assertTrue("minecraft:haste".equals(beaconData(helper, beacon).getString("primary_effect")),
                            "the beacon's primary effect is " + beaconData(helper, beacon).getString("primary_effect"));
                    helper.assertTrue(companion.getInventory().countItem(Items.IRON_INGOT) == 1,
                            "she carries " + companion.getInventory().countItem(Items.IRON_INGOT) + " ingots, not 1");
                    helper.assertTrue(companion.containerMenu == companion.inventoryMenu, "the beacon was left open");
                })
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /** 一座信标存下来的数据:层数({@code Levels})与主副效果。 */
    private static net.minecraft.nbt.CompoundTag beaconData(GameTestHelper helper, BlockPos beacon) {
        return helper.getLevel().getBlockEntity(beacon).saveWithoutMetadata(helper.getLevel().registryAccess());
    }

    /**
     * 讲台:读书(三页,翻在第一页),翻到第三页,再读一遍(翻在第三页,讲台记着),把书取下来。书进了她的背包,讲台空了,界面合上。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_station")
    public static void lectern_read_page_and_take_work_on_the_book(GameTestHelper helper) {
        BlockPos lectern = place(helper, new BlockPos(5, 2, 4), Blocks.LECTERN);
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new net.minecraft.world.item.component.WrittenBookContent(
                net.minecraft.server.network.Filterable.passThrough("Tale"), "someone", 0,
                java.util.List.of(page("one"), page("two"), page("three")), true));
        net.minecraft.world.level.block.LecternBlock.tryPlaceBook(null, helper.getLevel(), lectern,
                helper.getLevel().getBlockState(lectern), book);
        NumenPlayer companion = spawnAt(helper, "gametest_reader", new BlockPos(3, 2, 4), false);
        ToolRun run = lua(companion, "local l = " + xyz(lectern) + "\nlocal a = minecraft.lectern.read(l)\n"
                + "local p = minecraft.lectern.page(l, 3)\nlocal b = minecraft.lectern.read(l)\nlocal t = minecraft.lectern.take(l)\n"
                + "return #a.book.pages .. \"/\" .. a.page .. \"/\" .. a.book.pages[2] .. \"/\" .. p .. \"/\" .. b.page "
                + ".. \"/\" .. t.title");

        succeedWhen(helper, () -> {
            helper.assertTrue(run.done(), "the program has not finished");
            helper.assertTrue(run.ranToTheEnd() && run.receipt().contains("3/1/two/3/3/Tale"),
                    "read, page and take did not hand back the book and its pages: " + run.receipt());
            helper.assertTrue(companion.getInventory().countItem(Items.WRITTEN_BOOK) == 1, "she did not take the book");
            helper.assertTrue(!helper.getLevel().getBlockState(lectern)
                    .getValue(net.minecraft.world.level.block.LecternBlock.HAS_BOOK), "the lectern still has the book");
            helper.assertTrue(companion.containerMenu == companion.inventoryMenu, "the lectern was left open");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    private static net.minecraft.server.network.Filterable<net.minecraft.network.chat.Component> page(String text) {
        return net.minecraft.server.network.Filterable.passThrough(net.minecraft.network.chat.Component.literal(text));
    }

    /**
     * 合成器:关上中间一格(再关一次交回 false:它已经关着),往第 0 格放两块铁锭。中间那格关着,第 0 格里是两块铁锭,界面合上。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_station")
    public static void crafter_toggle_and_put_act_on_the_named_slot(GameTestHelper helper) {
        BlockPos crafter = place(helper, new BlockPos(5, 2, 4), Blocks.CRAFTER);
        NumenPlayer companion = spawnAt(helper, "gametest_crafter", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.IRON_INGOT, 5));
        ToolRun run = lua(companion, "local c = " + xyz(crafter) + "\nlocal off = minecraft.crafter.toggle(c, 4, false)\n"
                + "local again = minecraft.crafter.toggle(c, 4, false)\nlocal n = minecraft.crafter.put(c, 0, \"iron_ingot\", 2)\n"
                + "return tostring(off) .. \"/\" .. tostring(again) .. \"/\" .. n");

        succeedWhen(helper, () -> {
            helper.assertTrue(run.done(), "the program has not finished");
            helper.assertTrue(run.ranToTheEnd() && run.receipt().contains("true/false/2"),
                    "toggle and put did not hand back true, false and 2: " + run.receipt());
            var box = (net.minecraft.world.level.block.entity.CrafterBlockEntity) helper.getLevel().getBlockEntity(crafter);
            helper.assertTrue(box.isSlotDisabled(4), "slot 4 of the crafter is still on");
            helper.assertTrue(box.getItem(0).is(Items.IRON_INGOT) && box.getItem(0).getCount() == 2
                            && companion.getInventory().countItem(Items.IRON_INGOT) == 3,
                    "slot 0 holds " + box.getItem(0) + ", she carries "
                            + companion.getInventory().countItem(Items.IRON_INGOT) + " ingots");
            helper.assertTrue(companion.containerMenu == companion.inventoryMenu, "the crafter was left open");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 在点名的工作台上合:八块木板在那张台的 3x3 格里合出一口箱子(她自己的 2x2 装不下这条配方),界面合上。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_station")
    public static void craft_at_a_named_table_uses_its_grid_and_closes_it(GameTestHelper helper) {
        BlockPos table = place(helper, new BlockPos(5, 2, 4), Blocks.CRAFTING_TABLE);
        NumenPlayer companion = spawnAt(helper, "gametest_carpenter", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.OAK_PLANKS, 8));
        ToolRun craft = lua(companion, "minecraft.inv.craft(\"minecraft:chest\", {at = " + xyz(table) + "})");

        succeedWhen(helper, () -> {
            helper.assertTrue(craft.done(), "inv craft has not finished");
            helper.assertTrue(craft.succeeded() && Long.valueOf(1).equals(craft.field("crafted")),
                    "inv craft at the table did not report one chest: " + craft.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.CHEST) == 1
                            && companion.getInventory().countItem(Items.OAK_PLANKS) == 0,
                    "the inventory does not hold the chest with the planks spent");
            helper.assertTrue(companion.containerMenu == companion.inventoryMenu, "the crafting table was left open");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 酿造台的四件事各说各的格:三瓶水进三个瓶格,地狱疣进材料格,一撮烈焰粉进燃料格;不等酿完就把瓶子取出来——三瓶回到她手里,
     * 地狱疣留在材料格,烈焰粉少了一撮,界面合上。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_station")
    public static void brewing_bottles_ingredient_and_fuel_land_in_their_own_slots(GameTestHelper helper) {
        BlockPos stand = place(helper, new BlockPos(5, 2, 4), Blocks.BREWING_STAND);
        NumenPlayer companion = spawnAt(helper, "gametest_brewer", new BlockPos(3, 2, 4), false);
        for (int i = 0; i < 3; i++) {
            companion.getInventory().add(net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION,
                    net.minecraft.world.item.alchemy.Potions.WATER));
        }
        companion.getInventory().add(new ItemStack(Items.NETHER_WART));
        companion.getInventory().add(new ItemStack(Items.BLAZE_POWDER, 2));
        ToolRun run = lua(companion, "local s = " + xyz(stand) + "\nlocal b = minecraft.brewing.bottles(s, \"potion\")\n"
                + "local i = minecraft.brewing.ingredient(s, \"nether_wart\")\nlocal f = minecraft.brewing.fuel(s, 1)\n"
                + "local t = minecraft.brewing.take(s)\n"
                + "return b .. \"/\" .. i .. \"/\" .. f .. \"/\" .. #t .. \"/\" .. t[1].item .. \"/\" .. t[1].count");

        succeedWhen(helper, () -> {
            helper.assertTrue(run.done(), "the program has not finished");
            helper.assertTrue(run.ranToTheEnd() && run.receipt().contains("3/1/1/1/minecraft:potion/3"),
                    "the four calls did not hand back 3, 1, 1 and the three bottles: " + run.receipt());
            var box = (net.minecraft.world.level.block.entity.BrewingStandBlockEntity) helper.getLevel().getBlockEntity(stand);
            helper.assertTrue(box.getItem(0).isEmpty() && box.getItem(1).isEmpty() && box.getItem(2).isEmpty()
                            && box.getItem(3).is(Items.NETHER_WART),
                    "the stand holds " + box.getItem(0) + ", " + box.getItem(1) + ", " + box.getItem(2)
                            + " and " + box.getItem(3) + " as its ingredient");
            helper.assertTrue(companion.getInventory().countItem(Items.POTION) == 3
                            && companion.getInventory().countItem(Items.BLAZE_POWDER) == 1
                            && companion.getInventory().countItem(Items.NETHER_WART) == 0,
                    "she carries " + companion.getInventory().countItem(Items.POTION) + " bottles and "
                            + companion.getInventory().countItem(Items.BLAZE_POWDER) + " blaze powder");
            helper.assertTrue(companion.containerMenu == companion.inventoryMenu, "the brewing stand was left open");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 锻造台:升级模板、钻石剑、下界合金锭做出下界合金剑。剑到手,三样各用掉一件,界面合上。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_station")
    public static void smithing_forge_makes_the_item_from_its_three_inputs(GameTestHelper helper) {
        BlockPos table = place(helper, new BlockPos(5, 2, 4), Blocks.SMITHING_TABLE);
        NumenPlayer companion = spawnAt(helper, "gametest_forger", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        companion.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
        companion.getInventory().add(new ItemStack(Items.NETHERITE_INGOT));
        ToolRun forge = lua(companion, "return minecraft.smithing.forge(" + xyz(table)
                + ", \"netherite_upgrade_smithing_template\", \"diamond_sword\", \"netherite_ingot\").item");

        succeedWhen(helper, () -> {
            helper.assertTrue(forge.done(), "the program has not finished");
            helper.assertTrue(forge.ranToTheEnd() && forge.receipt().contains("minecraft:netherite_sword"),
                    "forge did not hand back a netherite sword: " + forge.receipt());
            helper.assertTrue(companion.getInventory().countItem(Items.NETHERITE_SWORD) == 1
                            && companion.getInventory().countItem(Items.DIAMOND_SWORD) == 0
                            && companion.getInventory().countItem(Items.NETHERITE_INGOT) == 0
                            && companion.getInventory().countItem(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE) == 0,
                    "she does not carry the netherite sword with the three inputs spent");
            helper.assertTrue(companion.containerMenu == companion.inventoryMenu, "the smithing table was left open");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 砂轮的两件事:磨掉一把附魔钻石剑的附魔,把两把用旧的铁镐并成一把。剑没了附魔,镐只剩一把、损耗比原来的少,界面合上。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_station")
    public static void grindstone_disenchant_and_repair_take_the_result(GameTestHelper helper) {
        BlockPos grindstone = place(helper, new BlockPos(5, 2, 4), Blocks.GRINDSTONE);
        NumenPlayer companion = spawnAt(helper, "gametest_grinder", new BlockPos(3, 2, 4), false);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS), 3);
        companion.getInventory().add(sword);
        for (int i = 0; i < 2; i++) {
            ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
            worn.setDamageValue(150);
            companion.getInventory().add(worn);
        }
        ToolRun run = lua(companion, "local g = " + xyz(grindstone) + "\n"
                + "local d = minecraft.grindstone.disenchant(g, \"diamond_sword\")\n"
                + "local r = minecraft.grindstone.repair(g, \"iron_pickaxe\", \"iron_pickaxe\")\n"
                + "return d.item .. \"/\" .. r.item .. \"/\" .. r.count");

        succeedWhen(helper, () -> {
            helper.assertTrue(run.done(), "the program has not finished");
            helper.assertTrue(run.ranToTheEnd() && run.receipt().contains("minecraft:diamond_sword/minecraft:iron_pickaxe/1"),
                    "disenchant and repair did not hand back the sword and one pickaxe: " + run.receipt());
            var plain = companion.getInventory().items.stream().filter(it -> it.is(Items.DIAMOND_SWORD)).findFirst();
            helper.assertTrue(plain.isPresent() && !plain.get().isEnchanted(), "the sword is still enchanted");
            var picks = companion.getInventory().items.stream().filter(it -> it.is(Items.IRON_PICKAXE)).toList();
            helper.assertTrue(picks.size() == 1 && picks.get(0).getDamageValue() < 150,
                    "the two pickaxes did not become one repaired pickaxe: " + picks);
            helper.assertTrue(companion.containerMenu == companion.inventoryMenu, "the grindstone was left open");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 制图台:一张地图加一张纸放大一级。她手里的地图比例尺大了一级,纸用掉,界面合上。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_station")
    public static void cartography_extend_makes_the_map_one_scale_larger(GameTestHelper helper) {
        BlockPos table = place(helper, new BlockPos(5, 2, 4), Blocks.CARTOGRAPHY_TABLE);
        NumenPlayer companion = spawnAt(helper, "gametest_mapper", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(net.minecraft.world.item.MapItem.create(helper.getLevel(), table.getX(), table.getZ(),
                (byte) 0, true, false));
        companion.getInventory().add(new ItemStack(Items.PAPER));
        ToolRun extend = lua(companion, "local m = minecraft.cartography.extend(" + xyz(table)
                + ", \"filled_map\")\nreturn m.item .. \"/\" .. m.count");

        succeedWhen(helper, () -> {
            helper.assertTrue(extend.done(), "the program has not finished");
            helper.assertTrue(extend.ranToTheEnd() && extend.receipt().contains("minecraft:filled_map/1"),
                    "extend did not hand back one map: " + extend.receipt());
            var map = companion.getInventory().items.stream().filter(it -> it.is(Items.FILLED_MAP)).findFirst();
            var data = map.map(it -> net.minecraft.world.item.MapItem.getSavedData(it, helper.getLevel())).orElse(null);
            helper.assertTrue(data != null && data.scale == 1, "her map is not at scale 1");
            helper.assertTrue(companion.getInventory().countItem(Items.PAPER) == 0, "the paper was not used");
            helper.assertTrue(companion.containerMenu == companion.inventoryMenu, "the cartography table was left open");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }
}
