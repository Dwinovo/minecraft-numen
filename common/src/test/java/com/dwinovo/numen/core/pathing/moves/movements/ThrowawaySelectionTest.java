package com.dwinovo.numen.core.pathing.moves.movements;

import java.lang.reflect.Field;

import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * selectThrowaway 的优先级与副手回退钉桩:
 * <ul>
 *   <li>快捷栏多种可用方块且槽位顺序与优先级不一致时,按 acceptableThrowawayItems
 *       的配置顺序选(默认泥土先于圆石先于下界岩先于石头),不按槽位顺序;</li>
 *   <li>快捷栏无可用耗材而副手有可垫路方块时,回退到副手并选一个空手/
 *       带 TOOL 组件的主手槽(剑/镐/斧/铲/锄右键不放置方块,右键走副手)。</li>
 * </ul>
 * selectThrowaway 是包级静态,本测试同包访问。需要 MC 注册表。
 */
@Tag("mc")
class ThrowawaySelectionTest {

    private static boolean booted;
    private static ServerPlayer player;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            player = allocatePlayer();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
    }

    private static ServerPlayer allocatePlayer() throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        ServerPlayer p = (ServerPlayer) unsafe.allocateInstance(ServerPlayer.class);
        Field inventory = Player.class.getDeclaredField("inventory");
        inventory.setAccessible(true);
        // 1.21.5: 盔甲/副手迁入 EntityEquipment,Inventory 与实体共享同一实例
        var equipment = new net.minecraft.world.entity.EntityEquipment();
        Field equipmentField = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("equipment");
        equipmentField.setAccessible(true);
        equipmentField.set(p, equipment);
        inventory.set(p, new Inventory(p, equipment));
        Field foodData = Player.class.getDeclaredField("foodData");
        foodData.setAccessible(true);
        foodData.set(p, new FoodData());
        return p;
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过耗材选择钉桩");
        clearHotbar();
    }

    @AfterEach
    void tearDown() {
        clearHotbar();
    }

    private static void clearHotbar() {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getNonEquipmentItems().size(); i++) {
            inv.getNonEquipmentItems().set(i, ItemStack.EMPTY);
        }
        inv.setItem(Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
        player.getInventory().setSelectedSlot(0);
    }

    @Test
    void priorityOrderBeatsSlotOrder() {
        // 0 号槽放圆石,3 号槽放泥土——优先级泥土先于圆石,应切到 3 号槽
        player.getInventory().getNonEquipmentItems().set(0, new ItemStack(Items.COBBLESTONE));
        player.getInventory().getNonEquipmentItems().set(3, new ItemStack(Items.DIRT));
        assertTrue(MovementPlacement.selectThrowaway(player, true));
        assertEquals(3, player.getInventory().getSelectedSlot(),
                "应按配置优先级选泥土(槽 3),而非槽位靠前的圆石(槽 0)");
    }

    @Test
    void firstAcceptableItemWins() {
        // 两个槽都放泥土(最高优先级),选槽位靠前者
        player.getInventory().getNonEquipmentItems().set(2, new ItemStack(Items.DIRT));
        player.getInventory().getNonEquipmentItems().set(5, new ItemStack(Items.DIRT));
        assertTrue(MovementPlacement.selectThrowaway(player, true));
        assertEquals(2, player.getInventory().getSelectedSlot());
    }

    @Test
    void offhandFallbackPicksToolOrEmptyMainHandSlot() {
        // 副手放可垫路方块;主手快捷栏只有一把石剑(带 TOOL 组件)在槽 0、空气在槽 1。
        // 应选带 TOOL 组件的槽(剑右键不放置方块,右键走副手),而非会放置方块的槽。
        player.getInventory().getNonEquipmentItems().set(0, new ItemStack(Items.STONE_SWORD));
        // 副手设置:1.21.5 副手在 EntityEquipment,经 Inventory.SLOT_OFFHAND 槽位映射写入
        player.getInventory().setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.DIRT));
        assertTrue(MovementPlacement.selectThrowaway(player, true));
        // 主手应切到槽 0(剑,带 TOOL 组件),不是 1(空手也合格但靠后)
        assertEquals(0, player.getInventory().getSelectedSlot(),
                "副手命中时主手应换到一个空手或带 TOOL 组件的槽,实为 " + player.getInventory().getSelectedSlot());
    }

    @Test
    void offhandFallbackSkipsPlaceableMainHandSlot() {
        // 副手放可垫路方块;主手快捷栏槽 0 放橡木板(会右键放置,不在耗材清单里,
        // 也不带 TOOL 组件),槽 1 放石剑(带 TOOL 组件)。快捷栏无可用耗材,
        // 走副手回退;应跳过槽 0(会放置方块),选槽 1(剑),让右键走副手。
        player.getInventory().getNonEquipmentItems().set(0, new ItemStack(Items.OAK_PLANKS));
        player.getInventory().getNonEquipmentItems().set(1, new ItemStack(Items.STONE_SWORD));
        player.getInventory().setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.DIRT));
        assertTrue(MovementPlacement.selectThrowaway(player, true));
        assertEquals(1, player.getInventory().getSelectedSlot(),
                "主手有可放置方块时应跳过它,选带 TOOL 组件的剑槽,实为 " + player.getInventory().getSelectedSlot());
    }

    @Test
    void activeBuildProviderBeatsGenericThrowaway() {
        player.getInventory().getNonEquipmentItems().set(0, new ItemStack(Items.DIRT));
        player.getInventory().getNonEquipmentItems().set(3, new ItemStack(Blocks.OBSIDIAN.asItem()));
        BuildPlacementRegistry.Provider provider = placeAt -> placeAt.equals(BlockPos.ZERO)
                ? Blocks.OBSIDIAN.defaultBlockState() : null;
        try {
            BuildPlacementRegistry.register(player, provider);
            assertTrue(MovementPlacement.selectForLocation(player, BlockPos.ZERO, true));
            assertEquals(3, player.getInventory().getSelectedSlot(),
                    "建造位置应选择请求方块,不是普通垫路耗材");
        } finally {
            BuildPlacementRegistry.unregister(player, provider);
        }
    }

    @Test
    void activeBuildProviderFallsBackToThrowawayWhenTargetBlockIsMissing() {
        player.getInventory().getNonEquipmentItems().set(0, new ItemStack(Items.DIRT));
        BuildPlacementRegistry.Provider provider = placeAt -> placeAt.equals(BlockPos.ZERO)
                ? Blocks.OBSIDIAN.defaultBlockState() : null;
        try {
            BuildPlacementRegistry.register(player, provider);
            assertTrue(MovementPlacement.selectForLocation(player, BlockPos.ZERO, true));
            assertEquals(0, player.getInventory().getSelectedSlot(),
                    "建造位置缺少请求方块时应能选择普通垫脚块继续移动");
        } finally {
            BuildPlacementRegistry.unregister(player, provider);
        }
    }
    @Test
    void noThrowawayReturnsFalse() {
        // 既无快捷栏耗材也无副手耗材
        assertFalse(MovementPlacement.selectThrowaway(player, false));
    }
}

