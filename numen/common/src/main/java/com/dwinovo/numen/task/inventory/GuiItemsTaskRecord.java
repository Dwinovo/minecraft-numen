package com.dwinovo.numen.task.inventory;

import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.task.TaskRecord;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * 在她打开的界面里搬东西(放进去、拿出来):这一种全搬,或搬够 {@code count} 件。界面那一侧可以只限几格(熔炉的燃料格、产物格),
 * 拿的时候可以不点名是哪一种({@code item} 为 null:那几格里有什么拿什么)。只当 {@code StationCompanionTask} 的子活派,不单独进任务槽。
 *
 * @see GuiItemsCompanionTask
 */
public final class GuiItemsTaskRecord extends TaskRecord<Integer> {

    /** 搬哪一种;null 是不论哪一种(只在拿的时候)。 */
    public final Item item;
    /** 搬几件;null 是这一种全搬。 */
    public final Integer count;
    /** 从她的背包放进界面那一侧;false 是从界面那一侧拿进她的背包。 */
    public final boolean put;
    /** 界面那一侧只动这几格(界面的格子编号);null 是那一侧的每一格,放进哪一格由界面自己分。 */
    private final int[] slots;

    public GuiItemsTaskRecord(ServerCall source, long deadlineGameTime, Item item, Integer count, boolean put,
                              int[] slots) {
        super(source, deadlineGameTime);
        if (item == null && put) {
            throw new IllegalArgumentException("putting needs an item");
        }
        this.item = item;
        this.count = count;
        this.put = put;
        this.slots = slots == null ? null : slots.clone();
    }

    /** 界面那一侧的这一格归不归这次搬。 */
    public boolean covers(int slot) {
        if (slots == null) {
            return true;
        }
        for (int s : slots) {
            if (s == slot) {
                return true;
            }
        }
        return false;
    }

    /** 界面那一侧限了格:放进去时落在哪一格由这次调用定,不交给界面分。 */
    public boolean slotted() {
        return slots != null;
    }

    /** 这次搬的东西叫什么;不点名是哪一种时是 {@code items}。 */
    public String itemName() {
        return item == null ? "items" : BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
