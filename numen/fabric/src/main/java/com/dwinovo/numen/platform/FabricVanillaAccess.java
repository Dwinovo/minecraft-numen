package com.dwinovo.numen.platform;

import com.dwinovo.numen.mixin.CraftingMenuAccessor;
import com.dwinovo.numen.mixin.FishingHookAccessor;
import com.dwinovo.numen.mixin.ItemEntityAccessor;
import com.dwinovo.numen.mixin.MenuDataSlotsAccessor;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * {@link IVanillaAccess} 的模组实现,全部经 {@code mixin} 包的 Mixin 访问器。
 * 不能放进 {@code mixin} 包:Mixin 不许直接加载包里的非 Mixin 类。
 */
public final class FabricVanillaAccess implements IVanillaAccess {

    @Override
    public int biteCountdown(FishingHook hook) {
        return ((FishingHookAccessor) (Object) hook).numen$getNibble();
    }

    @Override
    public int pickupDelay(ItemEntity item) {
        return ((ItemEntityAccessor) item).numen$getPickupDelay();
    }

    @Override
    public List<DataSlot> syncedDataSlots(AbstractContainerMenu menu) {
        return ((MenuDataSlotsAccessor) (Object) menu).numen$dataSlots();
    }

    @Override
    public void recomputeCraftingResult(AbstractContainerMenu menu, Level level, Player player,
                                        CraftingContainer grid, ResultContainer result,
                                        RecipeHolder<CraftingRecipe> hint) {
        CraftingMenuAccessor.numen$recompute(menu, level, player, grid, result, hint);
    }
}
