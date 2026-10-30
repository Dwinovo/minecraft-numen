package com.dwinovo.numen.plugins.jei;

import com.dwinovo.numen.api.knowledge.InfoPage;
import com.dwinovo.numen.api.knowledge.Recipe;
import com.dwinovo.numen.api.knowledge.RecipePage;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.mojang.logging.LogUtils;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientSupplier;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 向主人客户端上的 JEI 问配方与说明页。只走 JEI 的公共查询口:按焦点(产出、输入)找类别与配方,{@code getRecipeIngredients} 按角色读
 * 输入与产出(物品、流体与数量),催化剂查询读"哪台机器做",类别标题由客户端语言解析,说明页是 JEI 的信息类别。概率、耗时、热量、能量是各模组
 * 画在界面上的,没有结构化的口子,不读。
 *
 * <p>线程:客户端调用通道把请求投回客户端主线程执行(NeoForge 的 {@code playToClient} 默认主线程,Fabric 的接收器 {@code client.execute}),
 * JEI 的查询在这里同一线程上做。答复要装进客户端答复的大小上限,所以配方封顶、每条配方的输入列表封顶。
 */
final class JeiLookups {

    private static final Logger LOG = LogUtils.getLogger();

    /** 一次交回的配方条数上限;超出的条数在 {@code omitted} 里如实说。 */
    private static final int MAX_RECIPES = 40;
    /** 一条配方里输入(或产出)列几项:同槽的候选展平后,一个标签能有几十种。 */
    private static final int MAX_LISTED = 12;
    /** 一条说明最多多少字。 */
    private static final int MAX_NOTE_CHARS = 1500;
    /** 最多几条说明。 */
    private static final int MAX_NOTES = 10;

    private JeiLookups() {}

    /** 以这样东西为产出的配方。 */
    static RecipePage recipes(Item item) {
        return page(item, RecipeIngredientRole.OUTPUT);
    }

    /** 以这样东西为输入的配方。 */
    static RecipePage uses(Item item) {
        return page(item, RecipeIngredientRole.INPUT);
    }

    /** 为这样东西写的说明页。 */
    static InfoPage info(Item item) {
        IJeiRuntime runtime = runtime();
        IFocus<ItemStack> focus = focus(runtime, RecipeIngredientRole.INPUT, item);
        List<String> notes = runtime.getRecipeManager().createRecipeLookup(RecipeTypes.INFORMATION)
                .limitFocus(List.of(focus)).get()
                .map(page -> page.getDescription().stream().map(FormattedText::getString)
                        .collect(Collectors.joining(" ")).strip())
                .filter(text -> !text.isEmpty())
                .map(text -> text.length() > MAX_NOTE_CHARS ? text.substring(0, MAX_NOTE_CHARS) + "…" : text)
                .limit(MAX_NOTES).toList();
        return new InfoPage(Optional.empty(), notes);
    }

    private static IJeiRuntime runtime() {
        IJeiRuntime runtime = NumenJeiPlugin.runtime();
        if (runtime == null) {
            throw new ApiError(ErrorKind.FAILED, "JEI is installed on your owner's client but has not finished loading "
                    + "its recipes yet, or no world is open there", "ask again in a moment");
        }
        return runtime;
    }

    private static IFocus<ItemStack> focus(IJeiRuntime runtime, RecipeIngredientRole role, Item item) {
        return runtime.getJeiHelpers().getFocusFactory().createFocus(role, VanillaTypes.ITEM_STACK, new ItemStack(item));
    }

    private static RecipePage page(Item item, RecipeIngredientRole role) {
        IJeiRuntime runtime = runtime();
        IFocus<ItemStack> focus = focus(runtime, role, item);
        Collected collected = new Collected();
        runtime.getRecipeManager().createRecipeCategoryLookup().limitFocus(List.of(focus)).get()
                .filter(category -> category.getRecipeType() != RecipeTypes.INFORMATION && !isTagPage(category))
                .forEach(category -> collect(runtime, category, focus, collected));
        return new RecipePage(collected.recipes, collected.omitted == 0 ? Optional.empty() : Optional.of(collected.omitted));
    }

    /**
     * JEI 自己为标签画的"这个标签里有谁"的页(类别 id 的路径以 {@code tag_recipes/} 开头):不是配方,一个常用标签能带出几百项。物品在哪些标签里
     * 是原版来源的 {@code info.facts.tags}。
     */
    private static boolean isTagPage(IRecipeCategory<?> category) {
        return category.getRecipeType().getUid().getPath().startsWith("tag_recipes/");
    }

    /** 一次查询攒下的配方,与封顶之后没列的条数。 */
    private static final class Collected {
        final List<Recipe> recipes = new ArrayList<>();
        int omitted;
    }

    /** 一个类别里对得上焦点的每条配方。每条自成一格:一条读不出的配方只丢它自己。 */
    private static <T> void collect(IJeiRuntime runtime, IRecipeCategory<T> category, IFocus<?> focus, Collected into) {
        IRecipeManager manager = runtime.getRecipeManager();
        RecipeType<T> type = category.getRecipeType();
        IJeiHelpers helpers = runtime.getJeiHelpers();
        List<String> catalysts = manager.createRecipeCatalystLookup(type).get()
                .map(catalyst -> describe(helpers, catalyst, true).text()).distinct().toList();
        String categoryName = category.getTitle().getString();
        manager.createRecipeLookup(type).limitFocus(List.of(focus)).get().forEach(recipe -> {
            if (into.recipes.size() >= MAX_RECIPES) {
                into.omitted++;
                return;
            }
            try {
                into.recipes.add(row(manager, helpers, category, recipe, categoryName, catalysts));
            } catch (RuntimeException broken) {
                LOG.debug("[numen-jei] a recipe of {} could not be read, skipped: {}", type.getUid(), broken.toString());
            }
        });
    }

    private static <T> Recipe row(IRecipeManager manager, IJeiHelpers helpers, IRecipeCategory<T> category, T recipe,
                                  String categoryName, List<String> catalysts) {
        IIngredientSupplier supplier = manager.getRecipeIngredients(category, recipe);
        List<Entry> made = supplier.getIngredients(RecipeIngredientRole.OUTPUT).stream()
                .map(typed -> describe(helpers, typed, true)).distinct().toList();
        Entry main = made.stream().filter(Entry::item).findFirst().orElse(made.isEmpty() ? null : made.get(0));
        List<String> also = made.stream().filter(entry -> entry != main).map(Entry::text).toList();
        List<String> taken = supplier.getIngredients(RecipeIngredientRole.INPUT).stream()
                .map(typed -> describe(helpers, typed, false).text()).distinct().toList();
        ResourceLocation id = category.getRegistryName(recipe);
        return new Recipe(Optional.ofNullable(id).map(ResourceLocation::toString), category.getRecipeType().getUid().toString(),
                Optional.of(categoryName), main == null ? "nothing listed" : main.name(), main == null ? 0 : (int) Math.min(main.amount(), Integer.MAX_VALUE),
                listed(also), listed(taken), catalysts, Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }

    /** 最多列 {@link #MAX_LISTED} 项,其余记一个数。 */
    private static List<String> listed(List<String> all) {
        if (all.size() <= MAX_LISTED) {
            return all;
        }
        List<String> out = new ArrayList<>(all.subList(0, MAX_LISTED));
        out.add("+" + (all.size() - MAX_LISTED) + " more");
        return out;
    }

    /**
     * 一项成分在结果里的样子。
     *
     * @param name   物品是 id(产出、催化剂带命名空间,输入只写路径,同原版来源的写法),流体是流体 id
     * @param amount 物品是件数,流体是毫桶
     */
    private record Entry(String name, long amount, boolean item, boolean fluid) {

        String text() {
            return fluid ? amount + "mB " + name : item && amount > 1 ? amount + "x " + name : name;
        }
    }

    private static <V> Entry describe(IJeiHelpers helpers, ITypedIngredient<V> typed, boolean fullId) {
        Optional<ItemStack> stack = typed.getItemStack();
        if (stack.isPresent()) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.get().getItem());
            return new Entry(fullId ? key.toString() : key.getPath(), stack.get().getCount(), true, false);
        }
        IIngredientHelper<V> helper = helpers.getIngredientManager().getIngredientHelper(typed.getType());
        ResourceLocation key = helper.getResourceLocation(typed.getIngredient());
        if (typed.getType().equals(helpers.getPlatformFluidHelper().getFluidIngredientType())) {
            return new Entry(key.getPath(), NumenJei.host().milliBuckets(typed.getIngredient()), false, true);
        }
        // 气体、能量、源质这类各模组自己的成分:只有名字,数量没有通用的读法,不猜
        return new Entry(key + " (" + typed.getType().getIngredientClass().getSimpleName() + ")", 1, false, false);
    }
}
