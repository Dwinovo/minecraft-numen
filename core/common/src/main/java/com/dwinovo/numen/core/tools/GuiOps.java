package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.BlockAt;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Methods;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 打开的界面:{@code numen.gui.view} 读它、{@code numen.gui.close} 关它,{@code numen.use.block} 的右键打开了一个界面时也交回它
 * ({@link #window})。界面是一个 {@link Window}:每个槽一条,方法写在内置模块 {@code numen.gui} 里({@code w:put}、{@code w:take}……)。
 */
public final class GuiOps {

    private GuiOps() {}

    /** 槽在哪一边。 */
    public enum Side { CONTAINER, YOU, GRID, RESULT }

    /** 界面的一个槽。 */
    public record WindowSlot(@Doc("What numen.gui.move and numen.gui.quick take.") int index,
                             Side side,
                             @Doc("Empty slots have none.") Optional<String> item,
                             Optional<Integer> count,
                             @Doc("A slot you only take from.") Optional<Boolean> output) {}

    /** 打开的界面:脚本拿到的那张表,带着 {@code numen.gui} 里的方法。 */
    @Doc("The window you have open (your own inventory menu when nothing else is): every container and crafting-grid "
            + "slot, and your own filled ones.")
    @Methods("numen.gui")
    public record Window(@Doc("InventoryMenu when it is your own inventory.") String menu,
                         List<WindowSlot> slots,
                         @Doc("What the cursor holds.") Optional<String> cursor,
                         @Doc("The menu's numbers: progress, fuel, energy (meaning is GUI-specific; a furnace's are lit "
                                 + "time, lit duration, cook progress, cook total).") List<Integer> data,
                         @Doc("The block whose window it is, when a click on it opened it.") Optional<BlockAt> block)
            implements Clicks.Pressed {

        /** 同一个界面,点开它的是那一格。 */
        public Window openedAt(BlockAt at) {
            return new Window(menu, slots, cursor, data, Optional.of(at));
        }
    }

    /**
     * 她此刻打开的界面;没开别的就是她自己的背包界面(带 2x2 合成格)。合成格的格与结果格都标出来(原版的 2x2/3x3 与模组的 NxM 一样认:
     * 背着 {@code CraftingContainer} 的槽是格,{@code ResultSlot} 是结果);她自己那一边只列有东西的。
     */
    public static Window window(NumenPlayer self) {
        AbstractContainerMenu menu = self.containerMenu;
        boolean ownInventory = menu == self.inventoryMenu;
        List<WindowSlot> slots = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            ItemStack it = slot.getItem();
            if (slot instanceof ResultSlot) {
                slots.add(slot(i, Side.RESULT, it, true));
            } else if (slot.container instanceof CraftingContainer) {
                slots.add(slot(i, Side.GRID, it, false));
            } else if (slot.container == self.getInventory()) {
                if (!it.isEmpty()) {
                    slots.add(slot(i, Side.YOU, it, false));
                }
            } else {
                // 只能拿不能放的(机器的产出格):有东西、又不收回自己那一样
                slots.add(slot(i, Side.CONTAINER, it, !it.isEmpty() && !slot.mayPlace(it)));
            }
        }
        // 数据槽是界面另一条同步通道:真屏幕画进度、燃料、能量条读的那几个数。一般地读(不按界面特判),意思随界面
        List<DataSlot> data = ((com.dwinovo.numen.mixin.MenuDataSlotsAccessor) (Object) menu).numen$dataSlots();
        List<Integer> numbers = data.stream().map(DataSlot::get).toList();
        return new Window(ownInventory ? "InventoryMenu" : menu.getClass().getSimpleName(), slots,
                menu.getCarried().isEmpty() ? Optional.empty() : Optional.of(describe(menu.getCarried())), numbers,
                Optional.empty());
    }

    private static WindowSlot slot(int index, Side side, ItemStack it, boolean output) {
        return new WindowSlot(index, side,
                it.isEmpty() ? Optional.empty() : Optional.of(BuiltInRegistries.ITEM.getKey(it.getItem()).toString()),
                it.isEmpty() ? Optional.empty() : Optional.of(it.getCount()),
                output ? Optional.of(true) : Optional.empty());
    }

    private static String describe(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + " x" + stack.getCount();
    }

    /** 关掉开着的界面,关了一个是 true;她自己的背包界面总开着,没有别的开着时什么都不做,是 false。 */
    public static boolean close(NumenPlayer self) {
        AbstractContainerMenu menu = self.containerMenu;
        if (menu == null || menu == self.inventoryMenu) {
            return false;
        }
        self.closeContainer();
        return true;
    }
}
