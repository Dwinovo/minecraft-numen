package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.act.MenuOrigin;
import com.dwinovo.numen.core.task.inventory.TransferTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;


/**
 * 在打开的界面里搬东西的业务半边:{@code use transfer}(一格挪到另一格:挪、并、换)与 {@code use shift}(整叠挪到另一边,
 * 像按住 Shift 点它)。一次调用一步({@link Move}),作为有界短活跑({@code TransferCompanionTask}):从容器里拿东西的那一步
 * 和别的身体动作一样,可能要等主人点头。
 */
public final class ContainerOps {

    /** Generous for clicks that normally land in one tick. */
    private static final long TRANSFER_TIMEOUT_TICKS = 10 * 20;

    /**
     * 搬一步。
     *
     * @param to    放到哪一格;null 是整叠挪到另一边({@code use shift})
     * @param count 挪几个;null 是整叠。只和 {@code to} 一起用
     */
    public record Move(int from, Integer to, Integer count) {}

    /** 这一步交任务槽的那件活:点击通常一刻就完,期限只为等主人点头之外的意外留着。 */
    public static TaskRecord transfer(ServerSource source, Move move) {
        return new TransferTaskRecord(source, source.companion().level().getGameTime() + TRANSFER_TIMEOUT_TICKS, move);
    }

    /**
     * 这一步会不会从容器里拿东西进她的背包:会就是那个 {@code take} 动作,不会是 null。
     *
     * <p>拿 = 东西离开界面里不是她背包的那一段,落进她的背包——整叠分流过去({@code to} 省略)、放到她背包的
     * 某一格,或者整叠对换时换回来的那一叠。她自己的背包界面(2x2 合成格)是她的沙盒,不算;右键没有方块实体的
     * 方块(工作台、切石机)开的界面里,格子里只有她自己刚放进去的东西,也不算。容器是哪一格从
     * {@link MenuOrigin} 取;右键实体开的、来历不明的,动作不带格子。
     */
    public static Action taking(Move m, NumenPlayer self) {
        AbstractContainerMenu menu = self.containerMenu;
        int max = menu.slots.size() - 1;
        if (menu == self.inventoryMenu || m.from() < 0 || m.from() > max
                || (m.to() != null && (m.to() < 0 || m.to() > max))) {
            return null;
        }
        Slot from = menu.slots.get(m.from());
        Slot to = m.to() == null ? null : menu.slots.get(m.to());
        ItemStack taken = ItemStack.EMPTY;
        if (!mine(from, self) && (to == null || mine(to, self))) {
            taken = from.getItem();
        } else if (mine(from, self) && to != null && !mine(to, self) && (m.count() == null || m.count() <= 0)
                && !from.getItem().isEmpty() && !sameItem(to.getItem(), from.getItem())) {
            taken = to.getItem();
        }
        if (taken.isEmpty()) {
            return null;
        }
        BlockPos block = MenuOrigin.block(self);
        if (block == null) {
            return Action.take(null, null, taken.getItem());
        }
        BlockState state = self.level().getBlockState(block);
        return state.hasBlockEntity() ? Action.take(block, state, taken.getItem()) : null;
    }

    private static boolean mine(Slot slot, NumenPlayer self) {
        return slot.container == self.getInventory();
    }

    /** 执行一步,回执这一步的一行(不带序号)。 */
    public String step(Move m, NumenPlayer self) {
        AbstractContainerMenu menu = self.containerMenu;
        int max = menu.slots.size() - 1;
        int from = m.from();
        Integer to = m.to();
        Integer count = m.count();
        if (from < 0 || from > max) {
            return "from slot " + from + " OUT OF RANGE (0.." + max + ") — skipped; use gui for indices.";
        }
        if (to != null && (to < 0 || to > max)) {
            return "to slot " + to + " OUT OF RANGE (0.." + max + ") — skipped; use gui for indices.";
        }
        try {
            return to == null ? route(menu, self, from) : place(menu, self, from, to, count);
        } catch (RuntimeException ex) {
            return "slot " + from + " — ERROR: " + ex.getMessage();
        }
    }

    /** No destination: shift the whole stack to the other section, menu-routed (deposit/take/feed). */
    private static String route(AbstractContainerMenu menu, NumenPlayer entity, int from) {
        ItemStack before = menu.slots.get(from).getItem().copy();
        if (before.isEmpty()) {
            if (menu.slots.get(from) instanceof ResultSlot) {
                // Empty crafting result = the grid doesn't form a valid recipe (usually a mis-placed
                // 2x2 layout). Point the model back at the recipe so it self-corrects.
                return "slot " + from + " (crafting result) is empty — the grid doesn't form a valid "
                        + "recipe yet. Run inv recipe for the exact layout, then use gui and match "
                        + "it onto the grid cell-for-cell (a smaller recipe goes top-left; 2x2 slot "
                        + "indices are easy to guess wrong).";
            }
            return "slot " + from + " is empty — nothing to move.";
        }
        menu.clicked(from, 0, ClickType.QUICK_MOVE, entity);
        ItemStack after = menu.slots.get(from).getItem();
        int moved = before.getCount() - (sameItem(before, after) ? after.getCount() : 0);
        if (moved <= 0) {
            return "slot " + from + " (" + name(before) + ") didn't move — the other section is full "
                    + "or won't accept it.";
        }
        return "routed " + moved + " " + name(before) + " from slot " + from + " to the other section "
                + "(deposit/take/feed)." + (after.isEmpty() ? "" : " " + after.getCount() + " left in slot "
                + from + ".");
    }

    /** A destination slot: place exactly there — empty→move, same item→merge, different item→swap. */
    private static String place(AbstractContainerMenu menu, NumenPlayer entity, int from, int to, Integer count) {
        if (from == to) {
            return "slot " + from + " → itself — nothing to do.";
        }
        ItemStack fromBefore = menu.slots.get(from).getItem().copy();
        ItemStack toBefore = menu.slots.get(to).getItem().copy();
        if (fromBefore.isEmpty()) {
            return "slot " + from + " is empty — nothing to move.";
        }

        boolean exact = count != null && count > 0;
        boolean differentItem = !toBefore.isEmpty() && !sameItem(toBefore, fromBefore);

        if (exact && differentItem) {
            return "can't move " + count + " from slot " + from + " — slot " + to + " holds "
                    + name(toBefore) + ". Omit count to swap the whole stacks instead.";
        }

        if (exact) {
            int want = Math.min(count, fromBefore.getCount());
            MenuOps.dripInto(menu, entity, from, to, want);
        } else {
            menu.clicked(from, 0, ClickType.PICKUP, entity);          // grab the stack
            menu.clicked(to, 0, ClickType.PICKUP, entity);            // place / merge / swap
            if (!menu.getCarried().isEmpty()) {
                menu.clicked(from, 0, ClickType.PICKUP, entity);      // settle leftover / swapped item back
            }
        }

        ItemStack fromAfter = menu.slots.get(from).getItem();
        ItemStack toAfter = menu.slots.get(to).getItem();
        int moved = fromBefore.getCount() - (sameItem(fromBefore, fromAfter) ? fromAfter.getCount() : 0);

        if (differentItem) {     // whole-stack onto a different item = swap
            if (sameItem(toAfter, fromBefore) && sameItem(fromAfter, toBefore)) {
                return "swapped slot " + from + " (" + name(fromBefore) + ") ⇄ slot " + to + " ("
                        + name(toBefore) + ").";
            }
            return "slot " + from + " → " + to + ": nothing moved — slot " + to + " refused it "
                    + "(output-only slot?).";
        }
        if (moved <= 0) {
            return "slot " + from + " → " + to + ": nothing moved — slot " + to
                    + (toBefore.isEmpty() ? " refused it (output-only slot?)." : " is already full.");
        }
        String verb = toBefore.isEmpty() ? "moved " : "merged ";
        return verb + moved + " " + name(fromBefore) + " from slot " + from + " to slot " + to
                + " (slot " + to + " now " + toAfter.getCount()
                + (fromAfter.isEmpty() ? "; slot " + from + " emptied)." : "; " + fromAfter.getCount()
                + " left in slot " + from + ").");
    }

    private static boolean sameItem(ItemStack a, ItemStack b) {
        return ItemStack.isSameItemSameComponents(a, b);
    }

    private static String name(ItemStack stack) {
        return stack.isEmpty() ? "nothing" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }
}
