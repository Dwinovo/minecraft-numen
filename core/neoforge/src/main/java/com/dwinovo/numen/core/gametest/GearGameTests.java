package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.gear.GearSlot;
import com.dwinovo.numen.api.gear.GearSource;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * 穿戴扩展点:测试经 {@code NumenApi.registerGear} 那扇门登记一处假的穿戴来源(两格 {@code gametest:ring}、
 * 一格封死的 {@code gametest:charm}),只对本测试的同伴生效。{@code gear wear} / {@code gear remove} 的自动选位、被拒、缺槽、
 * 槽名写错、卸下、满包、摘不下,以及 {@code <worn>},都经这一处来源走和原版同一条路。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class GearGameTests {

    /** 穿戴批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_gear")
    public static void prepareGearBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 假来源里的一格。{@code refuseWear} / {@code refuseRemove} 非空就照这句话拒绝。 */
    static final class FakeSlot implements GearSlot {
        final String name;
        final String refuseWear;
        String refuseRemove;
        ItemStack worn = ItemStack.EMPTY;

        FakeSlot(String name, String refuseWear) {
            this.name = name;
            this.refuseWear = refuseWear;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ItemStack worn() {
            return worn;
        }

        @Override
        public Optional<String> refuseWear(ItemStack one) {
            return Optional.ofNullable(refuseWear);
        }

        @Override
        public Optional<String> refuseRemove() {
            return worn.isEmpty() ? Optional.empty() : Optional.ofNullable(refuseRemove);
        }

        @Override
        public ItemStack swap(ItemStack in) {
            ItemStack old = worn;
            worn = in;
            return old;
        }
    }

    /**
     * 只对一只同伴说话的假来源:紫水晶碎片归 {@code gametest:ring},绿宝石归 {@code gametest:charm}(那格封死),
     * 钻石归 {@code gametest:belt}(她身上没有这类格)。
     */
    record FakeGear(UUID self, List<FakeSlot> slots) implements GearSource {
        static final Map<Item, Set<String>> KINDS = Map.of(
                Items.AMETHYST_SHARD, Set.of("gametest:ring"),
                Items.EMERALD, Set.of("gametest:charm"),
                Items.DIAMOND, Set.of("gametest:belt"));

        @Override
        public List<GearSlot> slots(NumenPlayer body) {
            return body.getUUID().equals(self) ? List.copyOf(slots) : List.of();
        }

        @Override
        public Set<String> kindsOf(NumenPlayer body, ItemStack stack) {
            return body.getUUID().equals(self) ? KINDS.getOrDefault(stack.getItem(), Set.of()) : Set.of();
        }

        FakeSlot ring(int i) {
            return slots.get(i);
        }

        FakeSlot charm() {
            return slots.get(2);
        }
    }

    /** 拉起一只同伴,经那扇门给她登记一处假来源。 */
    static FakeGear dress(NumenPlayer companion) {
        FakeGear gear = new FakeGear(companion.getUUID(), List.of(
                new FakeSlot("gametest:ring", null),
                new FakeSlot("gametest:ring", null),
                new FakeSlot("gametest:charm", "the charm slot is sealed")));
        NumenPlugins.register(numen -> numen.registerGear(gear));
        return gear;
    }

    /** 不给 --slot:紫水晶碎片归 gametest:ring,进第一格空的,回执写出槽名,背包里少了它。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_gear")
    public static void gear_auto_choice_names_the_slot(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_ringbearer", new BlockPos(4, 2, 4), false);
        FakeGear gear = dress(companion);
        companion.getInventory().add(new ItemStack(Items.AMETHYST_SHARD));
        ToolRun equip = command(companion, "gear wear minecraft:amethyst_shard");

        helper.succeedWhen(() -> {
            helper.assertTrue(equip.done(), "gear wear has not finished");
            helper.assertTrue(equip.succeeded() && equip.outcome().contains("gametest:ring"),
                    "the reply does not name the ring slot: " + equip.outcome());
            helper.assertTrue(gear.ring(0).worn.is(Items.AMETHYST_SHARD), "the shard is not in the first ring slot");
            helper.assertTrue(companion.getInventory().countItem(Items.AMETHYST_SHARD) == 0,
                    "the shard is still in her backpack");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 认领了却被拒:绿宝石归封死的 charm 格,如实失败说出原因,主手不变、背包没少。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_gear")
    public static void gear_claimed_but_refused_changes_nothing(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_sealed", new BlockPos(4, 2, 4), false);
        FakeGear gear = dress(companion);
        companion.getInventory().add(new ItemStack(Items.EMERALD));
        companion.getInventory().selected = 5;   // 主手是空的那格,绿宝石在第 0 格
        ToolRun equip = command(companion, "gear wear minecraft:emerald");

        helper.succeedWhen(() -> {
            helper.assertTrue(equip.done(), "gear wear has not finished");
            helper.assertTrue(!equip.succeeded() && equip.outcome().contains("sealed"),
                    "the failure does not carry the slot's reason: " + equip.outcome());
            helper.assertTrue(companion.getMainHandItem().isEmpty(), "her main hand changed");
            helper.assertTrue(companion.getInventory().countItem(Items.EMERALD) == 1, "the emerald left her backpack");
            helper.assertTrue(gear.charm().worn.isEmpty(), "the sealed slot took it anyway");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 认领了但她身上没有这类格:钻石归 gametest:belt,如实失败说出缺哪类,不冒充拿在手上。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_gear")
    public static void gear_claimed_without_such_a_slot_fails(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_beltless", new BlockPos(4, 2, 4), false);
        dress(companion);
        companion.getInventory().add(new ItemStack(Items.DIAMOND));
        companion.getInventory().selected = 5;
        ToolRun equip = command(companion, "gear wear minecraft:diamond");

        helper.succeedWhen(() -> {
            helper.assertTrue(equip.done(), "gear wear has not finished");
            helper.assertTrue(!equip.succeeded() && equip.outcome().contains("gametest:belt"),
                    "the failure does not name the missing kind of slot: " + equip.outcome());
            helper.assertTrue(companion.getMainHandItem().isEmpty(), "the diamond went into her hand instead");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 槽名写错:失败,并列出她实际有的槽(手、原版甲、假来源的)。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_gear")
    public static void gear_unknown_slot_name_lists_her_slots(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_crownless", new BlockPos(4, 2, 4), false);
        dress(companion);
        companion.getInventory().add(new ItemStack(Items.AMETHYST_SHARD));
        ToolRun equip = command(companion, "gear wear minecraft:amethyst_shard --slot gametest:crown");

        helper.succeedWhen(() -> {
            helper.assertTrue(equip.done(), "gear wear has not finished");
            String said = equip.outcome();
            helper.assertTrue(!equip.succeeded() && said.contains("gametest:crown") && said.contains("mainhand")
                            && said.contains("head") && said.contains("gametest:ring")
                            && said.contains("gametest:charm"),
                    "the failure does not list the slots she has: " + said);
            helper.assertTrue(companion.getInventory().countItem(Items.AMETHYST_SHARD) == 1, "the shard moved anyway");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 按槽名卸下:两格 ring 都戴着,gear remove --slot gametest:ring 两件都回背包。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_gear")
    public static void gear_unequip_by_slot_name(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_unringed", new BlockPos(4, 2, 4), false);
        FakeGear gear = dress(companion);
        gear.ring(0).worn = new ItemStack(Items.AMETHYST_SHARD);
        gear.ring(1).worn = new ItemStack(Items.IRON_NUGGET);
        ToolRun unequip = command(companion, "gear remove --slot gametest:ring");

        helper.succeedWhen(() -> {
            helper.assertTrue(unequip.done(), "gear remove has not finished");
            helper.assertTrue(unequip.succeeded() && unequip.outcome().contains("amethyst_shard (gametest:ring)")
                            && unequip.outcome().contains("iron_nugget (gametest:ring)"),
                    "the reply does not name both pieces: " + unequip.outcome());
            helper.assertTrue(gear.ring(0).worn.isEmpty() && gear.ring(1).worn.isEmpty(), "a ring slot is still worn");
            helper.assertTrue(companion.getInventory().countItem(Items.AMETHYST_SHARD) == 1
                    && companion.getInventory().countItem(Items.IRON_NUGGET) == 1, "the pieces did not come back");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 按物品卸下:不给 --slot,gear remove --item 紫水晶碎片,只从戴着它的那格摘,另一格不动。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_gear")
    public static void gear_unequip_by_item(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_pickyring", new BlockPos(4, 2, 4), false);
        FakeGear gear = dress(companion);
        gear.ring(0).worn = new ItemStack(Items.IRON_NUGGET);
        gear.ring(1).worn = new ItemStack(Items.AMETHYST_SHARD);
        ToolRun unequip = command(companion, "gear remove --item minecraft:amethyst_shard");

        helper.succeedWhen(() -> {
            helper.assertTrue(unequip.done(), "gear remove has not finished");
            helper.assertTrue(unequip.succeeded() && unequip.outcome().contains("amethyst_shard (gametest:ring)"),
                    "the reply does not name the shard: " + unequip.outcome());
            helper.assertTrue(gear.ring(1).worn.isEmpty(), "the amethyst shard is still worn");
            helper.assertTrue(gear.ring(0).worn.is(Items.IRON_NUGGET), "the other ring slot was touched");
            helper.assertTrue(companion.getInventory().countItem(Items.AMETHYST_SHARD) == 1, "the shard did not come back");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 背包三十六格全满时卸饰品:不摘,还戴着,地上没东西;回执说背包满了。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_gear")
    public static void gear_unequip_with_a_full_inventory_keeps_it_on(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_crammed", new BlockPos(4, 2, 4), false);
        FakeGear gear = dress(companion);
        for (int i = 0; i < 36; i++) {
            companion.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        gear.ring(0).worn = new ItemStack(Items.AMETHYST_SHARD);
        ToolRun unequip = command(companion, "gear remove --slot gametest:ring");

        helper.succeedWhen(() -> {
            helper.assertTrue(unequip.done(), "gear remove has not finished");
            helper.assertTrue(!unequip.succeeded() && unequip.outcome().contains("inventory is full"),
                    "the failure does not say the inventory is full: " + unequip.outcome());
            helper.assertTrue(gear.ring(0).worn.is(Items.AMETHYST_SHARD), "the ring came off with nowhere to go");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    companion.getBoundingBox().inflate(4)).isEmpty(), "something was dropped on the ground");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 这格说摘不下:如实失败,原话带回来,东西还戴着。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_gear")
    public static void gear_that_refuses_to_come_off_stays_on(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_stuckring", new BlockPos(4, 2, 4), false);
        FakeGear gear = dress(companion);
        gear.ring(0).worn = new ItemStack(Items.AMETHYST_SHARD);
        gear.ring(0).refuseRemove = "the ring is stuck fast";
        ToolRun unequip = command(companion, "gear remove --slot gametest:ring");

        helper.succeedWhen(() -> {
            helper.assertTrue(unequip.done(), "gear remove has not finished");
            helper.assertTrue(!unequip.succeeded() && unequip.outcome().contains("stuck fast"),
                    "the failure does not carry the slot's reason: " + unequip.outcome());
            helper.assertTrue(gear.ring(0).worn.is(Items.AMETHYST_SHARD), "the stuck ring came off");
            helper.assertTrue(companion.getInventory().countItem(Items.AMETHYST_SHARD) == 0,
                    "the stuck ring was copied into her backpack");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * {@code <worn>} 排在身体状态最前面:原版四件甲与假来源的格子都列出,空位写 empty,同名多格逗号并列;
     * {@code get_self_status} 的 equipment 只剩两只手。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_gear")
    public static void body_state_lists_every_worn_slot(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mirror", new BlockPos(4, 2, 4), false);
        FakeGear gear = dress(companion);
        gear.ring(0).worn = new ItemStack(Items.AMETHYST_SHARD);
        companion.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        ToolRun status = call(companion, "get_self_status", args());

        helper.succeedWhen(() -> {
            helper.assertTrue(status.reply() != null, "get_self_status has not replied");
            var s = com.google.gson.JsonParser.parseString(status.reply()).getAsJsonObject();
            String body = s.get("body_state").getAsString();
            // 原版最先登记,排在最前;别的来源(装了的饰品模组)可能夹在中间,所以假来源那段只看在不在 <worn> 里
            helper.assertTrue(body.startsWith("<worn>head: minecraft:iron_helmet; chest: empty; legs: empty; "
                            + "feet: empty; ") && body.contains("; gametest:ring: minecraft:amethyst_shard, empty; "
                            + "gametest:charm: empty</worn>"),
                    "body_state does not open with the worn slots: " + body);
            helper.assertTrue(!s.getAsJsonObject("equipment").has("head"),
                    "equipment still reports armor beside <worn>: " + status.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 遣散像死亡一样全掉在脚下:背包里的、原版的头盔,和不在原版物品栏里的那处来源上戴着的,都落地各一件,
     * 身上不再戴着。只丢原版物品栏的话,模组的饰品会跟着身体一起消失。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_gear")
    public static void dismiss_drops_what_she_wears(GameTestHelper helper) {
        dismissDropsEverything(helper, "gametest_farewell", "gametest_bereaved",
                (owner, companion) -> com.dwinovo.numen.network.payload.DismissRequestPayload.handle(
                        new com.dwinovo.numen.network.payload.DismissRequestPayload(companion.getUUID()), owner));
    }

    /** 主人敲 {@code /numen player despawn} 和在面板上点 ✕ 是同一次遣散,掉的东西一样。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_gear")
    public static void despawn_command_drops_what_she_carries_and_wears(GameTestHelper helper) {
        dismissDropsEverything(helper, "gametest_parting", "gametest_mourner",
                (owner, companion) -> owner.getServer().getCommands().performPrefixedCommand(
                        owner.createCommandSourceStack(), "numen player despawn " + companion.getGameProfile().getName()));
    }

    private static void dismissDropsEverything(GameTestHelper helper, String name, String ownerName,
            java.util.function.BiConsumer<net.minecraft.server.level.ServerPlayer, NumenPlayer> dismiss) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, name, new BlockPos(4, 2, 4), false);
        var owner = presentPlayer(helper, companion, ownerName);
        FakeGear gear = dress(companion);
        gear.ring(1).worn = new ItemStack(Items.AMETHYST_SHARD);
        companion.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        companion.getInventory().add(new ItemStack(Items.DIAMOND));
        net.minecraft.world.phys.AABB around = companion.getBoundingBox().inflate(4);
        dismiss.accept(owner, companion);

        helper.succeedWhen(() -> {
            var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, around);
            int shards = drops.stream().filter(e -> e.getItem().is(Items.AMETHYST_SHARD))
                    .mapToInt(e -> e.getItem().getCount()).sum();
            int helmets = drops.stream().filter(e -> e.getItem().is(Items.IRON_HELMET))
                    .mapToInt(e -> e.getItem().getCount()).sum();
            int diamonds = drops.stream().filter(e -> e.getItem().is(Items.DIAMOND))
                    .mapToInt(e -> e.getItem().getCount()).sum();
            helper.assertTrue(shards == 1, "the worn shard did not drop exactly once: " + shards);
            helper.assertTrue(helmets == 1, "the helmet did not drop exactly once: " + helmets);
            helper.assertTrue(diamonds == 1, "the carried diamond did not drop exactly once: " + diamonds);
            helper.assertTrue(gear.ring(1).worn.isEmpty(), "the shard is still worn by a dismissed body");
            helper.assertTrue(NumenPlayer.findByUuid(level.getServer(), companion.getUUID()) == null,
                    "the dismissed body is still in the world");
            drops.forEach(net.minecraft.world.entity.Entity::discard);
            leave(owner);
        });
    }
}
