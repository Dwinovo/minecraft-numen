package com.dwinovo.numen.core.task.interact;
import com.dwinovo.numen.core.task.MouseButton;

import com.dwinovo.numen.sdk.ServerCall;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * Typed descriptor for {@code use entity} — the entity-aimed half of the native
 * crosshair interaction (the ENTITY column of vanilla's {@code startAttack}/{@code startUseItem}).
 * Entities are the only MOVING interaction target, so this is the one that auto-paths AND
 * follows the live entity (by id from {@code scan entities}) before pressing a button:
 * <ul>
 *   <li>{@link Button#LEFT} (attack): hit it. Tap = one cooldown-gated hit; hold = keep
 *       hitting until the target dies, the hold ends, or the task times out.</li>
 *   <li>{@link Button#RIGHT} (use): interact — trade / breed / mount / shear / name with the
 *       held item; hold = a modded entity needing continuous right-click.</li>
 * </ul>
 * The hit only lands when the native raytrace actually REACHES the entity (a wall in between
 * blocks it — we re-position rather than hit through it). {@code holdTicks}: 0 = tap, &gt;0 =
 * hold N ticks, -1 = hold until done (dead / self-complete) or timeout. {@code sneak}: hold sneak while pressing
 * ({@code --sneak}).
 */
public final class InteractEntityTaskRecord extends TaskRecord {

    /** Covers chasing a moving target. */
    private static final long TIMEOUT_TICKS = 60 * 20;

    public final MouseButton button;
    public final int entityId;
    public final int holdTicks;
    public final Item item;        // null → use whatever is in hand; else equip this first (food / shears / weapon)
    public final boolean sneak;

    public InteractEntityTaskRecord(ServerCall source, MouseButton button, int entityId, int holdTicks, Item item,
                                    boolean sneak) {
        super(source, source.her().level().getGameTime() + TIMEOUT_TICKS);
        this.button = button;
        this.entityId = entityId;
        this.holdTicks = holdTicks;
        this.item = item;
        this.sneak = sneak;
    }

    @Override
    public String describe() {
        return getToolName() + " " + (button == MouseButton.LEFT ? "left" : "right")
                + (item != null ? " " + BuiltInRegistries.ITEM.getKey(item).getPath() : "")
                + " entity#" + entityId + (holdTicks != 0 ? " hold=" + holdTicks : "") + (sneak ? " sneak" : "");
    }
}
