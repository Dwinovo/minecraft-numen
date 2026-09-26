package com.dwinovo.numen.core.act;
import com.dwinovo.numen.core.pathing.moves.AimGeometry;

import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.act.ToolSelect;
import com.dwinovo.numen.core.mixin.ServerPlayerGameModeAccessor;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.permission.Verdict;
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
 * ({@code MineCompanionTask}), construction clearing ({@code BuildCompanionTask}) and
 * {@link Interaction} (the {@code use block} left click). It is the only place a block is broken, so it is
 * where the permission layer is enforced: every new target is judged before the first swing
 * ({@link #permit}); a refused block is reported as {@link DigResult#REFUSED} and never touched.
 *
 * <p>服务端也可能把这一下退回来:别的模组取消了左键或破坏事件、原版的出生点保护与冒险模式限制——它们都在
 * 原生通道里生效,不经权限层。挖掘器在 START 与收尾那一下之后读服务端的挖掘状态({@link ServerPlayerGameModeAccessor})
 * 对账,退回来的同样按 {@link DigResult#REFUSED} 收场,理由是 {@link #SERVER_REFUSED}——不空挥到超时,也不把
 * 没挖掉的方块报成挖掉了。
 */
public final class BlockDigger {

    /** 服务端把挖掘退回来时回执里的理由。 */
    public static final String SERVER_REFUSED = "服务器没让挖掉这一格";

    /**
     * 服务端收下这一下挖掘时,比交互距离多给的那一格:{@code ServerPlayerGameMode#handleBlockBreakAction}
     * 验的是 {@code canInteractWithBlock(pos, 1.0)}。射线按"交互距离 + 这一格"打,打得到的就是服务端认的。
     * 调用方判"够不够得着"按的是站立格的眼位({@code BlockReach}),身体在格里偏开的那一截落在这一格里。
     */
    private static final double SERVER_REACH_SLACK = 1.0;

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
    /** 最近一次 {@link DigResult#REFUSED} 的理由(权限层的裁决,或服务端退回的 {@link #SERVER_REFUSED});没被拒过是 null。 */
    private Verdict refusal;

    public BlockDigger(NumenPlayer player) {
        this.player = player;
    }

    /** The block currently being dug, or {@code null} when idle. */
    public BlockPos current() {
        return pos;
    }

    /**
     * 最近一次真挖掉的那一格和它挖掉前的方块。{@link DigResult#BROKE_OCCLUDER} 挖掉的是挡在前面的那格,
     * 不是调用方给的目标,记账要记这里说的这一格。
     */
    public record Broken(BlockPos pos, BlockState was) {}

    private Broken lastBroken;

    /** 最近一次 {@link DigResult#broke()} 挖掉的是哪一格;还没挖掉过是 null。 */
    public Broken lastBroken() {
        return lastBroken;
    }

    /** Why the last {@link DigResult#REFUSED} happened; {@code null} if nothing was refused yet. */
    public Verdict refusal() {
        return refusal;
    }

    /**
     * 问权限层这一格能不能挖。每次换新目标问一次,在第一次挥手之前;被拒的格连 START 都不发。
     * 唯一挖掘落点上的唯一门,所有调用方(寻路、挖矿、施工、use block 左键)都过它。
     */
    private Verdict permit(BlockPos target) {
        Verdict verdict = Permission.judge(player, Action.breakBlock(target, player.level().getBlockState(target)));
        if (!verdict.allowed()) {
            refusal = verdict;
        }
        return verdict;
    }

    /**
     * 施工清障:一次到位的原生破坏({@code ServerPlayerGameMode.destroyBlock}——掉落按手持结算、
     * 创造不掉、别的模组的破坏事件照常触发),不走逐刻进度,也不要求视线。同样先过权限层。
     *
     * @return 方块真的没了
     */
    public boolean destroyNow(BlockPos target) {
        BlockState state = player.level().getBlockState(target);
        if (state.isAir()) {
            return false;
        }
        if (!permit(target).allowed()) {
            return false;
        }
        return player.gameMode.destroyBlock(target);
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
        NO_SHOT,
        /**
         * The permission layer refused this block before the first swing, or the server bounced the break
         * back (another mod cancelled it, vanilla spawn protection); {@link #refusal()} says why. The block is untouched.
         */
        REFUSED;

        /** 本 tick 有方块真的没了(目标或遮挡物)。 */
        public boolean broke() {
            return this == BROKE_TARGET || this == BROKE_OCCLUDER;
        }
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
            if (!permit(effective).allowed()) {
                cancel();
                return DigResult.REFUSED;
            }
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
        return digStep(target, occluder -> true);
    }

    /**
     * 同 {@link #digStep(BlockPos)},只是挡在前面的那一格还要 {@code mayClear} 点头才挖:权限层管许不许,
     * 调用方管这一格挖了会不会出事(比如挖矿按自己挑目标的那道剪枝,不挖贴着流体、顶着落沙的)。
     */
    public DigResult digStep(BlockPos target, java.util.function.Predicate<BlockPos> mayClear) {
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
        // The occluder goes through the same permission gate as any target: a player's chest in
        // the way is never ground down just to reach something behind it.
        BlockHitResult hit = AimGeometry.visibleHit(player, target, digReach());
        BlockPos effective = target;
        if (hit == null) {
            BlockHitResult center = centerRaycast(target);
            if (center != null && !center.getBlockPos().equals(target)
                    && mayClear.test(center.getBlockPos())
                    && Permission.judge(player, Action.breakBlock(center.getBlockPos(),
                            level.getBlockState(center.getBlockPos()))).allowed()) {
                hit = center;
                effective = center.getBlockPos();
            }
        }
        InputDriver.halt(player);
        if (hit == null) {
            return DigResult.NO_SHOT;                // no clear shot, nothing safe in the way — stuck
        }
        if (pos == null || !pos.equals(effective)) {
            if (!permit(effective).allowed()) {
                cancel();
                return DigResult.REFUSED;
            }
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
        BlockHitResult hit = AimGeometry.visibleHit(player, target, digReach());
        InputDriver.halt(player);
        if (hit == null) {
            return DigResult.NO_SHOT;
        }
        if (pos == null || !pos.equals(target)) {
            if (!permit(target).allowed()) {
                cancel();
                return DigResult.REFUSED;
            }
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
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, side, level.getMaxBuildHeight(), -1);
            player.swing(InteractionHand.MAIN_HAND);
            if (player.getAbilities().instabuild) {
                // creative: START 即破,连挖不设间隔(每 tick 一格)
                if (!landed(state)) {
                    return refusedByServer();
                }
                return broke(state, targetBreak);
            }
            if (!state.isAir()) {
                // START 通道内服务端已自带 attack 与 insta-mine 判定,这里
                // 不再补一次(重复 attack 会翻倍副作用,红石矿甚至会在
                // insta-mine 后被旧 state 的 attack 原地点亮放回)
                if (state.getDestroyProgress(player, level, pos) >= 1.0f) {
                    if (!landed(state)) {
                        return refusedByServer();
                    }
                    return broke(state, targetBreak);   // instamine: START broke it (no STOP is sent)
                }
            }
            ServerPlayerGameModeAccessor server = (ServerPlayerGameModeAccessor) player.gameMode;
            if (!server.numen$isDestroyingBlock() || !pos.equals(server.numen$destroyPos())) {
                // 服务端没收下这一下 START:往下攒进度也永远等不到它挖。它没开始挖,不必发 ABORT
                started = false;
                return refusedByServer();
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
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, side, level.getMaxBuildHeight(), -1);
            if (!landed(state)) {
                return refusedByServer();
            }
            blockHitDelay = postBreakDelay();
            return broke(state, targetBreak);
        }
        return DigResult.PROGRESSING;
    }

    /** 这一格的破坏落地了:记下挖掉的是哪一格,清掉挖掘状态。 */
    private DigResult broke(BlockState was, boolean targetBreak) {
        lastBroken = new Broken(pos.immutable(), was);
        reset();
        return targetBreak ? DigResult.BROKE_TARGET : DigResult.BROKE_OCCLUDER;
    }

    /**
     * 收尾那一下(creative/秒破的 START、生存的 STOP)之后,这一格的破坏落地了没有:方块变了算落地;服务端
     * 按自己的钟觉得进度还差一点、挂成延迟破坏的,过几刻它自己挖掉,也算。都不是就是服务端退回来了。
     *
     * @param before 按下之前那一格的方块状态
     */
    private boolean landed(BlockState before) {
        if (player.level().getBlockState(pos) != before) {
            return true;
        }
        ServerPlayerGameModeAccessor server = (ServerPlayerGameModeAccessor) player.gameMode;
        return server.numen$hasDelayedDestroy() && pos.equals(server.numen$delayedDestroyPos());
    }

    /** 服务端退回了这一下:记下理由,放开这一格(清裂纹),按 REFUSED 收场。 */
    private DigResult refusedByServer() {
        refusal = Verdict.deny(SERVER_REFUSED);
        cancel();
        return DigResult.REFUSED;
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
                        Direction.DOWN, player.level().getMaxBuildHeight(), -1);
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

    /** How far a dig swing reaches: the vanilla interaction range plus the server's slack. */
    private double digReach() {
        return player.blockInteractionRange() + SERVER_REACH_SLACK;
    }

    /**
     * A single ray from the eye to {@code target}'s shape centre — the break-the-occluder
     * fallback when {@link AimGeometry#visibleHit} finds no clear face: the ray lands on the
     * occluder (a leaf / a tight overhead), and we break THAT to open the way. Null on a miss / out
     * of reach. ({@link AimGeometry#visibleHit} already tries the centre first, so if that hit the
     * target it would have returned it; reaching here means the centre ray hits something else.)
     */
    private BlockHitResult centerRaycast(BlockPos target) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        double reach = digReach();
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

}
