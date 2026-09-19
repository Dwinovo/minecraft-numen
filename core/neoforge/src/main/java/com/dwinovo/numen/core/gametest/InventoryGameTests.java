package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
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
        TaskRecord record = new com.dwinovo.numen.core.tools.InventoryOps().equipItem(
                null, "minecraft:diamond_helmet", null, TaskDispatch.ctx("gametest-dress", companion));
        TaskDispatch.runSync(companion, record, reply -> {});

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
        TaskRecord record = new com.dwinovo.numen.core.tools.InventoryOps().dropItems(
                "minecraft:diamond_pickaxe", 1, TaskDispatch.ctx("gametest-courier", companion));
        TaskDispatch.runSync(companion, record, reply -> {});

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
}
