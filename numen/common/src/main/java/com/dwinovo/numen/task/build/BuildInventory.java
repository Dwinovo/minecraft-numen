package com.dwinovo.numen.task.build;

import com.dwinovo.numen.PlayerInv;
import com.dwinovo.numen.WorkProfile;
import com.dwinovo.numen.api.entity.NumenPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 施工的背包口径,只此一处:存量只算 36 格主背包
 * ({@link PlayerInv#BUILDABLE_SLOTS}),预检、逐格闸门与实扣同源;副手与盔甲格不算。
 */
final class BuildInventory {

    private final NumenPlayer player;

    BuildInventory(NumenPlayer player) {
        this.player = player;
    }

    private int buildableLimit(Inventory inventory) {
        return Math.min(PlayerInv.BUILDABLE_SLOTS, inventory.items.size());
    }

    /** 从背包扣掉一个该物品。 */
    void consumeOne(Item item) {
        if (WorkProfile.of(player).freeMaterials()) {
            return;   // 任务中途被切成免耗材画像:记账即刻停手,别扣真方块
        }
        Inventory inventory = player.getInventory();
        int limit = buildableLimit(inventory);
        for (int i = 0; i < limit; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                stack.shrink(1);
                return;
            }
        }
    }

    /** 够不够 {@code count} 件——双层砖那种一格吃两件的格子要问这个。 */
    boolean hasItems(Item item, int count) {
        return mainInventoryCount(item) >= count;
    }

    /** 主背包里第一格装着该物品的:快捷栏在前;没有为 -1。 */
    int findSlot(Item item) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                return i;
            }
        }
        return findMainInventorySlot(item);
    }

    int mainInventoryCount(Item item) {
        return PlayerInv.buildableCount(player.getInventory(), item);
    }

    private int findMainInventorySlot(Item item) {
        Inventory inventory = player.getInventory();
        int limit = buildableLimit(inventory);
        for (int i = 9; i < limit; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                return i;
            }
        }
        return -1;
    }
}
