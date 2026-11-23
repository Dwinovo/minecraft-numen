package com.dwinovo.numen.task.inventory;

import com.dwinovo.numen.api.entity.Stacks;
import com.dwinovo.numen.api.task.Activity;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.task.TaskRecord;

/**
 * Typed task descriptor for {@code gear wear} and {@code gear hold}: "take this item out of my inventory and
 * wear/wield it."
 * Completes in a single tick — no pathing.
 *
 * <p>{@link #slot} is {@code null} for auto-choosing a worn slot, a hand ({@code mainhand}, {@code offhand},
 * from {@code gear hold}) or a name from {@code <worn>}. Slot names depend on the
 * body (mods add slots), so they are resolved when the task runs, not here.
 */
public final class EquipTaskRecord extends TaskRecord<com.dwinovo.numen.gear.Wardrobe.Change> {

    /** 穿脱都是当场的事,期限只防卡死,给得宽一点。{@link UnequipTaskRecord} 用同一个。 */
    static final long TIMEOUT_TICKS = 5 * 20;

    /** The kind to equip (must be in the 36 backpack slots). */
    public final Stacks.Which which;
    /** Target slot name, or {@code null} to auto-choose. */
    public final String slot;
    /** Human-readable label for messages / debug overlay (e.g. "wooden_pickaxe"). */
    public final String label;

    public EquipTaskRecord(ServerCall source, Stacks.Which which, String slot, String label) {
        super(source, source.her().level().getGameTime() + TIMEOUT_TICKS);
        this.which = which;
        this.slot = slot;
        this.label = label;
    }

    @Override
    public String describe() {
        return getToolName() + " " + label;
    }

    /** 给主人看的一句(头顶气泡、档案页),客户端按当前语言翻;{@link #describe()} 是给模型的。 */
    @Override
    public Activity activity() {
        return Activity.of("numen.activity.equip", label);
    }
}
