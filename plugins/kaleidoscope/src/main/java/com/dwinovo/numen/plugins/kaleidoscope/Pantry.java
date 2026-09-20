package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 她背包里有什么。
 *
 * <p>{@link #find} 给回的是<b>背包里那一格的活栈</b>,不是副本:森罗的
 * {@code addIngredient} / {@code onPlaceOil} / {@code takeOutProduct} 都直接
 * {@code split(1)}、{@code shrink(1)} 传进去的那个栈,这样料是真的从她背包里少掉的,
 * 不会凭空多出一份。
 */
final class Pantry {

    private Pantry() {}

    /** 第一件满足 {@code want} 的活栈;没有就是空栈。 */
    static ItemStack find(NumenPlayer cook, Predicate<ItemStack> want) {
        Inventory inv = cook.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && want.test(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 背包里全部非空的栈(照原样,不是副本)。 */
    static List<ItemStack> all(NumenPlayer cook) {
        Inventory inv = cook.getInventory();
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                out.add(stack);
            }
        }
        return out;
    }

    /** 满足 {@code want} 的一共有几个。 */
    static int count(NumenPlayer cook, Predicate<ItemStack> want) {
        Inventory inv = cook.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && want.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
