package com.dwinovo.numen.core.tools;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;

import java.util.List;
import java.util.function.Supplier;

/**
 * 读配方表的安全口。整合包里的配方什么都干得出来:空输入 {@code assemble} 返回
 * null 而不是抛异常、{@code placementInfo} 或其 ingredient 的 {@code items()} 返回
 * null(实测 ATM10 里的 gear 类配方)。1.21.2+ 删掉了展示产出口(getResultItem),
 * 枚举期改用空输入 {@code assemble} 问产出——shaped/shapeless/熔炼/切石都无视输入;
 * 产出的 null 与异常一律折成 EMPTY,坏一条丢一条——一条坏配方杀掉整个遍历才是事故。
 */
public final class RecipeProbe {

    private RecipeProbe() {}

    /** 枚举合成配方的产出——空输入 assemble(shaped/shapeless 不看输入)。 */
    public static ItemStack resultOf(CraftingRecipe recipe, HolderLookup.Provider registries) {
        return probe(() -> recipe.assemble(CraftingInput.EMPTY, registries));
    }

    /** 枚举单输入配方(熔炼/切石)的产出——空输入 assemble(它们同样不看输入)。 */
    public static ItemStack resultOf(Recipe<SingleRecipeInput> recipe, HolderLookup.Provider registries) {
        return probe(() -> recipe.assemble(new SingleRecipeInput(ItemStack.EMPTY), registries));
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
     * 这条配方的输入表能不能安全交给下游:{@code placementInfo} 本身、它的清单与
     * 每个 ingredient 的 {@code items()} 都不为 null。craft 的摆料、盘点、缺料描述
     * 都在这道门之后,过了门就不必层层判空。
     */
    public static boolean usableIngredients(Recipe<?> recipe) {
        try {
            PlacementInfo info = recipe.placementInfo();
            List<Ingredient> ings = info == null ? null : info.ingredients();
            if (ings == null) {
                return false;
            }
            for (Ingredient ing : ings) {
                if (ing == null || ing.items() == null) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException broken) {
            return false;
        }
    }
}
