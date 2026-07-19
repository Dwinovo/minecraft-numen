package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.task.Suspendable;
import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.base.GoToThenDoTask;
import com.dwinovo.numen.core.task.base.Precondition;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code interact_at} on the player body — the point-aimed native interaction (BLOCK + AIR).
 * Walk within reach of the aim (if one is given), look at it, fire ONE native crosshair
 * raytrace ({@link Interaction#nativeRaytrace}) and press the requested mouse button on
 * whatever it resolves to ({@link Interaction#forHit}): break / activate the block hit, or —
 * on a clear-air aim — use the held item in that direction (throw / eat / draw). The mouse
 * model is the two record fields {@code button} (left/right) × {@code holdTicks} (tap/hold).
 */
public final class InteractAtCompanionTask extends GoToThenDoTask<InteractAtTaskRecord> {

    private static final double REACH = 4.5;
    private static final double REACH_SQR = REACH * REACH;
    private static final double WALK_SPEED = 1.0;
    /** Reposition-rung stance radius: any feet cell this close to the aim (< {@link #REACH},
     *  so an accepted stance is still within interact reach). Never wider than the goal. */

    private Interaction interaction;
    // ---- bounded recovery state (fields, so a Suspendable mid-rung suspend/resume
    //      picks straight back up: the counter and the rebuilt nav both survive) ----
    /** The FIRST nav failure's reason, preserved so the final give-up keeps the original wording. */
    private long holdUntil = -1;       // game tick to release a fixed-duration hold (holdTicks > 0)
    private String successMsg = "done";
    // A right-click that activated a real block (a station's GUI): captured so the
    // result can report it and the agent loop can remember it in <known_blocks>.
    private net.minecraft.core.BlockPos activatedBlock;
    private String activatedBlockId;

    public InteractAtCompanionTask(NumenPlayer player, InteractAtTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        // If an item to use was named, fail fast unless we actually carry it.
        return List.of(() -> r.item == null || PlayerInv.count(player.getInventory(), r.item) > 0 ? null
                : new Precondition.Failure(
                        "don't have " + BuiltInRegistries.ITEM.getKey(r.item).getPath() + " to use",
                        FailureType.NO_MATERIAL));
    }

    @Override
    protected PlayerNav buildNav() {
        // 本任务不自带到场导航:身体须已在触及距离内(基座在 reached()==false
        // 且无导航时直接教学失败,旅行归 goto)。
        return null;
    }

    @Override
    protected net.minecraft.core.BlockPos gotoFirstTarget() {
        return r.aim;
    }

    @Override
    protected boolean reached() {
        return r.aim == null || withinReach();
    }

    @Override
    protected TaskState act() {
        // Resolve the crosshair once we're in position, then drive the action.
        if (interaction == null) {
            if (r.item != null) {
                player.holdInHand(PlayerInv.findSlot(player.getInventory(), r.item));
            }
            if (r.aim != null) {
                InputDriver.lookAt(player, Vec3.atCenterOf(r.aim));
            }
            HitResult hit = Interaction.nativeRaytrace(player, REACH);
            // 目标格本身是实心方块、而准星实际落在别的方块上 = 被遮挡:
            // 拒绝并点名遮挡物(点下去只会交互到错误对象还谎报成功)。
            // 目标格是空气的瞄点(朝某处投掷等)保持准星穿透语义。
            if (r.aim != null
                    && !player.level().getBlockState(r.aim).isAir()
                    && hit instanceof net.minecraft.world.phys.BlockHitResult blockedHit
                    && !blockedHit.getBlockPos().equals(r.aim)) {
                var blocker = blockedHit.getBlockPos();
                String blockerId = BuiltInRegistries.BLOCK
                        .getKey(player.level().getBlockState(blocker).getBlock()).getPath();
                fail("aim " + aimLabel() + " is blocked from here — the crosshair lands on "
                        + blockerId + " at " + blocker.getX() + "," + blocker.getY() + ","
                        + blocker.getZ() + " instead. break_block that blocker, or goto the"
                        + " target's open side, then retry.", FailureType.OCCLUDED);
                return TaskState.FAILED;
            }
            // A consumable / ender pearl used in the AIR is body-bound (would feed or teleport the
            // fake player) — refuse even when it's just whatever happened to be in hand.
            if (button() == Interaction.Button.USE && hit.getType() == HitResult.Type.MISS) {
                String reason = InteractAtTaskRecord.bodyBoundReason(player.getMainHandItem().getItem());
                if (reason != null) {
                    fail(reason, FailureType.UNKNOWN);
                    return TaskState.FAILED;
                }
            }
            // A right-click landing on a block activates it (opens a station's GUI,
            // flips a switch, …). Remember the block we touched so <known_blocks> can
            // walk us back to stations we've used, not just ones we placed. The harvest
            // filters to tracked station types; doors/buttons fall away there.
            if (button() == Interaction.Button.USE && hit instanceof net.minecraft.world.phys.BlockHitResult bhr) {
                activatedBlock = bhr.getBlockPos();
                activatedBlockId = BuiltInRegistries.BLOCK
                        .getKey(player.level().getBlockState(activatedBlock).getBlock()).getPath();
            }
            interaction = Interaction.forHit(player, hit, button(), r.holdTicks);
            if (interaction == null) {       // left-click on air — a swing, nothing to do
                successMsg = "nothing under the aim (left-click in the air)";
                return TaskState.SUCCESS;
            }
            if (r.holdTicks > 0) {
                holdUntil = player.level().getGameTime() + r.holdTicks;
            }
        }

        // A fixed-duration hold ends when its window elapses: release the button.
        if (holdUntil >= 0 && player.level().getGameTime() >= holdUntil) {
            interaction.stop();
            successMsg = describeDone();
            return TaskState.SUCCESS;
        }
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


    /** In-ladder nav causes the reposition rung handles; anything else kicks straight back to the LLM. */
    private static boolean repositionable(FailureType type) {
        return type == FailureType.NO_PATH || type == FailureType.BOXED_IN
                || type == FailureType.OUT_OF_REACH || type == FailureType.STANCE_DUD;
    }

    private Interaction.Button button() {
        return r.button == InteractAtTaskRecord.Button.LEFT
                ? Interaction.Button.ATTACK : Interaction.Button.USE;
    }

    private boolean withinReach() {
        return player.onGround()
                && player.distanceToSqr(Vec3.atCenterOf(r.aim)) <= REACH_SQR;
    }

    private String aimLabel() {
        return r.aim.getX() + "," + r.aim.getY() + "," + r.aim.getZ();
    }

    private String describeDone() {
        String verb = r.button == InteractAtTaskRecord.Button.LEFT ? "left-clicked" : "right-clicked";
        return verb + (r.aim != null ? " " + aimLabel() : " (forward)");
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
        data.put("button", r.button == InteractAtTaskRecord.Button.LEFT ? "left" : "right");
        if (r.aim != null) {
            data.put("x", r.aim.getX());
            data.put("y", r.aim.getY());
            data.put("z", r.aim.getZ());
        }
        // Report the activated station (and its exact position, authoritative over the
        // raw aim) so the agent loop can harvest it into <known_blocks>.
        if (activatedBlock != null) {
            data.put("block", activatedBlockId);
            data.put("x", activatedBlock.getX());
            data.put("y", activatedBlock.getY());
            data.put("z", activatedBlock.getZ());
        }
        return data;
    }

    @Override
    protected String successMessage() {
        return successMsg;
    }

    @Override
    protected String timeoutMessage() {
        return "timed out before interacting at " + (r.aim != null ? aimLabel() : "forward");
    }

    @Override
    protected String cancelledMessage() {
        return "interact_at interrupted";
    }
}
