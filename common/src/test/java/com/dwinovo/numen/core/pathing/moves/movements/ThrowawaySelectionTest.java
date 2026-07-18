package com.dwinovo.numen.core.pathing.moves.movements;

import java.lang.reflect.Field;

import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
 *       非工具主手槽(避免主手物品右键消费抢走副手放置)。</li>
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
        inventory.set(p, new Inventory(p));
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
        for (int i = 0; i < inv.items.size(); i++) {
            inv.items.set(i, ItemStack.EMPTY);
        }
        player.getInventory().selected = 0;
    }

    @Test
    void priorityOrderBeatsSlotOrder() {
        // 0 号槽放圆石,3 号槽放泥土——优先级泥土先于圆石,应切到 3 号槽
        player.getInventory().items.set(0, new ItemStack(Items.COBBLESTONE));
        player.getInventory().items.set(3, new ItemStack(Items.DIRT));
        assertTrue(MovementPlacement.selectThrowaway(player, true));
        assertEquals(3, player.getInventory().selected,
                "应按配置优先级选泥土(槽 3),而非槽位靠前的圆石(槽 0)");
    }

    @Test
    void firstAcceptableItemWins() {
        // 两个槽都放泥土(最高优先级),选槽位靠前者
        player.getInventory().items.set(2, new ItemStack(Items.DIRT));
        player.getInventory().items.set(5, new ItemStack(Items.DIRT));
        assertTrue(MovementPlacement.selectThrowaway(player, true));
        assertEquals(2, player.getInventory().selected);
    }

    @Test
    void offhandFallbackPicksNonToolMainHandSlot() {
        // 副手放可垫路方块;主手是剑(工具),应换到一个非工具槽以便右键走副手。
        // 这里没有可垫路方块在快捷栏,只有一把石剑在槽 0、空气在槽 1。
        player.getInventory().items.set(0, new ItemStack(Items.STONE_SWORD));
        // 副手设置:ServerPlayer.getOffhandItem() 读 Inventory.offhand
        player.getInventory().offhand.set(0, new ItemStack(Items.DIRT));
        assertTrue(MovementPlacement.selectThrowaway(player, true));
        // 主手应切到槽 1(空手,非工具),不是 0(剑)
        assertEquals(1, player.getInventory().selected,
                "副手命中时主手应换到一个非工具/空手槽,实为 " + player.getInventory().selected);
    }

    @Test
    void noThrowawayReturnsFalse() {
        // 既无快捷栏耗材也无副手耗材
        assertFalse(MovementPlacement.selectThrowaway(player, false));
    }
}