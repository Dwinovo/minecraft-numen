package com.dwinovo.numen.core.task.interact;
import com.dwinovo.numen.core.task.MouseButton;
import com.dwinovo.numen.core.PlayerInv;

import com.dwinovo.numen.pathing.body.Hotbar;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.task.base.InReachTask;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.pathing.body.Crosshair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/**
 * {@code numen.use.entity} on the player body: the entity-aimed native interaction. It does not travel: the entity must be within
 * reach and in sight of where she stands; then she aims at it and presses the requested mouse button only when the native
 * raytrace reaches that entity. Out of reach or behind a wall, the call fails with where it is and the
 * {@code numen.move.to} to copy. LEFT+hold repeats the native attack until the hold ends, the target dies, or the task
 * times out.
 */
public final class InteractEntityCompanionTask extends InReachTask<InteractEntityTaskRecord> {

    private Entity entity;
    private Interaction interaction;
    /** 按键前的世界快照,收尾时对账出"真发生了什么"。 */
    private com.dwinovo.numen.core.act.PressReceipt receipt;
    private List<String> changes = List.of();
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

    /** 够不着时点名的那一格:它此刻所在的那一格。 */
    @Override
    protected BlockPos target() {
        return entity.blockPosition();
    }

    /** Act this tick when the target is gone (report the outcome), a fixed hold has elapsed, or we're
     *  in reach with a clear line of sight; otherwise the call fails without travelling. */
    @Override
    protected boolean reached() {
        return entity == null || !entity.isAlive()
                || (interaction != null && holdUntil >= 0 && player.level().getGameTime() >= holdUntil)
                || inReachAndLos();
    }

    @Override
    protected TaskState act() {
        // Target gone: death is success for a left-click that already landed; otherwise
        // the target slipped away before we could touch it.
        if (entity == null || !entity.isAlive()) {
            if (acted) {
                successMsg = r.button == MouseButton.LEFT
                        ? "defeated " + targetName() : "done with " + targetName();
                return TaskState.SUCCESS;
            }
            fail("the target entity is gone before I could reach it", FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }

        // 目标是她自己坐着的载具:右键的意图(上船)早已是事实,立即了结;左键
        // 原版玩家也打不到自己的座驾。不拦的话,下面的准星确认要求"看见目标",
        // 而从座位上看自己的船永远确认不了——任务空转到超时,实测就是这么挂的。
        if (entity == player.getVehicle()) {
            if (r.button == MouseButton.LEFT) {
                fail("you are riding the " + targetName()
                        + " — can't hit your own vehicle; numen.move.dismount() steps off first",
                        FailureType.UNKNOWN);
                return TaskState.FAILED;
            }
            successMsg = "already riding the " + targetName();
            return TaskState.SUCCESS;
        }

        // A fixed-duration hold completes on time even if the line of sight lapsed near the end.
        if (interaction != null && holdUntil >= 0 && player.level().getGameTime() >= holdUntil) {
            interaction.stop();
            successMsg = describeDone() + settle();
            return TaskState.SUCCESS;
        }

        // In reach + LOS: aim at the entity and confirm the crosshair actually resolves to IT
        // (e.g. not another entity wandered into the exact line) before pressing.
        InputDriver.lookAt(player, entity.getEyePosition());
        HitResult hit = Crosshair.pick(player);
        boolean onTarget = hit.getType() == HitResult.Type.ENTITY
                && ((EntityHitResult) hit).getEntity() == entity;
        if (!onTarget) {
            return TaskState.RUNNING;   // settling / something briefly in the line — re-aim next tick
        }

        if (interaction == null) {
            // 按下去之前交给权限层:左键是打它(宠物、有名字的、村民),右键是右键它(有主人的)。
            // 要问就站着等主人,不许就带着理由收场
            boolean left = r.button == MouseButton.LEFT;
            Permit permit = permit(left ? com.dwinovo.numen.permission.Action.attack(entity)
                    : com.dwinovo.numen.permission.Action.useEntity(entity));
            if (permit.state() == PermitState.WAITING) {
                player.controls().stop();
                return TaskState.RUNNING;
            }
            if (permit.state() == PermitState.REFUSED) {
                fail("cannot " + (left ? "attack " : "use ") + targetName() + ": " + permit.refusal(),
                        FailureType.REFUSED);
                return TaskState.FAILED;
            }
        }
        if (interaction == null) {
            if (r.item != null) {
                Hotbar.grip(player, r.item);
            }
            // 兜底开关与 use block 同一条身体约束(政策的唯一出处在那份记录上):
            // 实体没吃掉点击才轮到物品自用,手里是食物/珍珠时宁可不兜。
            boolean fallthroughOk =
                    InteractAtTaskRecord.bodyBoundReason(player.getMainHandItem().getItem()) == null
                    && InteractAtTaskRecord.bodyBoundReason(player.getOffhandItem().getItem()) == null;
            receipt = com.dwinovo.numen.core.act.PressReceipt.before(player, null);
            interaction = Interaction.forHit(player, hit, button(), r.holdTicks, fallthroughOk, r.sneak);
            if (r.holdTicks > 0) {
                holdUntil = player.level().getGameTime() + r.holdTicks;
            }
        }
        acted = true;

        return switch (interaction.tick()) {
            case DONE -> {
                successMsg = describeDone() + settle();
                yield TaskState.SUCCESS;
            }
            case FAILED -> {
                fail(interaction.failReason(), interaction.failType());
                yield TaskState.FAILED;
            }
            case RUNNING -> TaskState.RUNNING;
        };
    }

    private Interaction.Button button() {
        return r.button == MouseButton.LEFT
                ? Interaction.Button.ATTACK : Interaction.Button.USE;
    }

    private boolean withinReach() {
        return bodySettled() && entity != null
                && player.canInteractWithEntity(entity, 0.0);
    }

    /** In arm's reach AND no block between our eyes and the entity (vanilla hasLineOfSight) —
     *  the nav arrival gate, so the body walks around a wall instead of freezing in front of it. */
    private boolean inReachAndLos() {
        return withinReach() && player.hasLineOfSight(entity);
    }

    private String targetName() {
        return entity != null ? entity.getName().getString() : "entity#" + r.entityId;
    }

    private String describeDone() {
        String verb = r.button == MouseButton.LEFT ? "attacked" : "interacted with";
        return verb + " " + targetName() + (r.sneak ? " while sneaking" : "");
    }

    /** 收尾对账,与 use block 同款:只报事实,判断留给读回执的人。 */
    private String settle() {
        changes = receipt == null ? List.of() : receipt.diff(player);
        if (changes.isEmpty()) {
            return "";   // 实体交互多数不留可见痕迹(交易开了界面、动物进了求爱态),不硬报"没变"
        }
        return " — " + String.join("; ", changes);
    }

    /** Release the interaction, then the nav + overlay (base default). */
    @Override
    protected void cleanup() {
        if (interaction != null) interaction.stop();
        super.cleanup();
    }

    @Override
    protected com.dwinovo.numen.core.tools.Clicks.EntityClicked value() {
        return new com.dwinovo.numen.core.tools.Clicks.EntityClicked(r.button == MouseButton.LEFT
                ? com.dwinovo.numen.core.tools.Clicks.Button.LEFT : com.dwinovo.numen.core.tools.Clicks.Button.RIGHT,
                r.entityId, java.util.List.copyOf(changes));
    }

    @Override
    protected String successMessage() {
        return successMsg;
    }

    @Override
    protected String timeoutMessage() {
        return "timed out before interacting with " + targetName();
    }

    @Override
    protected String cancelledMessage() {
        return r.getToolName() + " interrupted";
    }
}
