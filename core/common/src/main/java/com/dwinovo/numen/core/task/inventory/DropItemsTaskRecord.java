package com.dwinovo.numen.core.task.inventory;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.world.item.Item;

/**
 * Typed task descriptor for {@code inv drop}: toss {@code count} of
 * {@code item} out of the entity's inventory onto the ground.
 */
public final class DropItemsTaskRecord extends TaskRecord {

    private static final long TIMEOUT_TICKS = 10 * 20;

    public final Item item;
    public final int count;
    public final String label;

    public DropItemsTaskRecord(ServerSource source, Item item, int count, String label) {
        super(source, source.companion().level().getGameTime() + TIMEOUT_TICKS);
        this.item = item;
        this.count = count;
        this.label = label;
    }

    @Override
    public String describe() {
        return getToolName() + " " + count + "x " + label;
    }
}
