package com.dwinovo.numen.core.tools;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;
import java.util.function.Supplier;

/**
 * 读配方表的安全口。整合包里的配方什么都干得出来:空输入 {@code assemble} 返回
 * null 而不是抛异常、ingredient 列表或其 {@code getItems()} 返回 null(实测
 * ATM10 里的 gear 类配方)。判据对齐 JEI 的 {@code CategoryRecipeValidator}:
 * 枚举期只走 {@code getResultItem} 这条全生态踩实的展示路,产出的 null 与异常
 * 一律折成 EMPTY,坏一条丢一条——一条坏配方杀掉整个遍历才是事故。
 */
public final class RecipeProbe {

    private RecipeProbe() {}

    /** 展示产出——枚举配方表用它,不用空输入 assemble(那是出合同的问法)。 */
    public static ItemStack resultOf(Recipe<?> recipe, RegistryAccess registries) {   // 1.20.4:展示产出收 RegistryAccess
        return probe(() -> recipe.getResultItem(registries));
    }

    /** 探一次产出:null 与异常一律折成 EMPTY,调用方只看 {@code isEmpty()}。 */
    public static ItemStack probe(Supplier<ItemStack> supplier) {
        try {
            ItemStack result = supplier.get();
            return result == null ? ItemStack.EMPTY : result;
        } catch (RuntimeException broken) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 这条配方的输入表能不能安全交给下游:列表本身与每个 ingredient 的
     * {@code getItems()} 都不为 null。craft 的摆料、盘点、缺料描述都在这道门
     * 之后,过了门就不必层层判空。
     */
    public static boolean usableIngredients(Recipe<?> recipe) {
        try {
            List<Ingredient> ings = recipe.getIngredients();
            if (ings == null) {
                return false;
            }
            for (Ingredient ing : ings) {
                if (ing == null || ing.getItems() == null) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException broken) {
            return false;
        }
    }
}
