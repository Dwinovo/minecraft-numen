package com.dwinovo.numen.core.task.inventory;

import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.List;

/**
 * {@code equip_item action=unequip} 的任务描述:把这些槽位上的东西收回背包。
 * 与 {@link EquipTaskRecord} 是同一个工具的两个形态——对象都是自己的装备栏,
 * 动词进参数,不另开工具。单 tick 完成,无寻路。
 *
 * <p>{@link #slots} 是一个槽位,或 {@code slot=armor} 展开的四件甲。
 */
public final class UnequipTaskRecord extends TaskRecord {

    /** Human-readable label for messages / debug overlay: "armor" 或单个槽位名。 */
    public final String label;
    public final List<EquipmentSlot> slots;

    public UnequipTaskRecord(String toolCallId, long deadlineGameTime,
                             List<EquipmentSlot> slots, String label) {
        super(EquipTaskRecord.TOOL_NAME, toolCallId, deadlineGameTime);
        this.slots = List.copyOf(slots);
        this.label = label;
    }

    @Override
    public String describe() {
        return EquipTaskRecord.TOOL_NAME + " unequip " + label;
    }
}
