package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 穿戴与背包:{@code equip_item}、{@code drop_items} 带出的物品组件、换皮回收。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class InventoryGameTests {

    /** 背包批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_inventory")
    public static void prepareInventoryBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 进食批次前置:普通难度(和平难度下饥饿值自己会回满,测不出吃下去补了多少)+ 正午。 */
    @BeforeBatch(batch = "numen_food")
    public static void prepareFoodBatch(ServerLevel level) {
        settleWorld(level, Difficulty.NORMAL, NOON);
    }

    /**
     * 穿盔甲的回执要说真话:不给 slot 的 equip_item 走原版右键换装,头盔确实到了头上,
     * 回执必须说 "in head"。曾经用含盔甲槽的总数比对来确认"离开了背包",头盔从手里挪到
     * 头上数量不变,于是判没穿上、兜底报 "holding … in main hand"——穿对了话说错了。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_inventory")
    public static void equip_armor_reply_names_the_slot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_dresser", new BlockPos(4, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.DIAMOND_HELMET));
        TaskRecord record = call(companion, "equip_item", args("item_id", "minecraft:diamond_helmet")).task();

        helper.succeedWhen(() -> {
            helper.assertTrue(companion.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
                    .is(Items.DIAMOND_HELMET), "the helmet is not on her head");
            String said = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(said != null && said.contains("in head"),
                    "the reply must say where it went, got: " + said);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 丢出去的是原物:附魔镐 drop_items 之后,地上的掉落物必须还带着那条附魔。
     * 曾经按数量销毁再按种类重造,附魔/耐久/改名全部蒸发——主人递来的神器一进一出成白板。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_inventory")
    public static void dropped_items_keep_their_components(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_courier", new BlockPos(4, 2, 4), false);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        var efficiency = level.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY);
        pick.enchant(efficiency, 3);
        companion.getInventory().add(pick);
        // 这条测的是丢出去的是不是原物;丢东西要不要问主人另有用例,这里让主人选"全放行"
        com.dwinovo.numen.permission.Permission.setMode(companion, com.dwinovo.numen.permission.Mode.BYPASS);
        TaskRecord record = call(companion, "drop_items", args(
                "item_id", "minecraft:diamond_pickaxe",
                "count", 1)).task();

        helper.succeedWhen(() -> {
            var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    companion.getBoundingBox().inflate(8));
            helper.assertTrue(!drops.isEmpty(), "nothing was dropped");
            ItemStack landed = drops.get(0).getItem();
            helper.assertTrue(landed.is(Items.DIAMOND_PICKAXE), "wrong item dropped: " + landed);
            var enchants = net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantmentsForCrafting(landed);
            helper.assertTrue(enchants.getLevel(efficiency) == 3,
                    "the enchantment did not survive the toss: " + enchants);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 换肤走的是"改注册表 + 原地回收":休眠存盘、按注册表重建之后,GameProfile 挂上
     * textures,而 UUID 与背包(经 .dat)原样回来——换的是皮,不是人。
     * ChangeSkinPayload 对活体执行的正是这一串。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_inventory")
    public static void reskin_recycle_keeps_identity_and_items(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        BlockPos spawn = helper.absolutePos(new BlockPos(4, 2, 4));
        NumenPlayer first = com.dwinovo.numen.entity.Companions.summon(server, UUID.randomUUID(),
                "gametest_reskin", level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        UUID uuid = first.getUUID();
        first.getInventory().add(new ItemStack(Items.DIAMOND));
        var reg = com.dwinovo.numen.entity.CompanionRegistry.get(server);
        reg.put(uuid, reg.find(uuid).withSkin("ZmFrZQ==", ""));
        com.dwinovo.numen.entity.Companions.dormant(server, first);
        com.dwinovo.numen.entity.Companions.respawn(server, uuid);

        helper.succeedWhen(() -> {
            NumenPlayer live = NumenPlayer.findByUuid(server, uuid);
            helper.assertTrue(live != null, "the body did not come back");
            helper.assertTrue(live.getGameProfile().getProperties().containsKey("textures"),
                    "the new skin is not on the rebuilt profile");
            helper.assertTrue(live.getInventory().hasAnyMatching(s -> s.is(Items.DIAMOND)),
                    "her inventory did not survive the recycle");
            com.dwinovo.numen.entity.Companions.dismiss(server, live);
        });
    }

    // ---- craft ----

    /** 手上的 2×2 就够:两根橡木原木合出 8 块木板,原木用光,回执说合了多少。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_inventory")
    public static void craft_planks_from_logs_in_hand(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_joiner", new BlockPos(3, 2, 3), false);
        companion.getInventory().add(new ItemStack(Items.OAK_LOG, 2));
        ToolRun craft = call(companion, "craft", args("item_id", "minecraft:oak_planks", "count", 8));

        helper.succeedWhen(() -> {
            helper.assertTrue(craft.done(), "craft has not replied");
            helper.assertTrue(craft.succeeded() && craft.outcome().contains("crafted 8x oak_planks"),
                    "craft did not report 8 planks: " + craft.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.OAK_PLANKS) == 8
                            && companion.getInventory().countItem(Items.OAK_LOG) == 0,
                    "the inventory does not hold 8 planks and no logs");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 3×3 的配方要工作台:够得着的地方没有,回执点出最近那张在哪,材料一样不动。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_inventory")
    public static void craft_3x3_without_a_table_in_reach_names_the_nearest(GameTestHelper helper) {
        BlockPos table = helper.absolutePos(new BlockPos(13, 2, 13));
        helper.getLevel().setBlockAndUpdate(table, Blocks.CRAFTING_TABLE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_seeker", new BlockPos(2, 2, 2), false);
        companion.getInventory().add(new ItemStack(Items.OAK_PLANKS, 8));
        ToolRun craft = call(companion, "craft", args("item_id", "minecraft:chest", "count", 1));

        helper.succeedWhen(() -> {
            helper.assertTrue(craft.done(), "craft has not replied");
            helper.assertTrue(!craft.succeeded() && craft.outcome().contains(
                            "Nearest one is at " + table.getX() + "," + table.getY() + "," + table.getZ()),
                    "the reply does not point at the table: " + craft.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.OAK_PLANKS) == 8,
                    "the planks were touched although nothing could be crafted");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 工作台就在手边:右键打开、摆好、取出一口箱子,木板用光,界面合上。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_inventory")
    public static void craft_3x3_at_a_table_within_reach(GameTestHelper helper) {
        helper.getLevel().setBlockAndUpdate(helper.absolutePos(new BlockPos(5, 2, 3)),
                Blocks.CRAFTING_TABLE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_carpenter", new BlockPos(3, 2, 3), false);
        companion.getInventory().add(new ItemStack(Items.OAK_PLANKS, 8));
        ToolRun craft = call(companion, "craft", args("item_id", "minecraft:chest", "count", 1));

        helper.succeedWhen(() -> {
            helper.assertTrue(craft.done(), "craft has not replied");
            helper.assertTrue(craft.succeeded(), "craft at the table failed: " + craft.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.CHEST) == 1
                            && companion.getInventory().countItem(Items.OAK_PLANKS) == 0,
                    "the inventory does not hold the chest with the planks spent");
            helper.assertTrue(companion.containerMenu == companion.inventoryMenu,
                    "the crafting table was left open");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 材料不够:回执写明缺什么,手里的一块不动。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_inventory")
    public static void craft_short_of_materials_names_the_shortfall(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_skimper", new BlockPos(3, 2, 3), false);
        companion.getInventory().add(new ItemStack(Items.OAK_PLANKS, 3));
        ToolRun craft = call(companion, "craft", args("item_id", "minecraft:chest", "count", 1));

        helper.succeedWhen(() -> {
            helper.assertTrue(craft.done(), "craft has not replied");
            helper.assertTrue(!craft.succeeded() && craft.outcome().contains("not enough materials")
                            && craft.outcome().contains("missing"),
                    "the reply does not name the shortfall: " + craft.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.OAK_PLANKS) == 3,
                    "the planks were touched although nothing could be crafted");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    // ---- eat ----

    /** 饿了吃面包:吃完才生效,饥饿值涨上去、面包少一个,回执报现在的饥饿值。 */
    @GameTest(template = "floor16", timeoutTicks = 2000, batch = "numen_food")
    public static void eat_restores_hunger(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_eater", new BlockPos(3, 2, 3), false);
        companion.getFoodData().setFoodLevel(10);
        companion.getInventory().add(new ItemStack(Items.BREAD, 2));
        ToolRun eat = call(companion, "eat", args("item_id", "minecraft:bread"));

        helper.succeedWhen(() -> {
            helper.assertTrue(eat.done(), "eat has not finished");
            helper.assertTrue(eat.succeeded() && eat.outcome().startsWith("ate bread"),
                    "eat did not report the meal: " + eat.outcome());
            helper.assertTrue(companion.getFoodData().getFoodLevel() >= 15
                            && companion.getInventory().countItem(Items.BREAD) == 1,
                    "hunger is " + companion.getFoodData().getFoodLevel() + " with "
                            + companion.getInventory().countItem(Items.BREAD) + " bread left");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 吃饱了还要吃:原版不让,回执如实说吃不下,面包还在。 */
    @GameTest(template = "floor16", timeoutTicks = 2000, batch = "numen_food")
    public static void eat_on_a_full_stomach_keeps_the_food(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_sated", new BlockPos(3, 2, 3), false);
        companion.getFoodData().setFoodLevel(20);
        companion.getInventory().add(new ItemStack(Items.BREAD, 2));
        ToolRun eat = call(companion, "eat", args("item_id", "minecraft:bread"));

        helper.succeedWhen(() -> {
            helper.assertTrue(eat.done(), "eat has not finished");
            helper.assertTrue(!eat.succeeded() && eat.outcome().contains("already full"),
                    "eating on a full stomach was not refused: " + eat.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.BREAD) == 2, "the bread was eaten");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 没有这个物品的合成配方:回执叫她去查配方,背包不动。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_inventory")
    public static void craft_something_without_a_recipe_says_so(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_dreamer", new BlockPos(3, 2, 3), false);
        companion.getInventory().add(new ItemStack(Items.OAK_PLANKS, 8));
        ToolRun craft = call(companion, "craft", args("item_id", "minecraft:bedrock", "count", 1));

        helper.succeedWhen(() -> {
            helper.assertTrue(craft.done(), "craft has not replied");
            helper.assertTrue(!craft.succeeded() && craft.outcome().contains("no crafting recipe makes"),
                    "the reply does not say there is no recipe: " + craft.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.OAK_PLANKS) == 8, "the planks were touched");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 要穿的东西背包里没有:当场失败,说出缺的是什么。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_inventory")
    public static void equip_something_not_carried_says_so(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_unarmed", new BlockPos(3, 2, 3), false);
        ToolRun equip = call(companion, "equip_item", args("action", "equip", "item_id", "minecraft:iron_helmet"));

        helper.succeedWhen(() -> {
            helper.assertTrue(equip.done(), "equip_item has not finished");
            helper.assertTrue(!equip.succeeded() && equip.outcome().contains("in inventory to equip"),
                    "the failure does not say the item is not carried: " + equip.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    // ---- equip_item:换下、卸甲、没地方放 ----

    /** 头上已经戴着铁头盔,再戴钻石头盔:钻石的上了头,铁的回到背包里,不掉在地上。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_inventory")
    public static void equip_a_second_helmet_stows_the_first(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_milliner", new BlockPos(4, 2, 4), false);
        companion.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        companion.getInventory().add(new ItemStack(Items.DIAMOND_HELMET));
        ToolRun equip = call(companion, "equip_item", args("item_id", "minecraft:diamond_helmet"));

        helper.succeedWhen(() -> {
            helper.assertTrue(equip.done(), "equip_item has not finished");
            helper.assertTrue(equip.succeeded() && equip.outcome().contains("in head"),
                    "the reply does not say the helmet went on her head: " + equip.outcome());
            helper.assertTrue(companion.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
                    .is(Items.DIAMOND_HELMET), "the diamond helmet is not on her head");
            helper.assertTrue(companion.getInventory().countItem(Items.IRON_HELMET) == 1,
                    "the iron helmet did not go back into her inventory");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 雕刻南瓜没有右键穿戴,但原版认它是头部装备:照样戴上头,回执说在头上。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_inventory")
    public static void equip_a_carved_pumpkin_goes_on_the_head(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_scarecrow", new BlockPos(4, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.CARVED_PUMPKIN));
        ToolRun equip = call(companion, "equip_item", args("item_id", "minecraft:carved_pumpkin"));

        helper.succeedWhen(() -> {
            helper.assertTrue(equip.done(), "equip_item has not finished");
            helper.assertTrue(equip.succeeded() && equip.outcome().contains("in head"),
                    "the reply does not say the pumpkin went on her head: " + equip.outcome());
            helper.assertTrue(companion.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
                    .is(Items.CARVED_PUMPKIN), "the pumpkin is not on her head");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 卸甲:头盔和靴子一起脱下来收进背包,两个槽都空了,回执点名脱了哪两件。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_inventory")
    public static void unequip_armor_takes_every_piece_off(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_undresser", new BlockPos(4, 2, 4), false);
        companion.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        companion.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        ToolRun unequip = call(companion, "equip_item", args("action", "unequip", "slot", "armor"));

        helper.succeedWhen(() -> {
            helper.assertTrue(unequip.done(), "unequip has not finished");
            helper.assertTrue(unequip.succeeded() && unequip.outcome().startsWith("took off")
                            && unequip.outcome().contains("iron_helmet") && unequip.outcome().contains("iron_boots"),
                    "the reply does not name both pieces: " + unequip.outcome());
            helper.assertTrue(companion.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty()
                            && companion.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).isEmpty(),
                    "some armor is still worn");
            helper.assertTrue(companion.getInventory().countItem(Items.IRON_HELMET) == 1
                            && companion.getInventory().countItem(Items.IRON_BOOTS) == 1,
                    "the armor did not go into her inventory");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 背包三十六格全满时卸头盔:没地方放就不脱,头盔还戴着,也不扔在地上;回执说背包满了。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_inventory")
    public static void unequip_with_a_full_inventory_keeps_the_armor_on(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_overpacked", new BlockPos(4, 2, 4), false);
        for (int i = 0; i < 36; i++) {
            companion.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        companion.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        ToolRun unequip = call(companion, "equip_item", args("action", "unequip", "slot", "head"));

        helper.succeedWhen(() -> {
            helper.assertTrue(unequip.done(), "unequip has not finished");
            helper.assertTrue(!unequip.succeeded() && unequip.outcome().contains("inventory is full"),
                    "the failure does not say the inventory is full: " + unequip.outcome());
            helper.assertTrue(companion.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
                    .is(Items.IRON_HELMET), "the helmet came off with nowhere to go");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                            companion.getBoundingBox().inflate(4)).isEmpty(), "something was dropped on the ground");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 头上本来就空着还要卸:不算错,回执说没什么可脱。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_inventory")
    public static void unequip_an_empty_slot_says_there_is_nothing(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_bareheaded", new BlockPos(4, 2, 4), false);
        ToolRun unequip = call(companion, "equip_item", args("action", "unequip", "slot", "head"));

        helper.succeedWhen(() -> {
            helper.assertTrue(unequip.done(), "unequip has not finished");
            helper.assertTrue(unequip.succeeded() && unequip.outcome().startsWith("nothing to take off"),
                    "the reply does not say there was nothing to take off: " + unequip.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 卸甲不说卸哪个槽:按参数错误退回,告诉她要给 slot。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_inventory")
    public static void unequip_without_a_slot_is_rejected(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_vague", new BlockPos(4, 2, 4), false);
        companion.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        ToolRun unequip = call(companion, "equip_item", args("action", "unequip"));

        helper.succeedWhen(() -> {
            helper.assertTrue(unequip.done(), "unequip has not replied");
            helper.assertTrue(!unequip.succeeded() && unequip.outcome().contains("slot is required"),
                    "the rejection does not ask for a slot: " + unequip.outcome());
            helper.assertTrue(companion.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
                    .is(Items.IRON_HELMET), "the helmet came off anyway");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    // ---- eat:没有、不能吃、金苹果、叫停、创造 ----

    /** 背包里没有面包还要吃:当场失败,说缺的是面包。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_food")
    public static void eat_something_not_carried_says_so(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_hungry", new BlockPos(3, 2, 3), false);
        companion.getFoodData().setFoodLevel(10);
        ToolRun eat = call(companion, "eat", args("item_id", "minecraft:bread"));

        helper.succeedWhen(() -> {
            helper.assertTrue(eat.done(), "eat has not finished");
            helper.assertTrue(!eat.succeeded() && eat.outcome().contains("no bread in inventory"),
                    "the failure does not say there is no bread: " + eat.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 要吃圆石:不是能吃能喝的东西,当场失败,圆石还在。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_food")
    public static void eat_something_inedible_says_so(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_gnawer", new BlockPos(3, 2, 3), false);
        companion.getFoodData().setFoodLevel(10);
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 3));
        ToolRun eat = call(companion, "eat", args("item_id", "minecraft:cobblestone"));

        helper.succeedWhen(() -> {
            helper.assertTrue(eat.done(), "eat has not finished");
            helper.assertTrue(!eat.succeeded() && eat.outcome().contains("can't be eaten or drunk"),
                    "the failure does not say cobblestone is not food: " + eat.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.COBBLESTONE) == 3, "the cobblestone was used up");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 金苹果饱着也能吃(原版允许):吃掉了,身上有了伤害吸收,回执说吃了。 */
    @GameTest(template = "floor16", timeoutTicks = 2000, batch = "numen_food")
    public static void eat_a_golden_apple_on_a_full_stomach(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_gourmet", new BlockPos(3, 2, 3), false);
        companion.getFoodData().setFoodLevel(20);
        companion.getInventory().add(new ItemStack(Items.GOLDEN_APPLE));
        ToolRun eat = call(companion, "eat", args("item_id", "minecraft:golden_apple"));

        helper.succeedWhen(() -> {
            helper.assertTrue(eat.done(), "eat has not finished");
            helper.assertTrue(eat.succeeded() && eat.outcome().startsWith("ate golden_apple"),
                    "the golden apple was not eaten on a full stomach: " + eat.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.GOLDEN_APPLE) == 0
                            && companion.hasEffect(net.minecraft.world.effect.MobEffects.ABSORPTION),
                    "the apple is still there or its effect is missing");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 嚼到一半主人按停止:按主人停止收场;面包一个没少,饥饿值没变,过了整段咀嚼时长也不再生效。 */
    @GameTest(template = "floor16", timeoutTicks = 2000, batch = "numen_food")
    public static void owner_stop_mid_meal_keeps_the_food(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_interrupted", new BlockPos(3, 2, 3), false);
        companion.getFoodData().setFoodLevel(10);
        companion.getInventory().add(new ItemStack(Items.BREAD, 2));
        ToolRun eat = call(companion, "eat", args("item_id", "minecraft:bread"));

        helper.startSequence()
                .thenIdle(10)
                .thenExecute(() -> com.dwinovo.numen.task.CompanionTickDispatcher.cancelFor(companion))
                .thenWaitUntil(() -> helper.assertTrue(eat.done() && eat.outcome().startsWith("the owner pressed Stop"),
                        "the meal did not end as stopped by the owner: " + eat.outcome()))
                .thenIdle(40)
                .thenWaitUntil(() -> helper.assertTrue(companion.getInventory().countItem(Items.BREAD) == 2
                                && companion.getFoodData().getFoodLevel() == 10,
                        "the stopped meal still took effect: " + companion.getInventory().countItem(Items.BREAD)
                                + " bread, hunger " + companion.getFoodData().getFoodLevel()))
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /** 创造模式没有饥饿:不去吃,回执如实说用不着,面包留着。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_food")
    public static void eat_in_creative_says_there_is_no_hunger(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_immortal", new BlockPos(3, 2, 3), true);
        companion.getInventory().add(new ItemStack(Items.BREAD, 2));
        ToolRun eat = call(companion, "eat", args("item_id", "minecraft:bread"));

        helper.succeedWhen(() -> {
            helper.assertTrue(eat.done(), "eat has not finished");
            helper.assertTrue(!eat.succeeded() && eat.outcome().contains("creative mode has no hunger"),
                    "the reply does not say creative has no hunger: " + eat.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.BREAD) == 2, "the bread was eaten");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 箱子的配方认"任意木板":四块橡木加四块白桦木板,在手边的工作台合出一口箱子,两种木板都用光。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_inventory")
    public static void craft_a_chest_from_mixed_planks(GameTestHelper helper) {
        helper.getLevel().setBlockAndUpdate(helper.absolutePos(new BlockPos(5, 2, 3)),
                Blocks.CRAFTING_TABLE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_patchworker", new BlockPos(3, 2, 3), false);
        companion.getInventory().add(new ItemStack(Items.OAK_PLANKS, 4));
        companion.getInventory().add(new ItemStack(Items.BIRCH_PLANKS, 4));
        ToolRun craft = call(companion, "craft", args("item_id", "minecraft:chest", "count", 1));

        helper.succeedWhen(() -> {
            helper.assertTrue(craft.done(), "craft has not replied");
            helper.assertTrue(craft.succeeded(), "craft from mixed planks failed: " + craft.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.CHEST) == 1
                            && companion.getInventory().countItem(Items.OAK_PLANKS) == 0
                            && companion.getInventory().countItem(Items.BIRCH_PLANKS) == 0,
                    "the chest was not made from both kinds of planks");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }
}
