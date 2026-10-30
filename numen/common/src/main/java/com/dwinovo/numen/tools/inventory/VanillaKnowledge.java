package com.dwinovo.numen.tools.inventory;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.knowledge.InfoPage;
import com.dwinovo.numen.api.knowledge.ItemFacts;
import com.dwinovo.numen.api.knowledge.KnowledgeKind;
import com.dwinovo.numen.api.knowledge.RecipePage;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.tools.RecipeBook;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * 配方与物品知识的第一个来源:游戏自己的数据,服务端就有,不依赖任何模组。配方读自配方表(任何模组的配方类型都在里面,见
 * {@link RecipeBook}),事实读自物品注册表与默认组件。三个动词各登记一个来源;它们是隐藏的函数,模型只看见动词
 * ({@code numen.inv.recipes}、{@code uses}、{@code info}),不看见来源。
 */
public final class VanillaKnowledge {

    /** 原版读不到、因此不会给的字段:配方表只有类型、输入与产物。 */
    private static final List<String> NOT_PROVIDED = List.of("chance", "time", "heat", "energy", "fluids",
            "which machine does it");

    private VanillaKnowledge() {}

    static void install(NumenApi numen) {
        numen.knowledgeSource(KnowledgeKind.RECIPES, "every recipe type in the game's recipe list, crafting, cooking, "
                + "stonecutting and smithing in full", NOT_PROVIDED, Recipes.class);
        numen.knowledgeSource(KnowledgeKind.USES, "every recipe type in the game's recipe list that takes the item as an "
                + "input", NOT_PROVIDED, Uses.class);
        numen.knowledgeSource(KnowledgeKind.INFO, "the game's own facts about the item: stack size, durability, food, "
                + "the block it places, its tags", List.of("descriptions"), Info.class);
    }

    /** 查哪一样。 */
    public record Query(@Doc("The item.") Item item) {}

    /** {@code recipes} 的来源。 */
    public static final class Recipes {
        private Recipes() {}

        @Fn("The recipes that make an item, from the game's recipe list.")
        public static RecipePage read(ServerCall call, Query args) {
            return RecipeBook.recipesFor(args.item(), call.her());
        }
    }

    /** {@code uses} 的来源。 */
    public static final class Uses {
        private Uses() {}

        @Fn("The recipes that take an item as an input, from the game's recipe list.")
        public static RecipePage read(ServerCall call, Query args) {
            return RecipeBook.usesOf(args.item(), call.her());
        }
    }

    /** {@code info} 的来源。 */
    public static final class Info {
        private Info() {}

        @Fn("The game's own facts about an item.")
        public static InfoPage read(ServerCall call, Query args) {
            return new InfoPage(Optional.of(facts(args.item())), List.of());
        }
    }

    /** 读一样物品的事实。 */
    public static ItemFacts facts(Item item) {
        ItemStack stack = item.getDefaultInstance();
        FoodProperties food = stack.get(DataComponents.FOOD);
        return new ItemFacts(BuiltInRegistries.ITEM.getKey(item).toString(), stack.getMaxStackSize(),
                stack.isDamageableItem() ? Optional.of(stack.getMaxDamage()) : Optional.empty(),
                food == null ? Optional.empty()
                        : Optional.of(new ItemFacts.Food(food.nutrition(), Math.round(food.saturation() * 100) / 100.0,
                                food.canAlwaysEat())),
                item instanceof BlockItem block
                        ? Optional.of(BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString()) : Optional.empty(),
                BuiltInRegistries.ITEM.wrapAsHolder(item).tags().map(t -> t.location().toString()).sorted().toList());
    }
}
