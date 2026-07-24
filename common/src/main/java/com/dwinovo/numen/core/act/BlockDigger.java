package com.dwinovo.numen.core.act;

import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.task.base.ToolSelect;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Progressive block breaking that drives the SAME native server entry point a
 * real client's packets hit. A fake player has no client to
 * run the mining loop, so this class stands in for the client:
 * <ul>
 *   <li>begin: {@code handleBlockBreakAction(START_DESTROY_BLOCK)} + the block's
 *       left-click {@code attack} (note blocks, redstone-ore glow, …); creative
 *       breaks instantly on START; an insta-mineable block breaks on START too;</li>
 *   <li>each tick: accumulate the block's real {@link BlockState#getDestroyProgress}
 *       and broadcast the crack overlay (breaker id {@code -1} — the
 *       server does NOT self-complete a survival break for a fake player);</li>
 *   <li>finish: {@code handleBlockBreakAction(STOP_DESTROY_BLOCK)} → the SERVER
 *       destroys the block (drops / durability / events). We do NOT clear the
 *       crack — the block vanishing removes it, so there's no "intact for one
 *       frame" flicker — and we set a {@code blockHitDelay} so the next dig waits
 *       for the destroy to land instead of re-starting the same block;</li>
 *   <li>interrupted: {@code ABORT_DESTROY_BLOCK} + clear the crack.</li>
 * </ul>
 * Shared by path-obstruction clearing ({@code ExecHarness}), auto-mine
 * ({@code MineCompanionTask}), and {@link Interaction} (break_block / interact).
 */
public final class BlockDigger {

    /** The crack is broadcast under breaker id -1 (not the player's entity id),
     *  so the server's own per-player crack clearing on STOP can't wipe it early. */
    private static final int CRACK_ID = -1;

    /** Ticks to wait after a survival break before starting another; follows the
     *  blockBreakSpeed setting (period = the setting, delay = setting − 1). */
    private static int postBreakDelay() {
        return Math.max(0,
                com.dwinovo.numen.core.pathing.settings.NavSettings.get().blockBreakSpeed - 1);
    }

    private final NumenPlayer player;
    private BlockPos pos;
    private float progress;       // accumulated 0..1 destroy fraction
    private boolean started;      // START_DESTROY_BLOCK has been sent for `pos`
    private int blockHitDelay;    // post-break cooldown (survives reset())
    /** 开挖时的主手物品快照;中途换持(物品/组件级)即重开进度。 */
    private net.minecraft.world.item.ItemStack destroyingItem;

    public BlockDigger(NumenPlayer player) {
        this.player = player;
    }

    /** The block currently being dug, or {@code null} when idle. */
    public BlockPos current() {
        return pos;
    }

    /** Outcome of one {@link #digStep} tick — lets callers distinguish "still working"
     *  from "physically can't get at it", which the old boolean folded together. */
    public enum DigResult {
        /** Break in progress, cooling down, or begun this tick — keep calling. */
        PROGRESSING,
        /** The TARGET block's break committed this tick. */
        BROKE_TARGET,
        /** An OCCLUDER in the way broke this tick (not the target) — a step toward it. */
        BROKE_OCCLUDER,
        /** No face of the target is reachable and nothing safe occludes it — stuck (maps to OCCLUDED). */
        NO_SHOT
    }

    /** Legacy boolean shim: {@code true} only on the tick the TARGET breaks. Kept so
     *  pre-migration callers ({@link Interaction}) compile unchanged; delete once every
     *  caller consumes {@link #digStep}. */
    public boolean dig(BlockPos target) {
        return digStep(target) == DigResult.BROKE_TARGET;
    }

    /**
     * Advance the dig of {@code target} by one tick (restarting cleanly if the
     * target changed): face it, drive the native break action, swing.
     *
     * @return the {@link DigResult} for this tick.
     */
    /**
     * Advance the dig one tick using an ALREADY-RESOLVED crosshair hit: dig
     * exactly the block (and face) the caller's view ray landed on, no internal
     * aim-point search. The hit block is always treated as the target.
     */
    public DigResult digStep(BlockHitResult crosshairHit) {
        if (blockHitDelay > 0) {                    // let the previous break land first
            blockHitDelay--;
            InputDriver.halt(player);
            return DigResult.PROGRESSING;
        }
        InputDriver.halt(player);
        BlockPos effective = crosshairHit.getBlockPos();
        if (pos == null || !pos.equals(effective)) {
            // 工具由外层(移动原语按意图格)选择,这里不按命中格改选
            start(effective, false);
        }
        return advance(crosshairHit, true);
    }

    /** 破块后冷却按游戏刻递减;本 tick 没走 digStep 的驱动方调用此保持计时。 */
    public void tickCooldown() {
        if (blockHitDelay > 0) {
            blockHitDelay--;
        }
    }

    public DigResult digStep(BlockPos target) {
        Level level = player.level();
        if (blockHitDelay > 0) {                    // let the previous break land first
            blockHitDelay--;
            InputDriver.halt(player);
            return DigResult.PROGRESSING;
        }
        // Resolve what to actually swing at this tick. First try a raycast-VERIFIED face on the
        // target. If the target is OCCLUDED — no face in line of
        // sight (leaves in front, a tight column overhead) — fall back to breaking the
        // occluder: aim at the target's centre and break whatever the
        // crosshair actually hits, opening the way, instead of holding forever for a clear angle.
        // One guard: never grind a do_not_break / container block as the occluder.
        BlockHitResult hit = reachableHit(target);
        BlockPos effective = target;
        if (hit == null) {
            BlockHitResult center = centerRaycast(target);
            if (center != null && !center.getBlockPos().equals(target)
                    && !BlockHelper.shouldAvoidBreaking(level, center.getBlockPos())) {
                hit = center;
                effective = center.getBlockPos();
            }
        }
        InputDriver.halt(player);
        if (hit == null) {
            return DigResult.NO_SHOT;                // no clear shot, nothing safe in the way — stuck
        }
        if (pos == null || !pos.equals(effective)) {
            start(effective, true);
        }
        // dig() may be clearing an OCCLUDER this tick, not the target; report the break (true) ONLY
        // when the TARGET itself goes, so callers that count mined targets / treat the cell as cleared
        // aren't fooled by a leaf we broke just to open the line of sight.
        return advance(hit, effective.equals(target));
    }


    public DigResult digTargetStep(BlockPos target) {
        if (blockHitDelay > 0) {
            blockHitDelay--;
            InputDriver.halt(player);
            return DigResult.PROGRESSING;
        }
        BlockHitResult hit = reachableHit(target);
        InputDriver.halt(player);
        if (hit == null) {
            return DigResult.NO_SHOT;
        }
        if (pos == null || !pos.equals(target)) {
            start(target, true);
        }
        return advance(hit, true);
    }
    /** Shared per-tick dig advance against a resolved hit (face + aim point). */
    private DigResult advance(BlockHitResult hit, boolean targetBreak) {
        Level level = player.level();
        // 主手物品与开挖时不同(物品/组件级比较)→ 重开:ABORT 旧进度、
        // 按新手持重新 START(与原版换持重置破坏进度同语义)
        if (started && destroyingItem != null
                && !net.minecraft.world.item.ItemStack.isSameItemSameComponents(
                        destroyingItem, player.getMainHandItem())) {
            BlockPos samePos = pos;
            start(samePos, false);
        }
        InputDriver.lookAt(player, hit.getLocation());
        Direction side = hit.getDirection();
        BlockState state = level.getBlockState(pos);

        if (!started) {
            started = true;
            player.gameMode.handleBlockBreakAction(pos,
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, side, level.getMaxY(), -1);
            player.swing(InteractionHand.MAIN_HAND);
            if (player.getAbilities().instabuild) {
                // creative: START 即破,连挖不设间隔(每 tick 一格)
                reset();
                return targetBreak ? DigResult.BROKE_TARGET : DigResult.BROKE_OCCLUDER;
            }
            if (!state.isAir()) {
                // START 通道内服务端已自带 attack 与 insta-mine 判定,这里
                // 不再补一次(重复 attack 会翻倍副作用,红石矿甚至会在
                // insta-mine 后被旧 state 的 attack 原地点亮放回)
                if (state.getDestroyProgress(player, level, pos) >= 1.0f) {
                    reset();                         // instamine: START broke it (no STOP is sent)
                    return targetBreak ? DigResult.BROKE_TARGET : DigResult.BROKE_OCCLUDER;
                }
            }
            return DigResult.PROGRESSING;            // begin accumulating next tick
        }

        // Survival: accumulate the real per-tick destroy fraction; broadcast the crack.
        progress += state.getDestroyProgress(player, level, pos);
        int stage = Math.min(9, (int) (progress * 10.0f));
        level.destroyBlockProgress(CRACK_ID, pos, stage);
        player.swing(InteractionHand.MAIN_HAND);
        if (progress >= 1.0f) {
            // STOP → server destroys. Do NOT clear the crack: the block vanishing
            // removes it (no intact-for-a-frame flicker).
            player.gameMode.handleBlockBreakAction(pos,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, side, level.getMaxY(), -1);
            blockHitDelay = postBreakDelay();
            reset();
            return targetBreak ? DigResult.BROKE_TARGET : DigResult.BROKE_OCCLUDER;
        }
        return DigResult.PROGRESSING;
    }

    private void start(BlockPos target, boolean selectTool) {
        cancel();
        pos = target.immutable();
        progress = 0.0f;
        started = false;
        if (selectTool) {
            // Hold the best tool BEFORE timing the dig — getDestroyProgress reads the held
            // item, and the pathing cost model prices every break with the best
            // available tool. ToolSelect owns the scan (whole inventory) so this stays
            // consistent with the pathing cost model (ToolSet).
            ToolSelect.holdBestTool(player, player.level().getBlockState(pos));
        }
        // 存活引用而非副本:主手栈原地变异(修补吸经验改耐久)时引用相等,
        // 不触发重置;只有真正换持(不同栈对象且物品/组件不同)才重开
        destroyingItem = player.getMainHandItem();
    }

    /** Abandon an IN-PROGRESS dig: ABORT it server-side and clear the crack.
     *  A completed break never comes through here — its crack is left
     *  for the block-break to remove. */
    public void cancel() {
        if (pos != null) {
            if (started) {
                player.gameMode.handleBlockBreakAction(pos,
                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                        Direction.DOWN, player.level().getMaxY(), -1);
            }
            player.level().destroyBlockProgress(CRACK_ID, pos, -1);   // clear the crack
        }
        reset();
    }

    /** Clear dig state. Deliberately does NOT touch {@link #blockHitDelay} (a
     *  post-break cooldown that must outlive the break) or the crack overlay. */
    private void reset() {
        pos = null;
        progress = 0.0f;
        started = false;
    }

    /**
     * The first point ON {@code pos} the eye can
     * actually raycast to — the block's shape centre first, then its six face centres. The
     * returned {@link BlockHitResult} carries the exact aim point ({@code getLocation}) AND
     * the face the ray hits ({@code getDirection}), so the dig looks at the real interaction
     * face like a player would. {@code null} if nothing on the block is in line of sight.
     */
    private BlockHitResult reachableHit(BlockPos pos) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        double reach = com.dwinovo.numen.core.pathing.moves.MovementHelper.blockReachDistance(player);
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getShape(level, pos);
        if (shape.isEmpty()) {
            shape = Shapes.block();
        }
        // Collision-shape centre first (empty collision → whole-cell centre),
        // then the six face centres on the outline shape.
        Vec3[] aims = {
                com.dwinovo.numen.core.pathing.moves.MovementHelper.collisionCenter(level, pos, state),
                offsetOn(pos, shape, 0.5, 0.0, 0.5),
                offsetOn(pos, shape, 0.5, 1.0, 0.5),
                offsetOn(pos, shape, 0.5, 0.5, 0.0),
                offsetOn(pos, shape, 0.5, 0.5, 1.0),
                offsetOn(pos, shape, 0.0, 0.5, 0.5),
                offsetOn(pos, shape, 1.0, 0.5, 0.5),
        };
        for (Vec3 aim : aims) {
            Vec3 dir = aim.subtract(eye);
            if (dir.lengthSqr() < 1.0e-8) continue;
            Vec3 end = eye.add(dir.normalize().scale(reach));
            BlockHitResult res = level.clip(new ClipContext(
                    eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (res.getType() == HitResult.Type.BLOCK && res.getBlockPos().equals(pos)) {
                return res;
            }
        }
        return null;
    }

    /**
     * A single ray from the eye to {@code target}'s shape centre — the break-the-occluder
     * fallback when {@link #reachableHit} finds no clear face: the ray lands on the
     * occluder (a leaf / a tight overhead), and we break THAT to open the way. Null on a miss / out
     * of reach. ({@link #reachableHit} already tries the centre first, so if that hit the target it
     * would have returned it; reaching here means the centre ray hits something else.)
     */
    private BlockHitResult centerRaycast(BlockPos target) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        double reach = com.dwinovo.numen.core.pathing.moves.MovementHelper.blockReachDistance(player);
        Vec3 center = Vec3.atCenterOf(target);
        Vec3 dir = center.subtract(eye);
        if (dir.lengthSqr() < 1.0e-8) {
            return null;
        }
        Vec3 end = eye.add(dir.normalize().scale(reach));
        BlockHitResult res = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return res.getType() == HitResult.Type.BLOCK ? res : null;
    }

    /** A point on the block's shape:
     *  {@code min*m + max*(1-m)} on each axis. */
    private static Vec3 offsetOn(BlockPos pos, VoxelShape shape, double mx, double my, double mz) {
        double x = shape.min(Direction.Axis.X) * mx + shape.max(Direction.Axis.X) * (1 - mx);
        double y = shape.min(Direction.Axis.Y) * my + shape.max(Direction.Axis.Y) * (1 - my);
        double z = shape.min(Direction.Axis.Z) * mz + shape.max(Direction.Axis.Z) * (1 - mz);
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

}
