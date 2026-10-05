package com.dwinovo.numen.plugins.kaleidoscope;

import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.BaseRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.Quality;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.QualityEvaluator;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;

/**
 * 一道弹性菜({@code flex_pot} / {@code flex_stockpot})在这个存档上的黄金配比。
 *
 * <h2>配比从哪来</h2>
 * 森罗按 <b>世界种子 + 配方 id</b> 给每道弹性菜发一组固定的投料量
 * ({@code QualityEvaluator.randomVector}),投料向量与它的<b>余弦相似度</b>四次方决定品质档位。
 * 换个存档同一道菜的配比就不一样,所以它只能现查,没法写进表里。
 *
 * <h2>为什么是枚举而不是算一遍</h2>
 * {@code randomVector} 是私有的,森罗公开的只有 {@code evaluate(...)}。这里<b>不重写</b>那套
 * 发牌与洗牌——把 {@code evaluate} 当预言机用:按配方的料枚举投料量,问它判成几档,取第一个被判成
 * {@link Quality#SUPERB} 的。判据因此仍只有森罗那一处,森罗改了算法这里跟着变。
 *
 * <h2>为什么取"最小"的那个</h2>
 * 余弦相似度只看方向不看长度,所以 2:1 和 4:2 同档。枚举按总量从小到大走,第一个命中的就是
 * <b>花最少的料拿到极佳</b>的那个配比。
 */
public final class GoldenRatio {

    /** 单种料的上限:{@code randomVector} 发的量最多就是 4。 */
    private static final int MAX_PER_INGREDIENT = 4;

    private GoldenRatio() {}

    /**
     * 查这道菜的黄金配比。
     *
     * @param ingredients 配方的料(已剔空)
     * @param recipeId    配方 id——它和世界种子一起决定这个存档的配比
     * @param worldSeed   {@code ServerLevel.getSeed()}
     * @return 与 {@code ingredients} 一一对应的份数;锅装得下的范围内没有一种投法能到极佳时返回 null
     */
    public static int[] of(List<Ingredient> ingredients, ResourceLocation recipeId, long worldSeed) {
        int size = ingredients.size();
        if (size == 0 || size > BaseRecipe.RECIPES_SIZE) {
            return null;
        }
        ItemStack[] sample = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            ItemStack[] items = ingredients.get(i).getItems();
            if (items.length == 0) {
                // 这一味是空标签(数据包没给它任何物品),摆不出投料,也就问不出配比
                return null;
            }
            sample[i] = items[0];
        }
        // 总量从小到大:先命中的就是最省料的那个配比
        for (int total = size; total <= BaseRecipe.RECIPES_SIZE; total++) {
            int[] counts = new int[size];
            int[] hit = search(sample, ingredients, recipeId, worldSeed, counts, 0, total);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** 把 {@code remaining} 份摊到第 {@code at} 味及其之后,每味至少 1 份。 */
    private static int[] search(ItemStack[] sample, List<Ingredient> ingredients, ResourceLocation recipeId,
                                long worldSeed, int[] counts, int at, int remaining) {
        int left = counts.length - at;
        if (left == 1) {
            if (remaining < 1 || remaining > MAX_PER_INGREDIENT) {
                return null;
            }
            counts[at] = remaining;
            return grade(sample, ingredients, recipeId, worldSeed, counts) == Quality.SUPERB
                    ? counts.clone() : null;
        }
        int max = Math.min(MAX_PER_INGREDIENT, remaining - (left - 1));
        for (int n = 1; n <= max; n++) {
            counts[at] = n;
            int[] hit = search(sample, ingredients, recipeId, worldSeed, counts, at + 1, remaining - n);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * 这么投料森罗会判几档。
     *
     * <p>摆出来的投料表要和方块实体递给评估器的<b>一模一样</b>:补满 {@code RECIPES_SIZE} 格、
     * 每格一份。炒锅和汤锅递的都是这个形状的表,少一格或多一格算出来的就不是同一个分数。
     */
    private static Quality grade(ItemStack[] sample, List<Ingredient> ingredients, ResourceLocation recipeId,
                                 long worldSeed, int[] counts) {
        NonNullList<ItemStack> inputs =
                NonNullList.withSize(BaseRecipe.RECIPES_SIZE, ItemStack.EMPTY);
        int slot = 0;
        for (int i = 0; i < counts.length; i++) {
            for (int n = 0; n < counts[i]; n++) {
                inputs.set(slot++, sample[i].copyWithCount(1));
            }
        }
        return QualityEvaluator.evaluate(inputs, ingredients, recipeId, worldSeed);
    }
}
