package com.dwinovo.numen.plugins.minecraft.task.inventory;

import com.dwinovo.numen.plugins.minecraft.FailureType;
import com.dwinovo.numen.plugins.minecraft.PlayerInv;
import com.dwinovo.numen.plugins.minecraft.task.base.AbstractCompanionTask;
import com.dwinovo.numen.plugins.minecraft.tools.ContainerOps;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.task.TaskState;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在她打开的界面里按种类搬东西,是 {@code StationCompanionTask} 的子活:一刻一步({@link ContainerOps}),整叠够数就整叠挪到另一边,
 * 只要一部分就放进另一边一格空的或同样东西的格;界面那一侧限了格时,放进去总是落在那几格里。搬东西这一步不过权限层:能不能动这个容器
 * 里的东西,问在右键打开它的那一下。搬够了、这一种没了、另一边放不下了就收场,回执说搬了几件、为什么停在那儿。
 */
public final class GuiItemsCompanionTask extends AbstractCompanionTask<GuiItemsTaskRecord, Integer> {

    private int moved;
    /** 搬了的东西,按种类记件数,先搬的在前。 */
    private final Map<Item, Integer> kinds = new LinkedHashMap<>();
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
            // 取下来的东西让界面自己合上了(讲台的书取下,原版就关掉讲台):已经搬的算数
            if (moved > 0) {
                return finish();
            }
            fail("it closed before the work was done", FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        int left = r.count == null ? Integer.MAX_VALUE : r.count - moved;
        Slot from = left <= 0 ? null : source(menu);
        if (from == null) {
            return finish();
        }
        ContainerOps.Move move;
        // 界面那一侧限了格的放:落在哪一格由这次调用定,不走"整叠挪到另一边"(那是界面自己分)
        if (from.getItem().getCount() <= left && !(r.put && r.slotted())) {
            move = new ContainerOps.Move(from.index, null, null);
        } else {
            Slot to = target(menu, from.getItem());
            if (to == null) {
                stopped = noRoom();
                return finish();
            }
            move = new ContainerOps.Move(from.index, to.index, Math.min(left, from.getItem().getCount()));
        }
        // 搬了几件看她背包里这一种变了多少,不看搬出来的那一格少了多少:取走一件就又出一件的格(切石机的成品格,按住 Shift
        // 点它会一直切到料用完)那一格只少一叠,到她手里的是全部
        Item kind = from.getItem().getItem();
        int before = PlayerInv.count(player.getInventory(), kind);
        ContainerOps.step(move, player);
        int step = (PlayerInv.count(player.getInventory(), kind) - before) * (r.put ? -1 : 1);
        if (step <= 0) {
            stopped = noRoom();
            return finish();
        }
        moved += step;
        kinds.merge(kind, step, Integer::sum);
        return TaskState.RUNNING;
    }

    /** 这一步搬不动时说的那句:拿是她的背包放不下,放是对方满了或不收这种东西。 */
    private String noRoom() {
        if (!r.put) {
            return "your inventory has no room for " + itemName();
        }
        return (r.slotted() ? "that slot" : "it") + " is full or does not take " + itemName();
    }

    /** 搬够了、搬不动了:搬了的照实说;一件都没搬是失败。 */
    private TaskState finish() {
        if (moved == 0) {
            boolean none = stopped == null;
            fail(none ? (r.put ? "you carry no " + itemName() : "it holds no " + itemName())
                    : stopped, none ? FailureType.TARGET_LOST : FailureType.NO_SPACE);
            return TaskState.FAILED;
        }
        done = (r.put ? "put " : "took ") + moved + " " + itemName() + (r.put ? " in" : " out")
                + (r.count != null && moved < r.count ? " (" + r.count + " asked; "
                + (stopped != null ? stopped : (r.put ? "you had no more" : "it held no more")) + ")" : "") + ".";
        succeed();
        return TaskState.SUCCESS;
    }

    /** 搬出来的那一侧里第一格这种东西;没有是 null。放进去从她的背包搬,拿出来从界面那一侧搬。 */
    private Slot source(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (sourceSide(slot) && wanted(slot)) {
                return slot;
            }
        }
        return null;
    }

    /** 搬出来的那一侧的这一格里是不是这次要搬的:是要的那一种(不点名就是有东西),在界面那一侧时还得是限定的那几格。 */
    private boolean wanted(Slot slot) {
        ItemStack stack = slot.getItem();
        boolean kind = r.which == null ? !stack.isEmpty() : r.which.matches(stack);
        return kind && (r.put || r.covers(slot.index));
    }

    /** 另一侧能放下一部分的一格:空的,或同样东西还没满;放进界面时还得是限定的那几格。没有是 null。 */
    private Slot target(AbstractContainerMenu menu, ItemStack stack) {
        for (Slot slot : menu.slots) {
            if (sourceSide(slot) || slot instanceof ResultSlot || !slot.mayPlace(stack)
                    || (r.put && !r.covers(slot.index))) {
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

    private String itemName() {
        return r.itemName();
    }

    /** 搬了的东西,按种类记件数,先搬的在前;一件都没搬是空表。 */
    public Map<Item, Integer> kinds() {
        return Collections.unmodifiableMap(kinds);
    }

    /** 搬了几件。 */
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
