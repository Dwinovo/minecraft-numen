package com.dwinovo.numen.task.interact;
import com.dwinovo.numen.api.task.Activity;
import com.dwinovo.numen.task.MouseButton;

import com.dwinovo.numen.api.sdk.EntityRef;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.task.TaskRecord;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * Typed descriptor for {@code use entity} — the entity-aimed half of the native
 * crosshair interaction (the ENTITY column of vanilla's {@code startAttack}/{@code startUseItem}).
 * Entities are the only MOVING interaction target, so this is the one that auto-paths AND
 * follows the live entity (by the id {@code scan entities} gives) before pressing a button:
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
public final class InteractEntityTaskRecord extends TaskRecord<com.dwinovo.numen.tools.Clicks.Pressed> {

    /** Covers chasing a moving target. */
    private static final long TIMEOUT_TICKS = 60 * 20;

    public final MouseButton button;
    public final EntityRef entity;
    public final int holdTicks;
    public final Item item;        // null → use whatever is in hand; else equip this first (food / shears / weapon)
    public final boolean sneak;
    /** 点在实体身上的哪一点(相对它的位置),先发 INTERACT_AT;不指定为 null。 */
    public final net.minecraft.world.phys.Vec3 at;

    public InteractEntityTaskRecord(ServerCall source, MouseButton button, EntityRef entity, int holdTicks, Item item,
                                    boolean sneak, net.minecraft.world.phys.Vec3 at) {
        super(source, source.her().level().getGameTime() + TIMEOUT_TICKS);
        this.button = button;
        this.entity = entity;
        this.holdTicks = holdTicks;
        this.item = item;
        this.sneak = sneak;
        this.at = at;
    }

    @Override
    public String describe() {
        return getToolName() + " " + (button == MouseButton.LEFT ? "left" : "right")
                + (item != null ? " " + BuiltInRegistries.ITEM.getKey(item).getPath() : "")
                + " " + entity + (holdTicks != 0 ? " hold=" + holdTicks : "") + (sneak ? " sneak" : "");
    }

    /** 给主人看的一句(头顶气泡、档案页),客户端按当前语言翻;{@link #describe()} 是给模型的。 */
    @Override
    public Activity activity() {
        return Activity.of("numen.activity.click_entity_" + (button == MouseButton.LEFT ? "left" : "right"),
                entity.label());
    }
}
