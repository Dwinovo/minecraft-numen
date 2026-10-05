package com.dwinovo.numen.core.task.inventory;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.tools.ContainerOps;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.task.TaskState;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;


/**
 * 在她打开的界面里按种类搬东西:一刻一步,和 {@code gui move}/{@code gui quick} 同一种搬法({@link ContainerOps}),整叠够数就
 * 整叠挪到另一边,只要一部分就放进另一边一格空的或同样东西的格。从容器里拿出来的那一步点下去之前交给权限层,和 {@code gui quick}
 * 一样。搬够了、这一种没了、另一边放不下了就收场,回执说搬了几件、为什么停在那儿。
 */
public final class GuiItemsCompanionTask extends AbstractCompanionTask<GuiItemsTaskRecord> {

    private final ContainerOps ops = new ContainerOps();
    private int moved;
    /** 没搬够就停下时的缘由;搬够了或这一种搬完了是 null。 */
    private String stopped;
    private String done = "done";

    public GuiItemsCompanionTask(NumenPlayer player, GuiItemsTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
    }

    @Override
    protected TaskState onTick() {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu) {
            fail("no window is open: open a chest, a furnace or a machine first (numen.use.block on it)",
                    FailureType.UNSUPPORTED);
            return TaskState.FAILED;
        }
        int left = r.count == null ? Integer.MAX_VALUE : r.count - moved;
        Slot from = left <= 0 ? null : source(menu);
        if (from == null) {
            return finish();
        }
        ContainerOps.Move move;
        if (from.getItem().getCount() <= left) {
            move = new ContainerOps.Move(from.index, null, null);
        } else {
            Slot to = target(menu, from.getItem());
            if (to == null) {
                stopped = "there is no room on the other side for part of a stack";
                return finish();
            }
            move = new ContainerOps.Move(from.index, to.index, left);
        }
        Action take = ContainerOps.taking(move, player);
        if (take != null) {
            Permit permit = permit(take);
            if (permit.state() == PermitState.WAITING) {
                player.controls().stop();
                return TaskState.RUNNING;
            }
            if (permit.state() == PermitState.REFUSED) {
                fail("did not take " + itemName() + ": " + permit.refusal(), FailureType.REFUSED);
                return TaskState.FAILED;
            }
        }
        int before = onSource(menu);
        ops.step(move, player);
        int step = before - onSource(menu);
        if (step <= 0) {
            stopped = "the other side is full or does not take " + itemName();
            return finish();
        }
        moved += step;
        return TaskState.RUNNING;
    }

    /** 搬够了、搬不动了:搬了的照实说;一件都没搬是失败。 */
    private TaskState finish() {
        if (moved == 0) {
            boolean none = stopped == null;
            fail(none ? (r.put ? "you carry no " + itemName() : "the window holds no " + itemName())
                    : stopped, none ? FailureType.TARGET_LOST : FailureType.NO_SPACE);
            return TaskState.FAILED;
        }
        done = (r.put ? "put " : "took ") + moved + " " + itemName() + (r.put ? " into the window" : " out of the window")
                + (r.count != null && moved < r.count ? " (" + r.count + " asked; "
                + (stopped != null ? stopped : (r.put ? "you had no more" : "it held no more")) + ")" : "") + ".";
        succeed();
        return TaskState.SUCCESS;
    }

    /** 搬出来的那一侧里第一格这种东西;没有是 null。放进去从她的背包搬,拿出来从界面那一侧搬。 */
    private Slot source(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (sourceSide(slot) && slot.getItem().is(r.item)) {
                return slot;
            }
        }
        return null;
    }

    /** 另一侧能放下一部分的一格:空的,或同样东西还没满。没有是 null。 */
    private Slot target(AbstractContainerMenu menu, ItemStack stack) {
        for (Slot slot : menu.slots) {
            if (sourceSide(slot) || slot instanceof ResultSlot || !slot.mayPlace(stack)) {
                continue;
            }
            ItemStack there = slot.getItem();
            if (there.isEmpty() || ItemStack.isSameItemSameComponents(there, stack)
                    && there.getCount() < slot.getMaxStackSize(there)) {
                return slot;
            }
        }
        return null;
    }

    private boolean sourceSide(Slot slot) {
        return (slot.container == player.getInventory()) == r.put;
    }

    /** 搬出来的那一侧还有几件这种东西。 */
    private int onSource(AbstractContainerMenu menu) {
        int n = 0;
        for (Slot slot : menu.slots) {
            if (sourceSide(slot) && slot.getItem().is(r.item)) {
                n += slot.getItem().getCount();
            }
        }
        return n;
    }

    private String itemName() {
        return BuiltInRegistries.ITEM.getKey(r.item).getPath();
    }

    /** 搬了几件:{@code numen.gui.put}、{@code numen.gui.take} 交回的值。 */
    @Override
    protected Integer value() {
        return moved;
    }

    /** No nav / overlay to release. */
    @Override
    protected void cleanup() {}

    @Override
    protected String successMessage() {
        return done;
    }

    @Override
    protected String timeoutMessage() {
        return "timed out after moving " + moved + " " + itemName();
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted after moving " + moved + " " + itemName();
    }
}
