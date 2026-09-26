package com.dwinovo.numen.core.task.inventory;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.world.item.Item;

/**
 * Typed task descriptor for {@code inv eat}: consume a food / drink from the
 * companion's inventory. {@link EatCompanionTask} eats it natively (the player
 * body's own held-use path), so hunger + saturation + consume effects apply
 * exactly as they do for a real player when the chew completes.
 */
public final class EatItemTaskRecord extends TaskRecord {

    /** Generous — covers any food's eat duration (most ~1.6s) plus buffer. */
    private static final long TIMEOUT_TICKS = 15 * 20;

    /** The food item to eat (one is consumed on completion). */
    public final Item item;
    /** Human-readable label for messages / debug overlay (e.g. "golden_apple"). */
    public final String label;

    public EatItemTaskRecord(ServerSource source, Item item, String label) {
        super(source, source.companion().level().getGameTime() + TIMEOUT_TICKS);
        this.item = item;
        this.label = label;
    }

    @Override
    public String describe() {
        return getToolName() + " " + label;
    }
}
