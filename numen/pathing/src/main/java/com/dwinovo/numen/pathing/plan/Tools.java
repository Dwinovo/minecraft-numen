package com.dwinovo.numen.pathing.plan;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 挖一格要什么工具、背包里的某件算不算:寻路执行用它在动手前判"适格工具",规划定价仍只看背包里有什么。
 *
 * <p>"要工具"照原版:方块 {@code requiresCorrectToolForDrops}。要不要用对工具,原版只按 {@code isCorrectToolForDrops}
 * 判,等级(tier)与工具种类都在里面,所以这里也只问这一处:候选是原版那几件工具,按材料由便宜到贵排,只留挖得动这一格的。
 * 模组工具已经在背包里时由 {@link ItemStack#isCorrectToolForDrops} 自己答,不看这张表。
 */
public final class Tools {

    /**
     * 备一件工具时的候选,按"做起来便宜"到"贵"排:木、石、铁、钻石、金。每档四件(镐、斧、锹、锄),谁挖得动这一格由
     * {@link #harvests} 筛。金放最后:同一档的方块木镐就够,金子更少见,只在别的都做不出时才用。
     */
    private static final List<Item> CANDIDATES = List.of(
            Items.WOODEN_PICKAXE, Items.WOODEN_AXE, Items.WOODEN_SHOVEL, Items.WOODEN_HOE,
            Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL, Items.STONE_HOE,
            Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL, Items.IRON_HOE,
            Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE,
            Items.GOLDEN_PICKAXE, Items.GOLDEN_AXE, Items.GOLDEN_SHOVEL, Items.GOLDEN_HOE);

    private Tools() {}

    /** 这一格要不要对的工具才有掉落。 */
    public static boolean needsTool(BlockState state) {
        return state.requiresCorrectToolForDrops();
    }

    /** 挖得动 {@code state} 的候选工具,按上面那张表的先后;一件都不行为空表。 */
    public static List<Item> candidates(BlockState state) {
        List<Item> out = new ArrayList<>();
        for (Item item : CANDIDATES) {
            if (harvests(item, state)) {
                out.add(item);
            }
        }
        return out;
    }

    /** 手里/背包里的这一叠挖 {@code state} 收得到掉落:空手、剑、等级不够的镐都不算。 */
    public static boolean harvests(ItemStack stack, BlockState state) {
        return !stack.isEmpty() && stack.isCorrectToolForDrops(state);
    }

    /** {@link #harvests(ItemStack, BlockState)} 的按物品问法(还没做出来的工具用它问)。 */
    public static boolean harvests(Item item, BlockState state) {
        return harvests(new ItemStack(item), state);
    }
}
