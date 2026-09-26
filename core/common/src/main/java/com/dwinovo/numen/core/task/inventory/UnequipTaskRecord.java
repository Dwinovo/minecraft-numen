package com.dwinovo.numen.core.task.inventory;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.world.item.Item;

/**
 * {@code gear remove} 的任务描述:把身上的东西收回背包。单 tick 完成,无寻路。
 *
 * <p>按 {@link #slot}(一个槽名,或 {@code armor} 指四件甲)摘,或按 {@link #item} 从戴着它的格子摘;
 * 两个都给就只摘这些槽里戴着这件的。槽名随身体而定,执行时才解析。
 */
public final class UnequipTaskRecord extends TaskRecord {

    /** 槽名;{@code null} = 按 {@link #item} 找。 */
    public final String slot;
    /** 只摘戴着这件的;{@code null} = 这些槽里有什么摘什么。 */
    public final Item item;
    /** Human-readable label for messages / debug overlay:槽名或物品名。 */
    public final String label;

    public UnequipTaskRecord(ServerSource source, String slot, Item item, String label) {
        super(source, source.companion().level().getGameTime() + EquipTaskRecord.TIMEOUT_TICKS);
        this.slot = slot;
        this.item = item;
        this.label = label;
    }

    @Override
    public String describe() {
        return getToolName() + " " + label;
    }
}
