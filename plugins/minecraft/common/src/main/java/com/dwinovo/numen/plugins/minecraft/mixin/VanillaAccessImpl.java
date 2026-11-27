package com.dwinovo.numen.plugins.minecraft.mixin;

import com.dwinovo.numen.plugins.minecraft.mixin.inject.CraftingMenuAccessor;
import com.dwinovo.numen.plugins.minecraft.mixin.inject.FishingHookAccessor;
import com.dwinovo.numen.plugins.minecraft.mixin.inject.ItemEntityAccessor;
import com.dwinovo.numen.plugins.minecraft.platform.IVanillaAccess;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

/**
 * {@link IVanillaAccess} 的模组实现,全部经 {@code mixin.inject} 包的 Mixin 访问器。
 * Mixin 配置的包是下一层的 {@code mixin.inject},这里不是 Mixin 声明的包,可以被 ServiceLoader 正常加载。
 */
public final class VanillaAccessImpl implements IVanillaAccess {

    @Override
    public int biteCountdown(FishingHook hook) {
        return ((FishingHookAccessor) (Object) hook).numen$getNibble();
    }

    @Override
    public int pickupDelay(ItemEntity item) {
        return ((ItemEntityAccessor) item).numen$getPickupDelay();
    }

    @Override
    public void recomputeCraftingResult(AbstractContainerMenu menu, Level level, Player player,
                                        CraftingContainer grid, ResultContainer result,
                                        RecipeHolder<CraftingRecipe> hint) {
        CraftingMenuAccessor.numen$recompute(menu, level, player, grid, result, hint);
    }
}
