package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.entity.NumenPlayer;
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
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 配方书:做一样东西的每一条配方,像 JEI 的一页({@code numen.inv.recipes})。合成配方的事实(装得下的最小的格、照她背着的还缺什么)
 * 与合成时查的同一处读({@link CraftOps})。
 */
public final class RecipeBook {

    private RecipeBook() {}

    /** 做这样东西的每一条配方,每个工位;找不到就是空表(它靠挖或交易得来,不是做出来的)。 */
    public static List<com.dwinovo.numen.core.tools.Recipe> recipesFor(Item target, NumenPlayer self) {
        ServerLevel level = self.serverLevel();
        List<com.dwinovo.numen.core.tools.Recipe> out = new ArrayList<>();
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            // 每条配方自成一格:整合包里一条坏配方(产出为 null、输入表为 null)只丢它自己,绝不让它杀掉整个查询。见 RecipeProbe。
            try {
                Recipe<?> r = holder.value();
                if (r instanceof CraftingRecipe cr) {
                    // 产出依赖输入的配方(烟花、镶零件的装备)静态描述答不了;1.21.1 没有 PlacementInfo,空输入表的老启发式一并保留
                    if (cr.isSpecial() || cr.getIngredients().isEmpty()
                            || cr.getIngredients().stream().allMatch(Ingredient::isEmpty)) {
                        continue;
                    }
                    ItemStack result = RecipeProbe.resultOf(cr, level.registryAccess());
                    if (!result.isEmpty() && result.getItem() == target) {
                        out.add(CraftOps.recipe(holder, cr, result, self));
                    }
                } else if (r instanceof AbstractCookingRecipe cook) {
                    ItemStack result = RecipeProbe.resultOf(cook, level.registryAccess());
                    if (!result.isEmpty() && result.getItem() == target) {
                        out.add(station(holder, cookingStation(cook.getType()), result,
                                List.of(describeIngredient(cook.getIngredients().get(0))),
                                Optional.of(cook.getCookingTime())));
                    }
                } else if (r instanceof StonecutterRecipe sc) {
                    ItemStack result = RecipeProbe.resultOf(sc, level.registryAccess());
                    if (!result.isEmpty() && result.getItem() == target) {
                        out.add(station(holder, com.dwinovo.numen.core.tools.Recipe.Station.STONECUTTER, result,
                                List.of(describeIngredient(sc.getIngredients().get(0))), Optional.empty()));
                    }
                } else if (r instanceof SmithingRecipe sm) {
                    // 锻造不走展示产出,保留空输入 assemble 的既有语义:变换配方(下界合金升级)照样给出产物,纹饰配方产出(空的)基底、
                    // 自然排除
                    ItemStack result = RecipeProbe.probe(() -> sm.assemble(new SmithingRecipeInput(
                            ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY), level.registryAccess()));
                    if (!result.isEmpty() && result.getItem() == target) {
                        out.add(station(holder, com.dwinovo.numen.core.tools.Recipe.Station.SMITHING, result, List.of(),
                                Optional.empty()));
                    }
                }
            } catch (RuntimeException broken) {
                com.dwinovo.numen.core.Constants.LOG.debug(
                        "[numen-recipe] 配方 {} 坏了,跳过: {}", holder.id(), broken.toString());
            }
        }
        return out;
    }

    /** 不在合成格里做的一条配方。 */
    private static com.dwinovo.numen.core.tools.Recipe station(RecipeHolder<?> holder,
                                                              com.dwinovo.numen.core.tools.Recipe.Station station,
                                                              ItemStack result, List<String> ingredients,
                                                              Optional<Integer> ticks) {
        return new com.dwinovo.numen.core.tools.Recipe(holder.id().toString(), station,
                BuiltInRegistries.ITEM.getKey(result.getItem()).toString(), result.getCount(), ingredients,
                Optional.empty(), Optional.empty(), ticks);
    }

    private static com.dwinovo.numen.core.tools.Recipe.Station cookingStation(RecipeType<?> type) {
        return type == RecipeType.BLASTING ? com.dwinovo.numen.core.tools.Recipe.Station.BLASTING
                : type == RecipeType.SMOKING ? com.dwinovo.numen.core.tools.Recipe.Station.SMOKING
                : type == RecipeType.CAMPFIRE_COOKING ? com.dwinovo.numen.core.tools.Recipe.Station.CAMPFIRE
                : com.dwinovo.numen.core.tools.Recipe.Station.SMELTING;
    }

    /**
     * 一样料的叫法:单一物品就是它;一类有共同后缀的写成 {@code planks(any)};不然把每一种都列出来——免得一类料把模型引到某一种具体的
     * 东西上。合成缺料时说缺什么用的是同一套叫法。
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
