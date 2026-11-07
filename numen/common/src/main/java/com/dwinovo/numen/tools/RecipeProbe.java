package com.dwinovo.numen.tools;

import net.minecraft.core.HolderLookup;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.List;
import java.util.function.Supplier;

/**
 * 读配方表的安全口。整合包里的配方什么都干得出来:空输入 {@code assemble} 返回
 * null 而不是抛异常、{@code placementInfo} 或其 ingredient 的 {@code items()} 返回 null(实测
 * ATM10 里的 gear 类配方)。判据对齐 JEI 的 {@code CategoryRecipeValidator}:
 * 枚举期只走展示路——1.21.2 起是配方的 {@code display()}(原版配方书也读它),不用空输入
 * {@code assemble}(那是出合同的问法);产出的 null 与异常一律折成 EMPTY,坏一条丢一条——一条坏配方杀掉整个遍历才是事故。
 */
public final class RecipeProbe {

    private RecipeProbe() {}

    /** 展示产出:配方展示里产出那一格解出的第一叠——枚举配方表用它。 */
    public static ItemStack resultOf(Recipe<?> recipe, HolderLookup.Provider registries) {
        ContextMap context = new ContextMap.Builder().withParameter(SlotDisplayContext.REGISTRIES, registries)
                .create(SlotDisplayContext.CONTEXT);
        return probe(() -> recipe.display().stream().map(display -> display.result().resolveForFirstStack(context))
                .filter(stack -> !stack.isEmpty()).findFirst().orElse(ItemStack.EMPTY));
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
     * 这条配方的输入表能不能安全交给下游:{@code placementInfo} 本身、它的清单与每个 ingredient 的
     * {@code items()} 都不为 null。craft 的摆料、盘点、缺料描述都在这道门之后,过了门就不必层层判空。
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
