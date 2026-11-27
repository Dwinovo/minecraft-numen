package com.dwinovo.numen.plugins.minecraft.tools.inventory;

import com.dwinovo.numen.api.knowledge.ItemFacts;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * 游戏自己对物品的知识:事实读自物品注册表与默认组件,服务端就有,不依赖任何模组。配方的查询在 {@link RecipeBook},
 * 动词({@code minecraft.inv.recipes}、{@code uses}、{@code info})在 {@link InvApi}。
 */
public final class VanillaKnowledge {

    private VanillaKnowledge() {}

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
