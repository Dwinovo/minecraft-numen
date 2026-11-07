package com.dwinovo.numen.api.knowledge;

import com.dwinovo.numen.api.sdk.Doc;

import java.util.List;
import java.util.Optional;

/**
 * 一条配方:{@code numen.inv.recipes}、{@code numen.inv.uses}、{@code numen.inv.craftable} 交回的那一项,也是知识来源
 * ({@link KnowledgeKind#RECIPES}、{@link KnowledgeKind#USES})交给动词的那一项。来源只填它知道的:读不到的字段留空,不猜;
 * 来源自己的 id 不用写,动词合并时盖上({@link #sources})。
 *
 * @param id       配方在配方表里的名字;来源没有给出时没有
 * @param category 配方类型的 id(原版注册表里的 {@code RecipeType} 名,或来源自己的类别 id):认识的几种有完整描述,模组自己的类型只有输入与产物
 * @param grid     合成配方装得下的最小的格;别的类别没有
 * @param missing  合成配方照她背着的合一次还缺什么;不缺或不是合成配方时没有
 * @param ticks    烧炼类配方一件要多久
 */
@Doc("One way to make an item, like a page of JEI.")
public record Recipe(@Doc("The recipe's id; numen.inv.craft takes it for a crafting recipe. Absent when no source "
        + "knows one.") Optional<String> id,
                     @Doc("Where it is made: the recipe type's id. minecraft:crafting, minecraft:smelting, minecraft:blasting, "
                             + "minecraft:smoking, minecraft:campfire_cooking, minecraft:stonecutting or minecraft:smithing, "
                             + "or a mod's own machine type such as create:mixing.") String category,
                     @Doc("The category's name in your owner's language (Mixing), when a source on their client knows "
                             + "it.") Optional<String> categoryName,
                     @Doc("What it makes.") String item,
                     @Doc("How many one craft makes.") int makes,
                     @Doc("Also made, besides item: more items, fluids with their amount (250mB water).")
                     List<String> alsoMakes,
                     @Doc("One entry per input: an item, planks(any) for any of a kind, 3x iron_ingot for a count, "
                             + "1000mB water for a fluid.") List<String> ingredients,
                     @Doc("The machines or blocks that do this recipe (furnace, create:mechanical_mixer), when a source "
                             + "knows them.") List<String> catalysts,
                     @Doc("Crafting only: the smallest grid it fits, 2 (your own) or 3 (a crafting table); 0 needs a "
                             + "modded station.") Optional<Integer> grid,
                     @Doc("Crafting only: what you are short of to craft it once, 3x iron_ingot (have 1); absent when "
                             + "you have it all.") Optional<List<String>> missing,
                     @Doc("Cooking only: how long one item takes.") Optional<Integer> ticks,
                     @Doc("The sources that listed this recipe. Filled in by the verb, not by the source.")
                     List<String> sources) {

    /** 一条只有这些的配方(别的字段空着)。 */
    public static Recipe of(String id, String category, String item, int makes, List<String> ingredients) {
        return new Recipe(Optional.ofNullable(id), category, Optional.empty(), item, makes, List.of(), ingredients,
                List.of(), Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }
}
