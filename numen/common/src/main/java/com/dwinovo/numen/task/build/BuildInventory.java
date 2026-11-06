package com.dwinovo.numen.task.build;

import com.dwinovo.numen.PlayerInv;
import com.dwinovo.numen.WorkProfile;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.entity.Stacks;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 施工的背包口径,只此一处:存量只算 36 格主背包
 * ({@link PlayerInv#BUILDABLE_SLOTS}),预检、逐格闸门与实扣同源;副手与盔甲格不算。
 */
final class BuildInventory {

    private final NumenPlayer player;

    BuildInventory(NumenPlayer player) {
        this.player = player;
    }

    /** 要放的这一种:放哪一叠只由 {@link Stacks#pick} 定;一件都没有为空。 */
    Optional<Stacks.Pick> pick(Item item) {
        return Stacks.pick(player.getInventory(), item);
    }

    /**
     * 从 {@code pick} 选中的那一种里扣 {@code count} 件(一格不止一件料时,这一种不够再按 {@link Stacks#pick} 的次序往下一种扣),
     * 交回扣掉的每一叠的样子与件数,回执据此说花了哪几叠。
     */
    List<ItemStack> consume(Stacks.Pick pick, int count) {
        List<ItemStack> spent = new ArrayList<>();
        if (WorkProfile.of(player).freeMaterials()) {
            return spent;   // 任务中途被切成免耗材画像:记账即刻停手,别扣真方块
        }
        Inventory inventory = player.getInventory();
        int left = count;
        Optional<Stacks.Pick> current = Optional.of(pick);
        while (left > 0 && current.isPresent()) {
            Stacks.Pick kind = current.get();
            for (int slot : kind.slots()) {
                ItemStack stack = inventory.getItem(slot);
                int n = Math.min(left, stack.getCount());
                if (n > 0) {
                    stack.shrink(n);
                    left -= n;
                    spent.add(kind.sample().copyWithCount(n));
                }
            }
            current = left > 0 ? Stacks.pick(inventory, kind.sample().getItem()) : Optional.empty();
        }
        return spent;
    }

    /** 够不够 {@code count} 件——双层砖那种一格吃两件的格子要问这个。 */
    boolean hasItems(Item item, int count) {
        return mainInventoryCount(item) >= count;
    }

    int mainInventoryCount(Item item) {
        return PlayerInv.buildableCount(player.getInventory(), item);
    }
}
