package com.dwinovo.numen.platform;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

/**
 * Paper 上的 {@link IVanillaAccess}:Paper 的服务端是 Mojang 名。{@code pickupDelay} 与 {@code dataSlots} 在 Paper 上是公开成员,
 * 直接用;{@code nibble} 与 {@code slotChangedCraftingGrid} 仍是私有 / 受保护的,按名字找,句柄一次查好缓存。
 * 找不到(换了版本改了名)是类加载时就抛的错,不是等到有人用才发现。
 */
public final class PaperVanillaAccess implements IVanillaAccess {

    private static final MethodHandle NIBBLE;
    private static final MethodHandle RECOMPUTE;

    static {
        try {
            NIBBLE = MethodHandles.privateLookupIn(FishingHook.class, MethodHandles.lookup())
                    .findGetter(FishingHook.class, "nibble", int.class);
            RECOMPUTE = MethodHandles.privateLookupIn(CraftingMenu.class, MethodHandles.lookup())
                    .findStatic(CraftingMenu.class, "slotChangedCraftingGrid", MethodType.methodType(void.class,
                            AbstractContainerMenu.class, Level.class, Player.class, CraftingContainer.class,
                            ResultContainer.class, RecipeHolder.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public int biteCountdown(FishingHook hook) {
        try {
            return (int) NIBBLE.invokeExact(hook);
        } catch (Throwable t) {
            throw new IllegalStateException("读 FishingHook.nibble 抛了异常", t);
        }
    }

    @Override
    public int pickupDelay(ItemEntity item) {
        return item.pickupDelay;
    }

    @Override
    public List<DataSlot> syncedDataSlots(AbstractContainerMenu menu) {
        return menu.dataSlots;
    }

    @Override
    public void recomputeCraftingResult(AbstractContainerMenu menu, Level level, Player player,
                                        CraftingContainer grid, ResultContainer result,
                                        RecipeHolder<CraftingRecipe> hint) {
        try {
            RECOMPUTE.invokeExact(menu, level, player, grid, result, (RecipeHolder) hint);
        } catch (Throwable t) {
            throw new IllegalStateException("CraftingMenu.slotChangedCraftingGrid 抛了异常", t);
        }
    }
}
