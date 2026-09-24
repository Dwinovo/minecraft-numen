package com.dwinovo.numen.core.task.inventory;

import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.world.item.Item;

/**
 * Typed task descriptor for the {@code equip} tool: "take this item out of my
 * inventory and wear/wield it." Completes in a single tick — no pathing.
 *
 * <p>{@link #slot} is {@code null} for auto-choosing, or a slot name the LLM forces:
 * {@code mainhand}, {@code offhand} or a name from {@code <worn>}. Slot names depend on the
 * body (mods add slots), so they are resolved when the task runs, not here.
 */
public final class EquipTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "equip_item";

    /** The item to equip (must be in the 36 backpack slots). */
    public final Item item;
    /** Target slot name, or {@code null} to auto-choose. */
    public final String slot;
    /** Human-readable label for messages / debug overlay (e.g. "wooden_pickaxe"). */
    public final String label;

    public EquipTaskRecord(String toolCallId, long deadlineGameTime,
                           Item item, String slot, String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.item = item;
        this.slot = slot;
        this.label = label;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + label;
    }
}
