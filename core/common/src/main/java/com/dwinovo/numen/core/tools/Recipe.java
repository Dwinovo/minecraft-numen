package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.sdk.Doc;

import java.util.List;
import java.util.Optional;

/**
 * 一条配方:{@code numen.inv.recipes} 与 {@code numen.inv.craftable} 交回的那一项。
 *
 * @param grid    合成配方装得下的最小的格;别的工位没有
 * @param missing 合成配方照她背着的合一次还缺什么;不缺或不是合成配方时没有
 * @param ticks   烧炼类配方一件要多久
 */
@Doc("One way to make an item, like a page of JEI.")
public record Recipe(@Doc("The recipe's id; numen.inv.craft takes it.") String id,
                     @Doc("Where it is made.") Station station,
                     @Doc("What it makes.") String item,
                     @Doc("How many one craft makes.") int makes,
                     @Doc("One entry per filled cell: an item, or planks(any) for any of a kind.")
                     List<String> ingredients,
                     @Doc("Crafting only: the smallest grid it fits, 2 (your own) or 3 (a crafting table); 0 needs a "
                             + "modded station.") Optional<Integer> grid,
                     @Doc("Crafting only: what you are short of to craft it once, 3x iron_ingot (have 1); absent when "
                             + "you have it all.") Optional<List<String>> missing,
                     @Doc("Cooking only: how long one item takes.") Optional<Integer> ticks) {

    /** 在哪做。 */
    public enum Station { CRAFTING, SMELTING, BLASTING, SMOKING, CAMPFIRE, STONECUTTER, SMITHING }
}
