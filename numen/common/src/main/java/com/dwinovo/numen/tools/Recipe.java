package com.dwinovo.numen.tools;

import com.dwinovo.numen.api.sdk.Doc;

import java.util.List;
import java.util.Optional;

/**
 * 一条配方:{@code numen.inv.recipes} 与 {@code numen.inv.craftable} 交回的那一项。
 *
 * @param category 配方类型的 id(原版注册表里的 {@code RecipeType} 名):认识的几种有完整描述,模组自己的类型只有输入与产物
 * @param grid    合成配方装得下的最小的格;别的类别没有
 * @param missing 合成配方照她背着的合一次还缺什么;不缺或不是合成配方时没有
 * @param ticks   烧炼类配方一件要多久
 */
@Doc("One way to make an item, like a page of JEI.")
public record Recipe(@Doc("The recipe's id; numen.inv.craft takes it.") String id,
                     @Doc("Where it is made: the recipe type's id. minecraft:crafting, minecraft:smelting, minecraft:blasting, "
                             + "minecraft:smoking, minecraft:campfire_cooking, minecraft:stonecutting or minecraft:smithing, "
                             + "or a mod's own machine type such as create:mixing.") String category,
                     @Doc("What it makes.") String item,
                     @Doc("How many one craft makes.") int makes,
                     @Doc("One entry per filled cell: an item, or planks(any) for any of a kind.")
                     List<String> ingredients,
                     @Doc("Crafting only: the smallest grid it fits, 2 (your own) or 3 (a crafting table); 0 needs a "
                             + "modded station.") Optional<Integer> grid,
                     @Doc("Crafting only: what you are short of to craft it once, 3x iron_ingot (have 1); absent when "
                             + "you have it all.") Optional<List<String>> missing,
                     @Doc("Cooking only: how long one item takes.") Optional<Integer> ticks) {}
