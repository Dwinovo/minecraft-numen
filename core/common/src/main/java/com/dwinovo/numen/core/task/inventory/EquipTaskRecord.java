package com.dwinovo.numen.core.task.inventory;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.world.item.Item;

/**
 * Typed task descriptor for {@code gear wear}: "take this item out of my inventory and wear/wield it."
 * Completes in a single tick — no pathing.
 *
 * <p>{@link #slot} is {@code null} for auto-choosing, or a slot name the LLM forces:
 * {@code mainhand}, {@code offhand} or a name from {@code <worn>}. Slot names depend on the
 * body (mods add slots), so they are resolved when the task runs, not here.
 */
public final class EquipTaskRecord extends TaskRecord {

    /** 穿脱都是当场的事,期限只防卡死,给得宽一点。{@link UnequipTaskRecord} 用同一个。 */
    static final long TIMEOUT_TICKS = 5 * 20;

    /** The item to equip (must be in the 36 backpack slots). */
    public final Item item;
    /** Target slot name, or {@code null} to auto-choose. */
    public final String slot;
    /** Human-readable label for messages / debug overlay (e.g. "wooden_pickaxe"). */
    public final String label;

    public EquipTaskRecord(ServerSource source, Item item, String slot, String label) {
        super(source, source.companion().level().getGameTime() + TIMEOUT_TICKS);
        this.item = item;
        this.slot = slot;
        this.label = label;
    }

    @Override
    public String describe() {
        return getToolName() + " " + label;
    }
}
