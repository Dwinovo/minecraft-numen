package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.bridge.ContextFactory;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.moves.ActionCosts;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.act.BlockDigger;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.scan.BlockScanner;
import com.dwinovo.numen.core.scan.ScanExecutor;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * {@code mine} — the scan → path → dig gathering loop, run on the
 * companion player body (a server-side fake player, so every break goes
 * through real server-side interaction rules, not client input).
 *
 * <h2>The loop</h2>
 * <ol>
 *   <li><b>knownOreLocations</b> — periodically rescan the world for target
 *       blocks ({@link BlockScanner}), and {@link #prune} every tick (drop ones
 *       mined / no longer matching / blacklisted / hazardous), sorted by
 *       distance, capped at {@link #MAX_ORES}.</li>
 *   <li><b>shaft</b> — if a target sits in our own column within reach, break it
 *       straight up immediately (no pathing), auto-switching to the best tool —
 *       classic vertical-shaft mining.</li>
 *   <li><b>composite goal</b> — otherwise head for the whole ore field at once:
 *       one A* search over {@link NavGoal#composite} of {@link NavGoal#mine}
 *       stances, so it walks to the CLOSEST reachable ore (not greedy-nearest,
 *       which is often the walled-in one).</li>
 *   <li><b>blacklist</b> — when the path search fails, blacklist the nearest ore
 *       (presumed unreachable) and retry, so one walled-in ore can't stall the
 *       whole task.</li>
 *   <li><b>branch mine</b> — when no ore is known, head outward holding the
 *       y-level ({@link NavGoal#runAway}) to dig fresh tunnel and expose more,
 *       bounded by {@link #MAX_BRANCH_TICKS}.</li>
 * </ol>
 *
 * <p>A custom reactive task: it owns its own phase machine, so it grows on
 * {@link AbstractCompanionTask} directly (the shared lifecycle / failure plumbing /
 * result envelope) while keeping the whole scan-path-mine loop in {@link #onTick()}.
 */
public final class MineCompanionTask extends AbstractCompanionTask<MineBlockTaskRecord> {

    private static final int RESCAN_INTERVAL = 5;      // ticks between world rescans for targets
    private static final int MAX_ORES = 64;            // cap on tracked target locations
    /** 扫描提前收工的同层判据:玩家 Y ±10 内有命中就不再远扫。 */
    private static final int SCAN_Y_THRESHOLD = 10;
    /** 已凑够但全在层外时,最远还愿意扫出去的 chunk 环半径。 */
    private static final int SCAN_MAX_CHUNK_RADIUS = 32;
    private static final double REACH_SQR = 4.5 * 4.5;
    private static final double MINE_SPEED = 1.0;
    /** Give up branch-mining after this many ticks with no ore found (~30 s). */
    private static final int MAX_BRANCH_TICKS = 600;
    /**
     * Whether to keep hunting when no target is known. OFF (the default): "no ore
     * known" ends the task with whatever was gathered — the body does NOT wander
     * off across the world looking for more, which is the safer contract for a
     * companion the player expects to stay nearby. Flip this to enable the opt-in
     * explore mode (the bounded branch-mine below). */
    private static final boolean EXPLORE_FOR_BLOCKS = false;
    /** Abandon an in-flight scan after this long so a wedged future can't stop
     *  rescanning forever (scans finish in well under a tick; this only fires if
     *  something is truly stuck). */
    private static final int SCAN_TIMEOUT_TICKS = 200;
    /** Consecutive {@code NO_SHOT} dig ticks on ONE ore before it is blacklisted —
     *  the reach test said it was workable, but no shot ever materialises (OUTLINE vs
     *  COLLIDER ray disagreement, a lip over the stance). Without this the dig could
     *  grind forever waiting for a shot that never comes. */
    private static final int MAX_NO_SHOT_TICKS = 20;
    /** How long a just-broken target's cell stays a walk-over goal (ticks) — the drop
     *  takes a moment to spawn, and without this window the body sprints for the next
     *  ore before the item pops and leaves it behind. */
    private static final int DROP_LOITER_TICKS = 5;

    private final List<BlockPos> knownOres = new ArrayList<>();
    private final Set<BlockPos> blacklist = new HashSet<>();
    /** Targets pruned because no carried tool harvests them (force=false only) — kept so the
     *  terminal failure can name the tool problem instead of reporting an empty field. */
    private final Set<BlockPos> unharvestable = new HashSet<>();
    /** Items the target blocks drop (simulated via the server loot tables). The
     *  count is over THESE in the inventory, not blocks broken — redstone_ore yields ~4 redstone. */
    private Set<Item> dropItems = Set.of();
    /** Matching items already in the inventory when the task began — the count is the DELTA above this
     *  (companion semantics: "gather N more", not an absolute "have N in the inventory"). */
    private int baseline;
    /** Nearby dropped items to collect (walked over for native pickup), refreshed per tick. */
    private List<BlockPos> drops = List.of();
    /** Cells of just-broken targets, each held as a walk-over goal until the mapped
     *  game time so the spawned drop gets picked up before moving on. */
    private final Map<BlockPos, Long> anticipatedDrops = new HashMap<>();

    private boolean navIsBranch;
    private BlockPos branchPoint;
    private int branchY;
    private int rescanTimer;
    private int branchTicks;
    private String progressNote = "done";
    /** The ore currently returning {@code NO_SHOT}, and for how many consecutive ticks. */
    private BlockPos noShotPos;
    private int noShotTicks;
    /** In-flight background ore scan (rescans run off the tick thread so a large radius never stalls the server tick). */
    private CompletableFuture<List<BlockScanner.Hit>> scan;
    /** Game time by which the in-flight scan must finish or be abandoned. */
    private long scanDeadline;

    // Progressive dig (blocks break tick-by-tick at legitimate player speed, not
    // instabreak) — shared with the path executor so all breaking reads the same.
    private final BlockDigger digger;

    public MineCompanionTask(NumenPlayer player, MineBlockTaskRecord record) {
        super(player, record);
        this.digger = new BlockDigger(player);
    }

    @Override
    protected List<Precondition> preconditions() {
        // Fail fast if NO requested target is harvestable with the current inventory — mining it
        // would destroy the block for no drop. Same gate as break_block / the cost model
        // (BlockHelper.canHarvest, whole-inventory). prune() then drops any individual unharvestable
        // cell, so a mixed request (e.g. coal we can mine + diamond we can't) still works.
        return List.of(() -> {
            boolean anyHarvestable = r.targets.stream().anyMatch(
                    b -> BlockHelper.canHarvest(player.getInventory(), b.defaultBlockState()));
            if (!anyHarvestable) {
                return new Precondition.Failure(
                        "can't harvest " + r.label + " with the current tools — mining it would"
                        + " destroy it without any drop. Equip a suitable tool (e.g. a pickaxe)"
                        + " first; to just destroy a block regardless of drops, use break_block.",
                        FailureType.WRONG_TOOL);
            }
            return null;
        });
    }

    @Override
    protected void onStart() {
        // Count toward `count` by ITEMS gathered, not blocks broken: resolve what these
        // blocks drop, and snapshot how many we already hold so the tally is the delta above it.
        dropItems = computeDropItems();
        baseline = inventoryMatch();
        // 首扫也走后台线程(加载区边界内的环形扫描可能要啃整个加载区,
        // 不挂 tick);结果落地前 onTick 的终局判定会等着。
        kickScan();
    }

    @Override
    protected TaskState onTick() {
        int gathered = Math.max(0, inventoryMatch() - baseline);   // matching items gained so far
        r.setMined(gathered);
        if (gathered >= r.count) {
            progressNote = "gathered all requested";
            return TaskState.SUCCESS;
        }

        Level level = player.level();

        // Maintain the ore list every tick — INCLUDING while a dig below is latched
        // (the list keeps refreshing during mining): merge a finished background
        // scan, prune (cheap — knownOres is capped at 64), and kick a fresh
        // off-thread scan every RESCAN_INTERVAL ticks (never more than one in flight).
        drainScan();
        prune();
        if (--rescanTimer <= 0) {
            rescanTimer = RESCAN_INTERVAL;
            if (scan == null) kickScan();
        }

        // 0) Continue an in-progress dig, locked onto its block (no re-selection)
        //    until it breaks or drifts out of reach.
        BlockPos digging = digger.current();
        if (digging != null) {
            if (level.getBlockState(digging).isAir() || !withinReach(digging)) {
                digger.cancel();
            } else {
                mineProgress(digging);
                return TaskState.RUNNING;
            }
        }

        drops = droppedItems();

        // 1) Mine any target we can already reach + see from here (no pathing) —
        //    a tree gets mined from beside, never by digging under it.
        BlockPos reachable = reachableTarget();
        if (reachable != null) {
            stopNav();
            // Keep the goal boxes visible while mining in place: the path executor is
            // paused, but the target overlay should persist. stopNav just
            // cleared the overlay, so re-publish the ore field boxes — otherwise the
            // boxes vanish the instant shaft-mining starts (the "boxes disappear after
            // two logs" bug). No path line while shaft-mining, just the goal.
            mineProgress(reachable);
            return TaskState.RUNNING;
        }

        // 2) Head for the ore field + nearby drops (GoalComposite), arriving when a
        //    shaft opens up; drops are collected by walking over them (native pickup).
        if (!knownOres.isEmpty() || !drops.isEmpty()) {
            branchTicks = 0;
            if (nav == null || navIsBranch) {
                stopNav();
                // Compiled front door: every known ore is SACRED — the route gets the
                // body to the ore; digging it is THIS task's job (with this task's
                // bookkeeping), so the path may not consume the objective on the way.
                nav = PlayerNav.to(player, this::oreFieldCompiled, MINE_SPEED,
                        () -> reachableTarget() != null);
                nav.setHighlights(() -> new ArrayList<>(knownOres));   // box every known target
                navIsBranch = false;
            }
            switch (nav.tick()) {
                case RUNNING -> { return TaskState.RUNNING; }
                case ARRIVED -> {
                    stopNav();
                    // Arrived per the stance goal but nothing is reachable from here
                    // (line of sight blocked, ore beyond reach): this stance is a dud —
                    // blacklist the nearest ore and move on, exactly as a failed path
                    // would. Without this the loop re-navs to the same satisfied stance
                    // forever ("arrived" every tick, zero progress).
                    if (reachableTarget() == null && !knownOres.isEmpty()) {
                        com.dwinovo.numen.Constants.LOG.info(
                                "[numen-task] mine stance dud at feet={} — arrived per"
                                        + " goal, nothing reachable (LOS/reach)",
                                player.blockPosition().toShortString());
                        blacklistNearest();
                    }
                    return TaskState.RUNNING;   // a reachable shaft is handled next tick
                }
                case FAILED -> {
                    com.dwinovo.numen.Constants.LOG.info(
                            "[numen-task] mine nav failed ({}): {}",
                            nav.failType(), nav.failReason());
                    blacklistNearest();
                    stopNav();
                    return TaskState.RUNNING;
                }
            }
        }

        // 3) No ore known and nothing dropped nearby. A scan still in flight means
        //    "don't know yet", not "nothing there" — wait for it before any verdict.
        if (scan != null) {
            return TaskState.RUNNING;
        }
        //    Default: stop here — only the
        //    opt-in explore mode branch-mines outward for more. So
        //    finish with whatever we gathered (the tool's contract: "fewer than count
        //    in range still succeeds"), rather than running off across the world.
        if (!EXPLORE_FOR_BLOCKS) {
            if (r.getMined() > 0) {
                progressNote = "gathered " + r.getMined() + "/" + r.count + ", no more " + r.label + " in range";
                return TaskState.SUCCESS;
            }
            return noOreFailure();
        }

        // 3b) Opt-in explore — branch-mine outward (bounded) to dig fresh tunnel and expose more.
        if (branchPoint == null) {
            branchPoint = player.blockPosition();
            branchY = branchPoint.getY();
        }
        if (++branchTicks > MAX_BRANCH_TICKS) {
            if (r.getMined() > 0) {
                progressNote = "gathered " + r.getMined() + "/" + r.count + ", no more " + r.label + " in range";
                return TaskState.SUCCESS;
            }
            return noOreFailure();
        }
        if (nav == null || !navIsBranch) {
            stopNav();
            nav = PlayerNav.toGoal(player, () -> NavGoal.runAway(branchPoint, branchY),
                    MINE_SPEED, () -> false);
            nav.setHighlights(() -> new ArrayList<>(knownOres));   // (empty while branch-exploring)
            navIsBranch = true;
        }
        switch (nav.tick()) {
            case RUNNING, ARRIVED -> { return TaskState.RUNNING; }
            case FAILED -> { stopNav(); return TaskState.RUNNING; } // boxed in — rescan/retry
        }
        return TaskState.RUNNING;
    }

    // ---- goals ----

    /** The whole mining objective, compiled: a stance per ore + a walk-over member
     *  per nearby drop, with every ore cell sacred — one A* search heads for the
     *  closest of either, and the route can't consume a target on the way. */
    private GoalCompiler.Compiled oreFieldCompiled() {
        if (knownOres.isEmpty() && drops.isEmpty()) {
            // Degenerate frame (targets vanished between ticks): stand where we are.
            return GoalCompiler.standOn(player.blockPosition());
        }
        CalculationContext ctx = ContextFactory.forExecution(player);
        List<GoalCompiler.Stance> stances =
                new ArrayList<>(knownOres.size());
        for (BlockPos ore : knownOres) {
            stances.add(coalesce(ctx, ore));
        }
        return GoalCompiler.mineField(
                stances, new ArrayList<>(drops));
    }

    /**
     * Pick the mining-stance goal for one ore so the body never stands BELOW the
     * bottom of a vein/trunk. A blind stance rule ("feet anywhere up to two below
     * every ore") was the earlier bug: it made a tree's bottom log's −2 cell a
     * valid stance, and bare-handed (logs dear, dirt cheap) A* dug under to it.
     * The fix reads the vertical run: ask "is the block above / below this one
     * ALSO something I'm mining?" — the bottom of a run (target above, plain
     * ground below) gets an exact-feet goal, so the ore is mined from where you
     * stand, never dug under.
     */
    private GoalCompiler.Stance coalesce(CalculationContext ctx, BlockPos loc) {
        boolean assumeVerticalShaftMine =
                !(player.level().getBlockState(loc.above()).getBlock()
                        instanceof net.minecraft.world.level.block.FallingBlock);
        boolean upwardGoal = internalMiningGoal(ctx, loc.above());
        boolean downwardGoal = internalMiningGoal(ctx, loc.below());
        boolean doubleDownwardGoal = internalMiningGoal(ctx, loc.below(2));
        if (upwardGoal == downwardGoal) {                       // symmetric vertically
            return (doubleDownwardGoal && assumeVerticalShaftMine)
                    ? GoalCompiler.Stance.at(loc, 2)   // feet up to 2 below the ore
                    : GoalCompiler.Stance.at(loc, 1);  // feet at the ore or 1 below
        }
        if (upwardGoal) {                                       // bottom of a run: stand in it
            return GoalCompiler.Stance.at(loc, 0);     // feet EXACTLY at the ore
        }
        return (doubleDownwardGoal && assumeVerticalShaftMine) // top of a run, more below
                ? new GoalCompiler.Stance(loc, loc.below(), 1)  // feet at/1-below the block under it
                : new GoalCompiler.Stance(loc, loc.below(), 0); // feet exactly at the block under it
    }

    /**
     * Is {@code pos} also part of what we're mining — a known target, a filter
     * match, or already-broken air continuing the shaft? Used by {@link #coalesce}
     * to read the vertical run a block sits in.
     */
    private boolean internalMiningGoal(CalculationContext ctx, BlockPos pos) {
        if (knownOres.contains(pos)) return true;
        net.minecraft.world.level.block.state.BlockState state = player.level().getBlockState(pos);
        if (state.isAir()) return true;                         // broken-out air still continues the run
        return r.targets.contains(state.getBlock()) && plausibleToBreak(ctx, pos, state);
    }

    /** 该目标格是否真挖得成:挖穿成本无穷(挖不动/被硬禁)、禁挖判定命中
     *  (冰/虫蚀/贴液体/悬空落沙邻格/世界边界)、或上下都被基岩封死的都不算。
     *  包内共享:goto 的 FIND 候选入册走同一道剪枝。 */
    static boolean plausibleToBreak(CalculationContext ctx, BlockPos pos, BlockState state) {
        if (MovementHelper.getMiningDurationTicks(ctx, pos.getX(), pos.getY(), pos.getZ(),
                state, true) >= ActionCosts.COST_INF) {
            return false;
        }
        if (MovementHelper.avoidBreaking(ctx, pos.getX(), pos.getY(), pos.getZ(), state)) {
            return false;
        }
        return !(ctx.get(pos.getX(), pos.getY() + 1, pos.getZ()).getBlock()
                        == net.minecraft.world.level.block.Blocks.BEDROCK
                && ctx.get(pos.getX(), pos.getY() - 1, pos.getZ()).getBlock()
                        == net.minecraft.world.level.block.Blocks.BEDROCK);
    }

    /** Dropped items worth collecting, walked over for native pickup: only items the
     *  targets actually drop (a stray rotten flesh isn't this task's business), within
     *  the task's own working radius. A drop sitting next to a known ore is skipped —
     *  mining that ore walks us there anyway. Just-broken cells linger as members for
     *  {@link #DROP_LOITER_TICKS} so the spawning drop isn't left behind. */
    private List<BlockPos> droppedItems() {
        Level level = player.level();
        long now = level.getGameTime();
        anticipatedDrops.values().removeIf(expiry -> expiry < now);
        // 搜集范围 = 服务端视距(身体周围的加载邻域),与目标扫描的事实边界同源。
        int reach = level instanceof ServerLevel sl
                ? sl.getServer().getPlayerList().getViewDistance() * 16 : 128;
        AABB box = new AABB(player.blockPosition()).inflate(reach);
        List<BlockPos> out = new ArrayList<>();
        for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (!dropItems.contains(ie.getItem().getItem())) continue;
            BlockPos p = ie.blockPosition();
            if (blacklist.contains(p) || nearKnownOre(p)) continue;
            out.add(p);
        }
        for (BlockPos p : anticipatedDrops.keySet()) {
            if (blacklist.contains(p) || nearKnownOre(p)) continue;
            out.add(p);
        }
        return out;
    }

    /** 距任一已知矿位 3 格内(distSqr ≤ 9)——挖那颗矿自然会带身体过去。 */
    private boolean nearKnownOre(BlockPos p) {
        return knownOres.stream().anyMatch(ore -> ore.distSqr(p) <= 9);
    }

    /**
     * The "shaft" test: a known target in the body's OWN feet column (x/z match),
     * at or above feet, still solid, and reachable (within reach distance AND with
     * a clear sight line). Mined in place, no pathing. The A* stance goal is what
     * gets the body INTO the column; this only fires once it's there. No
     * reach-from-the-side shortcut.
     */
    private BlockPos reachableTarget() {
        if (!player.onGround()) return null;
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        Vec3 eyes = player.getEyePosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos ore : knownOres) {
            if (ore.getX() != feet.getX() || ore.getZ() != feet.getZ()) continue;   // same column
            if (ore.getY() < feet.getY()) continue;                                  // at or above feet
            if (level.getBlockState(ore).isAir()) continue;
            if (!withinReach(ore) || !hasLineOfSight(eyes, ore)) continue;           // reachable
            double d = ore.distSqr(feet.above());
            if (d < bestD) {
                bestD = d;
                best = ore;
            }
        }
        return best;
    }

    /** Clear sight line from the eyes to the target block's centre (nothing solid
     *  blocks it but the target itself). */
    private boolean hasLineOfSight(Vec3 eyes, BlockPos target) {
        BlockHitResult hit = player.level().clip(new ClipContext(
                eyes, Vec3.atCenterOf(target),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    // ---- mining (progressive, tick-by-tick like a real player) ----

    /** Advance the shared dig one tick (it switches to the best tool itself); on the tick the TARGET
     *  breaks, drop it from the ore list. A {@link BlockDigger.DigResult#BROKE_OCCLUDER} (a leaf cleared
     *  to open the line of sight) is NOT the target, so the ore stays. The progress count is read from
     *  the inventory each tick, not here — one block can yield several items, and the drops take a
     *  moment to be picked up.
     *
     *  <p>Recovery: a PERSISTENT {@code NO_SHOT} (the ore passed the reach test but the dig
     *  can never draw a shot at it) is counted, and after {@link #MAX_NO_SHOT_TICKS} the ore
     *  is blacklisted and the loop moves on — matching how a failed path already blacklists
     *  the nearest ore, instead of grinding forever waiting for a shot. */
    private void mineProgress(BlockPos pos) {
        switch (digger.digStep(pos)) {
            case BROKE_TARGET -> {
                knownOres.remove(pos);
                anticipatedDrops.put(pos.immutable(),
                        player.level().getGameTime() + DROP_LOITER_TICKS);
                clearNoShot();
            }
            case NO_SHOT -> {
                if (pos.equals(noShotPos)) {
                    if (++noShotTicks >= MAX_NO_SHOT_TICKS) {
                        blacklist.add(pos.immutable());
                        knownOres.remove(pos);
                        digger.cancel();   // release the in-progress-dig latch on this ore
                        clearNoShot();
                    }
                } else {
                    noShotPos = pos.immutable();
                    noShotTicks = 1;
                }
            }
            // PROGRESSING / BROKE_OCCLUDER — real progress; reset the stall counter.
            default -> clearNoShot();
        }
    }

    private void clearNoShot() {
        noShotPos = null;
        noShotTicks = 0;
    }

    // ---- item counting (progress = matching items held in the inventory) ----

    /** Matching items currently in the inventory (sum of stack counts whose item the targets drop). */
    private int inventoryMatch() {
        if (dropItems.isEmpty()) return baseline;   // before start() resolved the set — no progress yet
        Inventory inv = player.getInventory();
        int sum = 0;
        // 只数主背包 36 格:盔甲/副手不算采集所得。
        for (ItemStack s : inv.getNonEquipmentItems()) {
            if (!s.isEmpty() && dropItems.contains(s.getItem())) sum += s.getCount();
        }
        return sum;
    }

    /** The item set the target blocks drop — the server loot table rolled once per
     *  target with the best harvesting tool we carry (so an ore yields its
     *  ingot/gem, stone yields cobblestone, etc.). Falls back to the block's own item if it has no loot. */
    private Set<Item> computeDropItems() {
        Set<Item> items = new HashSet<>();
        if (!(player.level() instanceof ServerLevel level)) {
            for (Block b : r.targets) items.add(b.asItem());
            return items;
        }
        BlockPos origin = player.blockPosition();
        for (Block b : r.targets) {
            BlockState state = b.defaultBlockState();
            List<ItemStack> drops;
            try {
                drops = Block.getDrops(state, level, origin, null, player, bestToolFor(state));
            } catch (RuntimeException broken) {
                drops = List.of();
            }
            if (drops.isEmpty()) {
                items.add(b.asItem());
            } else {
                for (ItemStack d : drops) items.add(d.getItem());
            }
        }
        return items;
    }

    /** The inventory item that mines {@code state} fastest — the tool the dig will actually use, so the
     *  simulated drops match the real ones (e.g. respects a Silk Touch / Fortune pick if carried). */
    private ItemStack bestToolFor(BlockState state) {
        Inventory inv = player.getInventory();
        ItemStack best = inv.getItem(inv.getSelectedSlot());
        float bestSpeed = best.getDestroySpeed(state);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            float speed = s.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = s;
            }
        }
        return best;
    }

    // ---- ore list maintenance ----

    /** Kick an off-thread scan: capture loaded-chunk refs ring by ring around the
     *  body on this (main) thread — the capture stops at the loaded-area edge —
     *  then walk their section palettes on the scan thread, nearest ring first,
     *  stopping early once {@link #MAX_ORES} are found with one near our own Y. */
    private void kickScan() {
        Level level = player.level();
        // 圆心用寻路口径的脚位格(0.1251 上抬 + 台阶取上格),不是裸 blockPosition:
        // 同层判据与 section 遍历序都从它导出,差一格会改变结果集。
        BlockScanner.RingCapture cap = BlockScanner.captureRings(level,
                BlockHelper.playerFeet(level, player.getX(), player.getY(), player.getZ()));
        scan = ScanExecutor.submit(() -> BlockScanner.scanRings(
                level, cap, r.targets, MAX_ORES, SCAN_Y_THRESHOLD, SCAN_MAX_CHUNK_RADIUS));
        scanDeadline = level.getGameTime() + SCAN_TIMEOUT_TICKS;
    }

    /** Merge a finished background scan into knownOres on the main thread. */
    private void drainScan() {
        if (scan == null) return;
        if (!scan.isDone()) {
            if (player.level().getGameTime() > scanDeadline) {   // wedged — drop it, re-kick later
                scan.cancel(false);
                scan = null;
            }
            return;
        }
        List<BlockScanner.Hit> hits;
        try {
            hits = scan.getNow(List.of());
        } catch (Throwable failed) {
            hits = List.of();
        }
        scan = null;
        mergeHits(hits);
    }

    /** Add fresh, non-blacklisted, non-hazardous hits to knownOres, then prune.
     *  Every candidate is re-validated here on the main thread, so a slightly
     *  stale async scan result is harmless. */
    private void mergeHits(List<BlockScanner.Hit> hits) {
        for (BlockScanner.Hit hit : hits) {
            BlockPos p = hit.pos().immutable();
            if (blacklist.contains(p) || knownOres.contains(p)) continue;
            knownOres.add(p);
        }
        prune();
    }

    private void prune() {
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        CalculationContext ctx = ContextFactory.forExecution(player);
        knownOres.removeIf(p -> {
            var state = level.getBlockState(p);
            if (state.isAir() || !r.targets.contains(state.getBlock()) || blacklist.contains(p)
                    || !plausibleToBreak(ctx, p, state)) {
                return true;
            }
            // Harvestability gate. Tool-skipped cells are remembered so the terminal failure
            // can say "you need a better tool" instead of the misleading "nothing found" (the
            // tool situation can also CHANGE mid-task: the only good pick breaking makes this
            // fire on re-prune).
            if (!BlockHelper.canHarvest(player.getInventory(), state)) {
                unharvestable.add(p.immutable());
                return true;
            }
            return false;
        });
        knownOres.sort(Comparator.comparingDouble(feet::distSqr));
        if (knownOres.size() > MAX_ORES) {
            knownOres.subList(MAX_ORES, knownOres.size()).clear();
        }
    }

    private void blacklistNearest() {
        BlockPos feet = player.blockPosition();
        // 掉落物路过点与矿位同池:走不到的那个不管是矿还是掉落物都拉黑,
        // 否则一个够不着的掉落物能让循环原地打转到超时。
        List<BlockPos> pool = new ArrayList<>(knownOres);
        pool.addAll(drops);
        pool.stream()
                .min(Comparator.comparingDouble(feet::distSqr))
                .ifPresent(p -> {
                    blacklist.add(p.immutable());
                    knownOres.remove(p);
                    com.dwinovo.numen.Constants.LOG.info(
                            "[numen-task] mine blacklisted {} (feet={}, {} target(s) left,"
                                    + " {} blacklisted)",
                            p.toShortString(), feet.toShortString(), knownOres.size(),
                            blacklist.size());
                });
    }

    /** Terminal "nothing gathered, no ore left to go for" failure, distinguishing a
     *  genuinely empty field ({@code MINED_OUT} — widening the search or stopping is the
     *  LLM's call) from a field that WAS found but every target got blacklisted as
     *  unreachable ({@code NO_PATH} — the terrain, not the scan radius, is the problem),
     *  with the counts. */
    private TaskState noOreFailure() {
        if (!unharvestable.isEmpty()) {
            // Targets exist but the carried tools can't make them drop — the actionable
            // problem is the tool, not the deposit. Names the escape hatches explicitly.
            fail("found " + unharvestable.size() + " " + r.label + " but none can be harvested with"
                    + " the current tools (mining would destroy them without any drop); gathered "
                    + r.getMined() + ". Equip a better tool (equip_item) and retry; to just destroy"
                    + " blocks regardless of drops, use break_block.",
                    FailureType.WRONG_TOOL);
            return TaskState.FAILED;
        }
        if (!blacklist.isEmpty()) {
            fail("found " + blacklist.size() + " " + r.label + " nearby but reached none of them"
                    + " — all " + blacklist.size()
                    + " were blacklisted as unreachable (no path / no clear shot); gathered 0",
                    FailureType.NO_PATH);
        } else {
            fail("no reachable " + r.label + " found in the loaded area around me",
                    FailureType.MINED_OUT);
        }
        return TaskState.FAILED;
    }

    private boolean withinReach(BlockPos pos) {
        return player.distanceToSqr(Vec3.atCenterOf(pos)) <= REACH_SQR;
    }

    /** Stop the nav AND clear the branch-mode flag (extends the base's nav release). */
    @Override
    protected void stopNav() {
        super.stopNav();
        navIsBranch = false;
    }

    @Override
    protected void cleanup() {
        // super.cleanup() = stopNav() (nav.stop clears the overlay when a nav exists) + an explicit
        // so a task that finished while shaft-mining (nav == null) still
        // clears its lingering goal boxes. Then release the dig + any in-flight scan.
        super.cleanup();
        digger.cancel();
        if (scan != null) {
            scan.cancel(false);
            scan = null;
        }
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("target", r.label);
        data.put("requested", r.count);
        data.put("gathered", r.getMined());
        return data;
    }

    @Override
    protected String successMessage() {
        return "gathered " + r.getMined() + "/" + r.count + " " + r.label + " (" + progressNote + ")";
    }

    @Override
    protected String timeoutMessage() {
        return "timed out after gathering " + r.getMined() + "/" + r.count + " " + r.label;
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted after gathering " + r.getMined() + "/" + r.count + " " + r.label;
    }
}
