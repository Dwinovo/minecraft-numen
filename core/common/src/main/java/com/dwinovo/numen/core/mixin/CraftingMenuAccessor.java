package com.dwinovo.numen.core.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 原版"按当前格局重算合成结果"的那一趟,{@code protected static},外面调不到。
 *
 * <p>它是结果槽<b>唯一</b>的写入处:原版的 {@code CraftingMenu.slotsChanged} 与
 * {@code InventoryMenu.slotsChanged}(玩家自己的 2×2)都只是转调它。
 *
 * <p>我们摆完格子要当场读结果,所以摆完就自己调一次,而不是指望 {@code slotsChanged}
 * ——那是个<b>可被覆写的触发器</b>:把重算推迟到之后 server tick 的模组(FastWorkbench 一类)
 * 覆写的正是它,于是同一刻读到的结果槽还是空的,合成稳定失败(issue #110)。绕开触发器、
 * 直接要这趟计算,对原版和那类模组都成立;我们手上那条配方顺带当 hint 传进去,连全表线性扫都省了。
 */
@Mixin(CraftingMenu.class)
public interface CraftingMenuAccessor {

    @Invoker("slotChangedCraftingGrid")
    static void numen$recompute(AbstractContainerMenu menu, Level level, Player player,
                                CraftingContainer grid, ResultContainer result,
                                RecipeHolder<CraftingRecipe> hint) {
        throw new AssertionError();
    }
}
