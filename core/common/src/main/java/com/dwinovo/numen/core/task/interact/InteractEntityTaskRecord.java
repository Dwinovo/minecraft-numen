package com.dwinovo.numen.core.task.interact;
import com.dwinovo.numen.core.task.MouseButton;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * Typed descriptor for {@code use entity} — the entity-aimed half of the native
 * crosshair interaction (the ENTITY column of vanilla's {@code startAttack}/{@code startUseItem}).
 * Entities are the only MOVING interaction target, so this is the one that auto-paths AND
 * follows the live entity (by id from {@code scan_nearby_entities}) before pressing a button:
 * <ul>
 *   <li>{@link Button#LEFT} (attack): hit it. Tap = one cooldown-gated hit; hold = keep
 *       hitting until the target dies, the hold ends, or the task times out.</li>
 *   <li>{@link Button#RIGHT} (use): interact — trade / breed / mount / shear / name with the
 *       held item; hold = a modded entity needing continuous right-click.</li>
 * </ul>
 * The hit only lands when the native raytrace actually REACHES the entity (a wall in between
 * blocks it — we re-position rather than hit through it). {@code holdTicks}: 0 = tap, &gt;0 =
 * hold N ticks, -1 = hold until done (dead / self-complete) or timeout.
 */
public final class InteractEntityTaskRecord extends TaskRecord {

    /** Covers chasing a moving target. */
    private static final long TIMEOUT_TICKS = 60 * 20;

    public final MouseButton button;
    public final int entityId;
    public final int holdTicks;
    public final Item item;        // null → use whatever is in hand; else equip this first (food / shears / weapon)

    public InteractEntityTaskRecord(ServerSource source, MouseButton button, int entityId, int holdTicks, Item item) {
        super(source, source.companion().level().getGameTime() + TIMEOUT_TICKS);
        this.button = button;
        this.entityId = entityId;
        this.holdTicks = holdTicks;
        this.item = item;
    }

    @Override
    public String describe() {
        return getToolName() + " " + (button == MouseButton.LEFT ? "left" : "right")
                + (item != null ? " " + BuiltInRegistries.ITEM.getKey(item).getPath() : "")
                + " entity#" + entityId + (holdTicks != 0 ? " hold=" + holdTicks : "");
    }
}
