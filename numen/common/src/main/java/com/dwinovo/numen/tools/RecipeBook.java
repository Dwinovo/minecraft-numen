package com.dwinovo.numen.tools;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.Doc;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * 配方书:原版配方表里每一种配方类型的配方,像 JEI 的一页({@code numen.inv.recipes}、{@code numen.inv.uses})。读法只靠原版的
 * {@link Recipe} 接口,所以任何模组的配方类型都读得到:认识的几种(合成、烧炼、切石、锻造)有完整描述,其余给出类型、输入与产物。
 * 合成配方的事实(装得下的最小的格、照她背着的还缺什么)与合成时查的同一处读({@link CraftOps})。
 */
public final class RecipeBook {

    /** 一次交回的配方条数上限:答复要装进脚本与大模型的上下文;超出的条数在 {@link Found#omitted} 里如实说。 */
    public static final int MAX_RECIPES = 40;

    /** "any[...]" 一类料最多把几种东西列出来,其余记一个数。 */
    private static final int MAX_LISTED_ITEMS = 8;

    private RecipeBook() {}

    /** 一次查询的结果。 */
    @Doc("The recipes that match, and how many more matched beyond what is listed.")
    public record Found(@Doc("The recipes: crafting, cooking, stonecutting and smithing first, in full; then every other "
            + "type with its type id, inputs and product.") List<com.dwinovo.numen.tools.Recipe> recipes,
                        @Doc("How many more recipes matched but are not listed; absent when all are listed.")
                        Optional<Integer> omitted) {}

    /** 做这样东西的每一条配方,每个类别;找不到就是空表(它靠挖或交易得来,不是做出来的)。 */
    public static Found recipesFor(Item target, NumenPlayer self) {
        return collect(self, (recipe, result) -> result.getItem() == target);
    }

    /** 拿这样东西当输入的每一条配方:标签展开后的输入里有它(锻造看三个槽认不认它)。 */
    public static Found usesOf(Item input, NumenPlayer self) {
        ItemStack probe = new ItemStack(input);
        return collect(self, (recipe, result) -> takes(recipe, probe));
    }

    /** 配方类型在注册表里的名字,即 {@link com.dwinovo.numen.tools.Recipe#category}。 */
    public static String categoryOf(Recipe<?> recipe) {
        return BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString();
    }

    /**
     * 在合成格里做的配方:类型是原版的合成。模组机器的配方也可以实现 {@code CraftingRecipe}(类型是它自己的),那不是在合成格里做的,
     * 照别的类型只给类型、输入与产物。
     */
    private static boolean isCrafting(Recipe<?> recipe) {
        return recipe instanceof CraftingRecipe && recipe.getType() == RecipeType.CRAFTING;
    }

    static boolean takes(Recipe<?> recipe, ItemStack stack) {
        if (recipe instanceof SmithingRecipe sm) {
            return sm.isTemplateIngredient(stack) || sm.isBaseIngredient(stack) || sm.isAdditionIngredient(stack);
        }
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.test(stack)) {
                return true;
            }
        }
        return false;
    }

    private static Found collect(NumenPlayer self, BiPredicate<Recipe<?>, ItemStack> wanted) {
        ServerLevel level = self.serverLevel();
        return collect(level.getRecipeManager().getRecipes(), level.registryAccess(), wanted,
                (holder, recipe, result) -> CraftOps.recipe(holder, recipe, result, self));
    }

    /** 合成配方的那一行:装得下的格、还缺什么要看她背着什么,所以由调用方给。 */
    interface CraftingRow {
        com.dwinovo.numen.tools.Recipe of(RecipeHolder<?> holder, CraftingRecipe recipe, ItemStack result);
    }

    /** 从这些配方里挑出 {@code wanted} 要的,每条描述成一行;一条坏配方只丢它自己。 */
    static Found collect(Iterable<? extends RecipeHolder<?>> holders, HolderLookup.Provider registries,
                         BiPredicate<Recipe<?>, ItemStack> wanted, CraftingRow crafting) {
        List<com.dwinovo.numen.tools.Recipe> described = new ArrayList<>();
        List<com.dwinovo.numen.tools.Recipe> skeletons = new ArrayList<>();
        for (RecipeHolder<?> holder : holders) {
            // 每条配方自成一格:整合包里一条坏配方(产出为 null、输入表为 null)只丢它自己,绝不让它杀掉整个查询。见 RecipeProbe。
            try {
                Recipe<?> r = holder.value();
                ItemStack result = RecipeProbe.resultOf(r, registries);
                // 合成里产出依赖输入的配方(烟花、镶零件的装备)静态描述答不了;锻造配方在原版里一律标 special,不是这个意思
                if ((isCrafting(r) && r.isSpecial()) || result.isEmpty()
                        || !RecipeProbe.usableIngredients(r) || !wanted.test(r, result)) {
                    continue;
                }
                if (r instanceof CraftingRecipe cr && isCrafting(r)) {
                    // 1.21.1 没有 PlacementInfo,空输入表的老启发式保留
                    if (cr.getIngredients().stream().allMatch(Ingredient::isEmpty)) {
                        continue;
                    }
                    described.add(crafting.of(holder, cr, result));
                } else if (r instanceof AbstractCookingRecipe cook) {
                    described.add(row(holder, r, result, List.of(describeIngredient(cook.getIngredients().get(0))),
                            Optional.of(cook.getCookingTime())));
                } else if (r instanceof StonecutterRecipe sc) {
                    described.add(row(holder, r, result, List.of(describeIngredient(sc.getIngredients().get(0))),
                            Optional.empty()));
                } else if (r instanceof SmithingRecipe) {
                    described.add(row(holder, r, result, List.of(), Optional.empty()));
                } else {
                    skeletons.add(row(holder, r, result, r.getIngredients().stream().filter(i -> !i.isEmpty())
                            .map(RecipeBook::describeIngredient).toList(), Optional.empty()));
                }
            } catch (RuntimeException broken) {
                com.dwinovo.numen.Constants.LOG.debug(
                        "[numen-recipe] 配方 {} 坏了,跳过: {}", holder.id(), broken.toString());
            }
        }
        described.addAll(skeletons);
        int omitted = Math.max(0, described.size() - MAX_RECIPES);
        return new Found(described.subList(0, described.size() - omitted),
                omitted == 0 ? Optional.empty() : Optional.of(omitted));
    }

    /** 不在合成格里做的一条配方(合成配方的那一行由 {@link CraftOps#recipe} 出)。 */
    private static com.dwinovo.numen.tools.Recipe row(RecipeHolder<?> holder, Recipe<?> recipe, ItemStack result,
                                                          List<String> ingredients, Optional<Integer> ticks) {
        return new com.dwinovo.numen.tools.Recipe(holder.id().toString(), categoryOf(recipe),
                BuiltInRegistries.ITEM.getKey(result.getItem()).toString(), result.getCount(), ingredients,
                Optional.empty(), Optional.empty(), ticks);
    }

    /**
     * 一样料的叫法:单一物品就是它;一类有共同后缀的写成 {@code planks(any)};不然把每一种都列出来(超过 {@link #MAX_LISTED_ITEMS}
     * 种只列前几种、记下还有几种)——免得一类料把模型引到某一种具体的东西上。合成缺料时说缺什么用的是同一套叫法。
     */
    static String describeIngredient(Ingredient ing) {
        List<String> paths = java.util.Arrays.stream(ing.getItems())   // 1.21.1: getItems() -> ItemStack[]
                .map(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath())
                .distinct()
                .toList();
        if (paths.isEmpty()) {
            return "?";
        }
        if (paths.size() == 1) {
            return paths.get(0);
        }
        String suffix = commonSuffixToken(paths);
        if (suffix != null) {
            return suffix + "(any)";
        }
        if (paths.size() > MAX_LISTED_ITEMS) {
            return "any[" + String.join("/", paths.subList(0, MAX_LISTED_ITEMS)) + "/+"
                    + (paths.size() - MAX_LISTED_ITEMS) + " more]";
        }
        return "any[" + String.join("/", paths) + "]";
    }

    private static String commonSuffixToken(List<String> paths) {
        String token = null;
        for (String p : paths) {
            int u = p.lastIndexOf('_');
            String t = u < 0 ? p : p.substring(u + 1);
            if (token == null) {
                token = t;
            } else if (!token.equals(t)) {
                return null;
            }
        }
        return token;
    }
}
