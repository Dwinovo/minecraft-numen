package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.pathing.exec.Interaction;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.base.GoToThenDoTask;
import com.dwinovo.numen.core.task.base.Precondition;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code interact_entity} on the player body — the entity-aimed native interaction. Auto-paths
 * and FOLLOWS the live entity (the only moving interaction target), then aims at it and presses
 * the requested mouse button — but only when the native raytrace actually REACHES the entity
 * (a wall in between blocks it, and we re-position instead of hitting through it, which diverges
 * from Carpet's "hit whatever the ray returns" — deliberate: this tool means "act on THIS
 * entity", not "grief the wall it hid behind"). attack+hold = keep hitting until dead (= hunt).
 */
public final class InteractEntityCompanionTask extends GoToThenDoTask<InteractEntityTaskRecord> {

    private static final double REACH = 3.0;            // vanilla entity interaction range
    private static final double REACH_SQR = REACH * REACH;
    private static final double WALK_SPEED = 1.0;

    private Entity entity;
    private Interaction interaction;
    private long holdUntil = -1;
    private boolean acted = false;     // landed at least one press (death then = success, not failure)
    private String successMsg = "done";

    public InteractEntityCompanionTask(NumenPlayer player, InteractEntityTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(
                // Resolve + cache the target; fail fast if it despawned / moved out of range.
                () -> {
                    entity = ((ServerLevel) player.level()).getEntity(r.entityId);
                    return (entity == null || !entity.isAlive())
                            ? new Precondition.Failure("no entity with id " + r.entityId
                                    + " nearby (it may have despawned or moved out of range)",
                                    FailureType.TARGET_LOST)
                            : null;
                },
                () -> r.item == null || PlayerInv.count(player.getInventory(), r.item) > 0 ? null
                        : new Precondition.Failure("don't have "
                                + BuiltInRegistries.ITEM.getKey(r.item).getPath() + " to use on it",
                                FailureType.NO_MATERIAL));
    }

    @Override
    protected PlayerNav buildNav() {
        // Arrival = within reach AND a clear line of sight: nav keeps walking (toward the entity)
        // until BOTH hold, so a wall between us and the target is cleared by re-positioning rather
        // than stood in front of forever.
        return new PlayerNav(player, () -> entity.blockPosition(), WALK_SPEED, this::inReachAndLos);
    }

    /** Act this tick when the target is gone (report the outcome), a fixed hold has elapsed, or we're
     *  in reach with a clear line of sight; otherwise the base drives the nav to follow the entity. */
    @Override
    protected boolean reached() {
        return entity == null || !entity.isAlive()
                || (interaction != null && holdUntil >= 0 && player.level().getGameTime() >= holdUntil)
                || inReachAndLos();
    }

    @Override
    protected TaskState act() {
        // Target gone: death is success for an attack that landed (the old hunt's contract); otherwise
        // the target slipped away before we could touch it.
        if (entity == null || !entity.isAlive()) {
            if (acted) {
                successMsg = r.button == InteractEntityTaskRecord.Button.LEFT
                        ? "defeated " + name() : "done with " + name();
                return TaskState.SUCCESS;
            }
            fail("the target entity is gone before I could reach it", FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }

        // A fixed-duration hold completes on time even if the line of sight lapsed near the end.
        if (interaction != null && holdUntil >= 0 && player.level().getGameTime() >= holdUntil) {
            interaction.stop();
            successMsg = describeDone();
            return TaskState.SUCCESS;
        }

        // In reach + LOS: aim at the entity and confirm the crosshair actually resolves to IT
        // (e.g. not another entity wandered into the exact line) before pressing.
        InputDriver.lookAt(player, entity.getEyePosition());
        HitResult hit = Interaction.nativeRaytrace(player, REACH);
        boolean onTarget = hit.getType() == HitResult.Type.ENTITY
                && ((EntityHitResult) hit).getEntity() == entity;
        if (!onTarget) {
            return TaskState.RUNNING;   // settling / something briefly in the line — re-aim next tick
        }

        if (interaction == null) {
            if (r.item != null) {
                player.holdInHand(PlayerInv.findSlot(player.getInventory(), r.item));
            }
            interaction = Interaction.forHit(player, hit, button(), r.holdTicks);
            if (r.holdTicks > 0) {
                holdUntil = player.level().getGameTime() + r.holdTicks;
            }
        }
        acted = true;

        return switch (interaction.tick()) {
            case DONE -> {
                successMsg = describeDone();
                yield TaskState.SUCCESS;
            }
            case FAILED -> {
                fail(interaction.failReason(), FailureType.UNKNOWN);
                yield TaskState.FAILED;
            }
            case RUNNING -> TaskState.RUNNING;
        };
    }

    /** Preserve the original "can't reach {name}: {reason}" wording; still a plain give-up (no recovery). */
    @Override
    protected TaskState handleNavFailure(FailureType type, String reason) {
        fail("can't reach " + name() + ": " + reason, type);
        return TaskState.FAILED;
    }

    private Interaction.Button button() {
        return r.button == InteractEntityTaskRecord.Button.LEFT
                ? Interaction.Button.ATTACK : Interaction.Button.USE;
    }

    private boolean withinReach() {
        return player.onGround()
                && entity != null
                && player.distanceToSqr(entity.position()) <= REACH_SQR;
    }

    /** In arm's reach AND no block between our eyes and the entity (vanilla hasLineOfSight) —
     *  the nav arrival gate, so the body walks around a wall instead of freezing in front of it. */
    private boolean inReachAndLos() {
        return withinReach() && player.hasLineOfSight(entity);
    }

    private String name() {
        return entity != null ? entity.getName().getString() : "entity#" + r.entityId;
    }

    private String describeDone() {
        String verb = r.button == InteractEntityTaskRecord.Button.LEFT ? "attacked" : "interacted with";
        return verb + " " + name();
    }

    /** Release the interaction, then the nav + overlay (base default). */
    @Override
    protected void cleanup() {
        if (interaction != null) interaction.stop();
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("button", r.button == InteractEntityTaskRecord.Button.LEFT ? "left" : "right");
        data.put("entity_id", r.entityId);
        return data;
    }

    @Override
    protected String successMessage() {
        return successMsg;
    }

    @Override
    protected String timeoutMessage() {
        return "timed out before interacting with " + name();
    }

    @Override
    protected String cancelledMessage() {
        return "interact_entity interrupted";
    }
}
