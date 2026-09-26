package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.entity.NumenPlayer;
import com.github.ysbbbbbb.kaleidoscopecookery.api.recipe.soupbase.ISoupBase;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.FlexPotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.FlexStockpotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.PotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.StockpotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.soupbase.SoupBaseManager;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一道菜的做法——森罗的一条配方读成同伴看得懂的样子。
 *
 * <p>森罗每口锅有两张表:<b>固定表</b>(照着投料就是那道菜,没有品质)和<b>弹性表</b>
 * ({@code flex_*},同一组料按投料比例判品质)。两张表的记录是四个互不相干的 record,
 * 这里是唯一一处把它们摊平的地方。
 *
 * @param id          配方 id,{@code kaleidoscope cook} 点菜用的就是它
 * @param cookware    哪口锅做
 * @param result      出锅的东西
 * @param ingredients 要的料(已剔掉配方表里的空位)
 * @param carrier     装盘用的容器;不用容器时是空 Ingredient
 * @param soupBase    汤锅要的汤底 id;炒锅为 null
 * @param time        起锅到出锅的游戏刻
 * @param stirFry     炒锅要翻够的次数;汤锅为 0
 * @param flex        是不是弹性配方(是才有品质与黄金配比)
 */
public record Dish(ResourceLocation id, Cookware cookware, ItemStack result, List<Ingredient> ingredients,
                   Ingredient carrier, ResourceLocation soupBase, int time, int stirFry, boolean flex) {

    /** 这口锅能做的全部菜。 */
    public static List<Dish> menu(Level level, Cookware cookware) {
        RecipeManager manager = level.getRecipeManager();
        List<Dish> out = new ArrayList<>();
        switch (cookware) {
            case POT -> {
                manager.getAllRecipesFor(ModRecipes.POT_RECIPE).forEach(h -> out.add(of(h)));
                manager.getAllRecipesFor(ModRecipes.FLEX_POT_RECIPE).forEach(h -> out.add(of(h)));
            }
            case STOCKPOT -> {
                manager.getAllRecipesFor(ModRecipes.STOCKPOT_RECIPE).forEach(h -> out.add(of(h)));
                manager.getAllRecipesFor(ModRecipes.FLEX_STOCKPOT_RECIPE).forEach(h -> out.add(of(h)));
            }
        }
        return out;
    }

    /** 按 id 点一道菜;这个 id 不是炒锅/汤锅的配方时返回 null。 */
    public static Dish byId(Level level, ResourceLocation id) {
        RecipeHolder<?> holder = level.getRecipeManager().byKey(id).orElse(null);
        if (holder == null) {
            return null;
        }
        Object recipe = holder.value();
        boolean mine = recipe instanceof PotRecipe || recipe instanceof FlexPotRecipe
                || recipe instanceof StockpotRecipe || recipe instanceof FlexStockpotRecipe;
        return mine ? of(holder) : null;
    }

    private static Dish of(RecipeHolder<?> holder) {
        return switch (holder.value()) {
            case PotRecipe r -> new Dish(holder.id(), Cookware.POT, r.result(), filled(r.ingredients()),
                    r.carrier(), null, r.time(), r.stirFryCount(), false);
            case FlexPotRecipe r -> new Dish(holder.id(), Cookware.POT, r.result(), filled(r.ingredients()),
                    r.carrier(), null, r.time(), r.stirFryCount(), true);
            case StockpotRecipe r -> new Dish(holder.id(), Cookware.STOCKPOT, r.result(), filled(r.ingredients()),
                    r.carrier(), r.soupBase(), r.time(), 0, false);
            case FlexStockpotRecipe r -> new Dish(holder.id(), Cookware.STOCKPOT, r.result(), filled(r.ingredients()),
                    r.carrier(), r.soupBase(), r.time(), 0, true);
            default -> throw new IllegalArgumentException("not a kaleidoscope cookware recipe: " + holder.id());
        };
    }

    /** 配方表按 9 格存,后面是空位;真正要的料只有前面那几味。 */
    private static List<Ingredient> filled(List<Ingredient> raw) {
        List<Ingredient> out = new ArrayList<>(raw.size());
        for (Ingredient i : raw) {
            if (!i.isEmpty()) {
                out.add(i);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 这道菜该怎么投料——与 {@link #ingredients()} 一一对应的份数。
     *
     * <p>固定配方一味一份就是它本来的样子;弹性配方按这个存档的黄金配比投,投别的量做出来的是
     * 同一道菜的低品质版本。配比查不出来时返回 null,上面据此如实收场,不会自作主张随便投一份。
     */
    public int[] portions(ServerLevel level) {
        if (!flex) {
            int[] one = new int[ingredients.size()];
            java.util.Arrays.fill(one, 1);
            return one;
        }
        return GoldenRatio.of(ingredients, id, level.getSeed());
    }

    /**
     * 拿 {@code have} 去凑 {@code want} 的份数,每味还差多少。
     *
     * <p>锅里已经下了多少、背包里够不够,问的是同一件事,所以只有这一处答案。一份东西可能同时
     * 满足好几味(标签会重叠),按味的先后配:先配得上的先扣。固定配方里同一味会占好几条
     * (要两个蛋就写两条),所以数的是<b>量</b>不是条数。
     */
    public int[] stillNeeded(Iterable<ItemStack> have, int[] want) {
        int[] need = want.clone();
        for (ItemStack stack : have) {
            if (stack.isEmpty()) {
                continue;
            }
            int available = stack.getCount();
            for (int i = 0; i < need.length && available > 0; i++) {
                if (need[i] > 0 && ingredients.get(i).test(stack)) {
                    int take = Math.min(need[i], available);
                    need[i] -= take;
                    available -= take;
                }
            }
        }
        return need;
    }

    /**
     * 她现在能不能把这道菜做出来——料、容器、厨具、汤底一样一样数过背包。
     *
     * @return 能做返回 null;做不了返回第一样缺的东西
     */
    public String missingFor(NumenPlayer cook, ServerLevel level) {
        int[] want = portions(level);
        if (want == null) {
            return "this world's golden ratio for it cannot be resolved";
        }
        int[] missing = stillNeeded(Pantry.all(cook), want);
        for (int i = 0; i < missing.length; i++) {
            if (missing[i] > 0) {
                return missing[i] + "x more " + names(ingredients.get(i));
            }
        }
        if (!carrier.isEmpty() && Pantry.count(cook, carrier) < result.getCount()) {
            return result.getCount() + "x " + names(carrier) + " to plate it";
        }
        if (cookware == Cookware.POT) {
            if (Pantry.count(cook, s -> s.is(TagMod.OIL)) == 0) {
                return "oil (tag kaleidoscope_cookery:oil)";
            }
            if (Pantry.count(cook, s -> s.is(TagMod.KITCHEN_SHOVEL)) == 0) {
                return "a kitchen shovel";
            }
            return null;
        }
        if (Pantry.count(cook, s -> s.is(ModItems.STOCKPOT_LID.get())) == 0) {
            return "a stockpot lid";
        }
        ISoupBase base = SoupBaseManager.getSoupBase(soupBase);
        if (base == null) {
            return "soup base " + soupBase + ", which nothing registers";
        }
        if (Pantry.count(cook, base::isSoupBase) == 0) {
            return idOf(base.getDisplayStack().getItem()) + " for the soup base";
        }
        return null;
    }

    /** 一味料认哪些物品;标签能认一大串,列不完就省略。 */
    public static String names(Ingredient ingredient) {
        ItemStack[] items = ingredient.getItems();
        if (items.length == 0) {
            return "(nothing matches)";
        }
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(items.length, 4);
        for (int i = 0; i < shown; i++) {
            sb.append(i == 0 ? "" : " / ").append(idOf(items[i].getItem()));
        }
        if (items.length > shown) {
            sb.append(" / …(").append(items.length).append(" items)");
        }
        return sb.toString();
    }

    public static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** 汤底要拿什么倒进去;这条汤底没登记过就只报 id。 */
    public String soupBaseName() {
        if (soupBase == null) {
            return null;
        }
        ISoupBase base = SoupBaseManager.getSoupBase(soupBase);
        return base == null ? soupBase.toString() : idOf(base.getDisplayStack().getItem());
    }

    /** {@code kaleidoscope recipes} 的一行。 */
    public Map<String, Object> row(ServerLevel level) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("recipe", id.toString());
        row.put("dish", idOf(result.getItem()) + (result.getCount() > 1 ? " x" + result.getCount() : ""));
        List<String> needs = new ArrayList<>();
        int[] want = portions(level);
        for (int i = 0; i < ingredients.size(); i++) {
            needs.add(names(ingredients.get(i)) + " x" + (want == null ? 1 : want[i]));
        }
        row.put("ingredients", needs);
        if (!carrier.isEmpty()) {
            row.put("carrier", names(carrier));
        }
        if (soupBase != null) {
            row.put("soup_base", soupBaseName());
        }
        row.put("kitchenware", cookware == Cookware.POT
                ? List.of("kaleidoscope_cookery:kitchen_shovel", "#kaleidoscope_cookery:oil")
                : List.of("kaleidoscope_cookery:stockpot_lid"));
        row.put("cook_ticks", time);
        if (cookware == Cookware.POT) {
            row.put("stir_fries", stirFry);
        }
        if (!flex) {
            row.put("quality", "fixed recipe — always the same, portions above are exact");
        } else if (want == null) {
            row.put("quality", "flex recipe, but no mix within the pot's 9 slots grades SUPERB on this world"
                    + " — the portions above are a guess, treat them as unknown");
        } else {
            row.put("quality", "flex recipe — the portions above ARE this world's golden ratio (grades SUPERB)");
        }
        return row;
    }
}
