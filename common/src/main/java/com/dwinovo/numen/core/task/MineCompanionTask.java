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
import com.dwinovo.numen.core.pathing.util.NavProfiler;
import com.dwinovo.numen.core.scan.TargetIndex;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code mine} — the scan → path → dig gathering loop, run on the
 * companion player body (a server-side fake player, so every break goes
 * through real server-side interaction rules, not client input).
 *
 * <h2>The loop</h2>
 * <ol>
 *   <li><b>knownOreLocations</b> — fed on demand from the shared {@link TargetIndex}
 *       (block-change-fed, lazily built), and {@link #prune} every tick (drop ones
 *       mined / no longer matching / blacklisted / hazardous), sorted by
 *       distance, capped at {@link #MAX_ORES}.</li>
 *   <li><b>in place</b> — any target the eyes can actually hit from where the body
 *       stands (centre or an exposed face, within block reach, unobstructed) is
 *       broken on the spot, nearest first, auto-switching to the best tool — no
 *       pathing, and never the block the body stands on.</li>
 *   <li><b>owned navigation target</b> — otherwise select one ore stance or drop,
 *       record its kind and position, and keep that ownership through navigation
 *       so a failure can never be charged to a different member.</li>
 *   <li><b>bounded recovery</b> — an arrival without a usable hit, or a dig
 *       without a shot, gets one delayed re-plan before the affected member is
 *       blacklisted.</li>
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

    private static final int MAX_ORES = 64;            // cap on tracked target locations
    /** 目标查询的最大 chebyshev 区块环半径。 */
    private static final int QUERY_MAX_CHUNK_RADIUS = 32;
    /** 名单低于此数触发补货查询——索引由方块变更钩子实时维护,自己挖掉的目标即时出账,
     *  所以只在名单快吃完时才需要真正去查。 */
    private static final int QUERY_LOW_WATER = 16;
    /** 两次查询的最小间隔(tick)。 */
    private static final int QUERY_MIN_GAP_TICKS = 20;
    /** 无条件刷新的慢心跳(tick):兜底外部世界变化(别人放/挖了方块)。 */
    private static final int QUERY_HEARTBEAT_TICKS = 100;
    /** 单次查询允许就地构建的 section 数上限——冷区域在几次查询内渐进变热,不压 tick
     *  (实测 64 时首窗峰 ~3.1ms,48 把单次查询的最坏构建成本压进 ~2.5ms)。 */
    private static final int QUERY_BUILD_BUDGET = 48;
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
    /** One second to let arrival/aim settle before a single bounded re-plan. A second
     *  failed window gives up the exact attempted target, never an inferred neighbour. */
    private static final int RECOVERY_WINDOW_TICKS = 20;
    private static final int MAX_RECOVERY_REPATHS = 1;
    /** How long a just-broken target's cell stays a walk-over goal (ticks) — the drop
     *  takes a moment to spawn, and without this window the body sprints for the next
     *  ore before the item pops and leaves it behind. */
    private static final int DROP_LOITER_TICKS = 5;

    private final List<BlockPos> knownOres = new ArrayList<>();
    /** Ore targets proven unreachable for this task. */
    private final Set<BlockPos> blacklist = new HashSet<>();
    /** Failed walk-over goals are tracked separately so they never inflate ore failures. */
    private final Set<BlockPos> dropBlacklist = new HashSet<>();
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
    /** 无掉落画像(创造)下的进度计数:破坏的目标方块数——背包增量在
     *  这种画像下恒为 0,数拾取物会让任务铲平半径 32 chunk 后报败。 */
    private int brokenTargets;

    private boolean navIsBranch;
    private BlockPos branchPoint;
    private int branchY;
    /** 距下一次允许查询的冷却(tick)。 */
    private int queryCooldown;
    /** 距慢心跳强制刷新的剩余 tick。 */
    private int heartbeatTimer;
    /** 上一次查询时同伴所在 chunk(打包 long)——跨 chunk 视为看到新地形,触发补查。 */
    private long lastQueryChunk = Long.MIN_VALUE;
    private int branchTicks;
    private String progressNote = "done";
    /** Intent target stays latched while BlockDigger may temporarily clear an occluder. */
    private BlockPos miningTarget;
    /** Exact business member owned by the active navigation, never inferred after failure. */
    private MineNavigationAttempt navigationAttempt;
    /** Business member being evaluated after its navigation reported ARRIVED. */
    private MineNavigationAttempt arrivedAttempt;
    private final MineTargetRecovery noShotRecovery =
            new MineTargetRecovery(RECOVERY_WINDOW_TICKS, MAX_RECOVERY_REPATHS);
    private final MineTargetRecovery arrivedRecovery =
            new MineTargetRecovery(RECOVERY_WINDOW_TICKS, MAX_RECOVERY_REPATHS);
    /** 上一次索引查询是否覆盖完整(构建预算未耗尽)。false = 冷区域仍在渐进构建,
     *  终局判定("附近没有目标")必须等它为 true 才能下。 */
    private boolean lastQueryComplete;

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
            if (WorkProfile.of(player).instaBreak()) {
                return null;   // 瞬破画像无视工具等级,工具门不适用
            }
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
        // 登记目标进共享索引并立即首查;冷区域的索引构建由每次查询的预算分摊,
        // 覆盖完整前 onTick 的终局判定会等着(lastQueryComplete)。
        if (player.level() instanceof ServerLevel sl) {
            TargetIndex.register(sl, r.targets);
        }
        runQuery();
        // 与 goto 的 start 日志对称:一任务一条,让日志里能看到任务确实启动了
        com.dwinovo.numen.Constants.LOG.info(
                "[numen-task] mine start targets={} count={} feet={} firstQuery={} hit(s)",
                r.label, r.count, player.blockPosition().toShortString(),
                knownOres.size());
    }

    @Override
    protected TaskState onTick() {
        // 进度口径随画像:有掉落 = 数拾取到的物品(一块矿可能出多个);
        // 无掉落(创造) = 数破坏的目标方块——否则永远数不满。
        int gathered = WorkProfile.of(player).dropsLoot()
                ? Math.max(0, inventoryMatch() - baseline)
                : brokenTargets;
        r.setMined(gathered);
        if (gathered >= r.count) {
            progressNote = "gathered all requested";
            return TaskState.SUCCESS;
        }

        Level level = player.level();

        // Maintain the ore list every tick — INCLUDING while a dig below is latched:
        // prune (cheap — knownOres is capped at 64) revalidates against the live world;
        // the shared TargetIndex is queried on demand (list low / new chunk / slow
        // heartbeat / cold area still building) instead of on a fixed rescan cadence —
        // the block-change hook keeps the index itself current in between.
        long tUpkeep = NavProfiler.begin();
        prune();
        maybeQuery();
        NavProfiler.end("mine.upkeep", tUpkeep);

        // 0) Continue the intent target, not digger.current(): the latter may temporarily
        //    be a safe occluder that BlockDigger is clearing on the target's behalf.
        if (miningTarget != null) {
            if (!knownOres.contains(miningTarget)
                    || level.getBlockState(miningTarget).isAir()) {
                digger.cancel();
                discardCurrentBusinessTarget();
            } else {
                if (nav != null) {
                    nav.pause();   // stand still for the dig; goal/path/in-flight search stay warm
                }
                mineProgress(miningTarget);
                return TaskState.RUNNING;
            }
        }

        long tDrops = NavProfiler.begin();
        drops = droppedItems();
        NavProfiler.end("mine.drops", tDrops);

        // 1) Mine any target we can already reach + see from here (no pathing) —
        //    a tree gets mined from beside, never by digging under it.
        BlockPos reachable = reachableTarget();
        if (reachable != null) {
            arrivedRecovery.clear();
            if (!reachable.equals(noShotRecovery.target())) {
                noShotRecovery.clear();
            }
            navigationAttempt = null;
            arrivedAttempt = null;
            // Mine in place with the nav merely PAUSED (inputs cleared each tick), never torn down:
            // the goal, current path segment, and any in-flight search stay warm, so when this dig
            // ends navigation resumes where it left off instead of cold-starting a fresh A* — that
            // cold start used to surface as a visible stall after every in-place dig. The goal-box
            // overlay also survives for free (nothing clears it anymore).
            if (nav != null) {
                nav.pause();
            }
            mineProgress(reachable);
            return TaskState.RUNNING;
        }

        // 2) Head for the ore field and nearby drops, arriving when a shaft opens up;
        //    drops are collected by walking over them (native pickup).
        if (!knownOres.isEmpty() || !drops.isEmpty()) {
            branchTicks = 0;
            if ((navigationAttempt != null && !navigationTargetValid(navigationAttempt))
                    || (arrivedAttempt != null && !navigationTargetValid(arrivedAttempt))) {
                discardCurrentBusinessTarget();
                stopNav();
            }
            if (nav == null || navIsBranch
                    || (navigationAttempt == null && arrivedAttempt == null)) {
                MineNavigationAttempt next = selectNavigationTarget();
                if (next == null) {
                    stopNav();
                    return TaskState.RUNNING;
                }
                startNavigation(next);
            }
            switch (nav.tick()) {
                case RUNNING -> { return TaskState.RUNNING; }
                case ARRIVED -> {
                    // Arrival normally means an in-place target just became reachable — next tick step 1
                    // pauses the nav and digs. Only clear inputs here (pause), never tear the nav down:
                    // teardown would throw away the goal + any in-flight search and force a cold restart.
                    nav.pause();
                    BlockPos hit = reachableTarget();
                    if (hit != null) {
                        arrivedRecovery.clear();   // next tick's in-place stage starts the dig
                        navigationAttempt = null;
                        arrivedAttempt = null;
                    } else {
                        MineNavigationAttempt arrived = arrivedAttempt;
                        if (arrived == null) {
                            arrived = navigationAttempt;
                            navigationAttempt = null;
                            arrivedAttempt = arrived;
                        }
                        if (arrived == null || !navigationTargetValid(arrived)) {
                            discardCurrentBusinessTarget();
                            stopNav();
                            return TaskState.RUNNING;
                        }
                        MineTargetRecovery.Decision decision = arrivedRecovery.miss(arrived.pos());
                        if (arrivedRecovery.ticks() == 1) {
                            com.dwinovo.numen.Constants.LOG.info(
                                    "[numen-task] mine ARRIVED-dud feet={} attempted={} kind={} repaths={} — waiting",
                                    player.blockPosition().toShortString(), arrived.pos().toShortString(),
                                    arrived.kind() == MineNavigationAttempt.Kind.ORE ? "ore" : "drop",
                                    arrivedRecovery.repaths());
                        }
                        if (decision == MineTargetRecovery.Decision.REPATH) {
                            com.dwinovo.numen.Constants.LOG.info(
                                    "[numen-task] mine ARRIVED-dud attempted={} — bounded re-plan",
                                    arrived.pos().toShortString());
                            startNavigation(arrived);
                        } else if (decision == MineTargetRecovery.Decision.GIVE_UP) {
                            blacklistAttempt(arrived, "arrival never produced a reachable hit");
                            discardCurrentBusinessTarget();
                            stopNav();
                        }
                    }
                    return TaskState.RUNNING;   // a reachable shaft is handled next tick
                }
                case FAILED -> {
                    MineNavigationAttempt failed = navigationAttempt;
                    com.dwinovo.numen.Constants.LOG.info(
                            "[numen-task] mine nav failed ({}): {} | candidate={} kind={}",
                            nav.failType(), nav.failReason(),
                            failed == null ? "none" : failed.pos().toShortString(),
                            failed == null ? "none" : failed.kind().name().toLowerCase());
                    blacklistAttempt(failed, "navigation failed: " + nav.failType());
                    discardCurrentBusinessTarget();
                    stopNav();
                    return TaskState.RUNNING;
                }
            }
        }

        // 3) No ore known and nothing dropped nearby. An incomplete index (cold area
        //    still building under the per-query budget) means "don't know yet", not
        //    "nothing there" — wait for full coverage before any verdict.
        if (!lastQueryComplete) {
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

    /** Compile only the business member owned by the active navigation attempt. */
    private GoalCompiler.Compiled navigationGoalCompiled() {
        MineNavigationAttempt attempt = navigationAttempt;
        if (attempt == null || !navigationTargetValid(attempt)) {
            return GoalCompiler.standOn(player.blockPosition());
        }
        if (attempt.kind() == MineNavigationAttempt.Kind.DROP) {
            return GoalCompiler.standOn(attempt.pos());
        }
        CalculationContext ctx = ContextFactory.forExecution(player);
        return GoalCompiler.mineField(List.of(coalesce(ctx, attempt.pos())), List.of());
    }

    private MineNavigationAttempt selectNavigationTarget() {
        return MineNavigationAttempt.nearest(player.blockPosition(), knownOres, drops);
    }

    private boolean navigationTargetValid(MineNavigationAttempt attempt) {
        if (attempt.kind() == MineNavigationAttempt.Kind.DROP) {
            return drops.contains(attempt.pos());
        }
        BlockState state = player.level().getBlockState(attempt.pos());
        return knownOres.contains(attempt.pos()) && !state.isAir()
                && r.targets.contains(state.getBlock());
    }

    private void startNavigation(MineNavigationAttempt attempt) {
        stopNav();
        if (!navigationTargetValid(attempt)) {
            discardCurrentBusinessTarget();
            return;
        }
        // Preserve the spent budget only for the same valid ore's NO_SHOT re-plan.
        if (attempt.kind() != MineNavigationAttempt.Kind.ORE
                || !attempt.pos().equals(noShotRecovery.target())) {
            noShotRecovery.clear();
        }
        navigationAttempt = attempt;
        nav = PlayerNav.toRevalidating(player, this::navigationGoalCompiled, MINE_SPEED,
                () -> reachableTarget() != null);
        nav.setHighlights(() -> new ArrayList<>(knownOres));
        navIsBranch = false;
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
     *
     * <p>The picked band then goes through {@link #extendToFloor}: a band whose
     * bottom hangs in mid-air (a mined-out trunk column) is stretched down
     * through the air to the first standable floor — as far as the ore stays
     * overhead-hittable from there ({@link #MAX_STANCE_DEPTH}) — so such a
     * target can be stood under and broken from the ground instead of demanding
     * a mid-air stance nobody can reach without scaffolding.
     */
    private GoalCompiler.Stance coalesce(CalculationContext ctx, BlockPos loc) {
        boolean assumeVerticalShaftMine =
                !(player.level().getBlockState(loc.above()).getBlock()
                        instanceof net.minecraft.world.level.block.FallingBlock);
        boolean upwardGoal = internalMiningGoal(ctx, loc.above());
        boolean downwardGoal = internalMiningGoal(ctx, loc.below());
        boolean doubleDownwardGoal = internalMiningGoal(ctx, loc.below(2));
        GoalCompiler.Stance stance;
        if (upwardGoal == downwardGoal) {                       // symmetric vertically
            stance = (doubleDownwardGoal && assumeVerticalShaftMine)
                    ? GoalCompiler.Stance.at(loc, 2)   // feet up to 2 below the ore
                    : GoalCompiler.Stance.at(loc, 1);  // feet at the ore or 1 below
        } else if (upwardGoal) {                                // bottom of a run: stand in it
            stance = GoalCompiler.Stance.at(loc, 0);   // feet EXACTLY at the ore
        } else {
            stance = (doubleDownwardGoal && assumeVerticalShaftMine) // top of a run, more below
                    ? new GoalCompiler.Stance(loc, loc.below(), 1)  // feet at/1-below the block under it
                    : new GoalCompiler.Stance(loc, loc.below(), 0); // feet exactly at the block under it
        }
        return extendToFloor(stance);
    }

    /** 脚位到目标的最大垂直距离:站在目标正下方仰头,眼高 1.62 + 触及 4.5 ≈ 6.1,
     *  即目标底面在脚上 6 格内仍可命中——波段最多下探到此,再深就算站得住也打不到了。 */
    private static final int MAX_STANCE_DEPTH = 6;

    /**
     * Stretch a stance band whose bottom hangs in mid-air down to the first standable
     * floor, capped so the ore stays overhead-hittable from the band bottom
     * ({@link #MAX_STANCE_DEPTH}). The walk only descends through walkable air and
     * stops the moment the band rests on ground — it never tunnels: a band already
     * grounded (fresh trunk) or sitting in solid terrain (buried ore, dig-in
     * semantics) is returned unchanged, so "never dug under" stays intact.
     */
    private GoalCompiler.Stance extendToFloor(GoalCompiler.Stance s) {
        Level level = player.level();
        int depth = s.maxBelow();
        while (true) {
            BlockPos feet = s.stanceBase().below(depth);
            if (MovementHelper.canWalkOn(level, feet.below())) {
                break;   // band bottom rests on a floor — grounded, done
            }
            if (s.ore().getY() - (feet.getY() - 1) > MAX_STANCE_DEPTH) {
                break;   // one step deeper and the ore would leave overhead reach
            }
            if (!MovementHelper.canWalkThrough(level, feet.below())) {
                break;   // something unstandable-yet-unpassable below — keep the band as is
            }
            depth++;
        }
        return depth == s.maxBelow() ? s
                : new GoalCompiler.Stance(s.ore(), s.stanceBase(), depth);
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
            if (dropBlacklist.contains(p) || nearKnownOre(p)) continue;
            out.add(p);
        }
        for (BlockPos p : anticipatedDrops.keySet()) {
            if (dropBlacklist.contains(p) || nearKnownOre(p)) continue;
            out.add(p);
        }
        return out;
    }

    /** 距任一已知矿位 3 格内(distSqr ≤ 9)——挖那颗矿自然会带身体过去。 */
    private boolean nearKnownOre(BlockPos p) {
        return knownOres.stream().anyMatch(ore -> ore.distSqr(p) <= 9);
    }

    /**
     * Pre-filter for the in-place pick, squared: candidates farther than this from the feet can't be
     * within block reach of the eyes (4.5 eye reach + 1.62 eye height + aim-point slack), so they are
     * skipped without spending rays. {@link #knownOres} is kept sorted nearest-first by {@link #prune},
     * so iteration simply stops at the first candidate beyond the filter.
     */
    private static final double IN_PLACE_FILTER_SQR = 7.0 * 7.0;

    /**
     * The in-place mining pick: the nearest known target the eyes can ACTUALLY hit from where the body
     * stands right now ({@link BlockDigger#findReachableHit}: centre + exposed face points, within block reach, nothing
     * solid in the way) — mined on the spot, no pathing. Column and height don't matter; hittability
     * does. The one hard exception is the support cell directly under the feet — never dig out our own
     * floor. Anything the eyes can't hit from here is left to the navigator (walk to a stance, pillar
     * up, etc.).
     */
    private BlockPos reachableTarget() {
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        BlockPos support = feet.below();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos ore : knownOres) {
            if (ore.distSqr(feet) > IN_PLACE_FILTER_SQR) {
                break;   // sorted nearest-first — everything after this is farther still
            }
            if (ore.equals(support) || level.getBlockState(ore).isAir()) {
                continue;
            }
            double d = ore.distSqr(feet.above());
            if (d >= bestD || digger.findReachableHit(ore) == null) {
                continue;
            }
            bestD = d;
            best = ore;
        }
        return best;
    }

    // ---- mining (progressive, tick-by-tick like a real player) ----

    /** Advance the shared dig one tick (it switches to the best tool itself); on the tick the TARGET
     *  breaks, drop it from the ore list. A {@link BlockDigger.DigResult#BROKE_OCCLUDER} (a leaf cleared
     *  to open the line of sight) is NOT the target, so the ore stays. The progress count is read from
     *  the inventory each tick, not here — one block can yield several items, and the drops take a
     *  moment to be picked up.
     *
     *  <p>Recovery: persistent {@code NO_SHOT} gets one bounded re-plan before the exact
     *  intent target is blacklisted. */
    private void mineProgress(BlockPos pos) {
        miningTarget = pos.immutable();
        switch (digger.digStep(pos)) {
            case BROKE_TARGET -> {
                knownOres.remove(pos);
                brokenTargets++;
                if (WorkProfile.of(player).dropsLoot()) {
                    // 无掉落画像不登记逗留格:等一个永不出现的掉落物只会来回绕路
                    anticipatedDrops.put(pos.immutable(),
                            player.level().getGameTime() + DROP_LOITER_TICKS);
                }
                discardCurrentBusinessTarget();
            }
            case NO_SHOT -> {
                MineTargetRecovery.Decision decision = noShotRecovery.miss(pos);
                if (decision == MineTargetRecovery.Decision.REPATH) {
                    com.dwinovo.numen.Constants.LOG.info(
                            "[numen-task] mine NO_SHOT target={} — cancelling dig and re-planning once",
                            pos.toShortString());
                    digger.cancel();
                    miningTarget = null;
                    arrivedRecovery.clear();
                    startNavigation(new MineNavigationAttempt(
                            pos, MineNavigationAttempt.Kind.ORE));
                } else if (decision == MineTargetRecovery.Decision.GIVE_UP) {
                    blacklistAttempt(new MineNavigationAttempt(
                                    pos, MineNavigationAttempt.Kind.ORE),
                            "no reachable dig hit after bounded re-plan");
                    digger.cancel();
                    discardCurrentBusinessTarget();
                    stopNav();
                }
            }
            // PROGRESSING / BROKE_OCCLUDER — real progress; reset recovery.
            default -> {
                noShotRecovery.clear();
                arrivedRecovery.clear();
            }
        }
    }

    // ---- item counting (progress = matching items held in the inventory) ----

    /** Matching items currently in the inventory (sum of stack counts whose item the targets drop). */
    private int inventoryMatch() {
        if (dropItems.isEmpty()) return baseline;   // before start() resolved the set — no progress yet
        Inventory inv = player.getInventory();
        int sum = 0;
        // 只数主背包 36 格:盔甲/副手不算采集所得。
        for (ItemStack s : inv.items) {
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
        ItemStack best = inv.getItem(inv.selected);
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

    /** 按需查询:名单快吃完 / 进入新 chunk / 慢心跳到点 / 上次覆盖不完整,才碰索引。 */
    private void maybeQuery() {
        --queryCooldown;
        --heartbeatTimer;
        if (queryCooldown > 0) {
            return;
        }
        if (knownOres.size() < QUERY_LOW_WATER
                || ChunkPos.asLong(player.blockPosition()) != lastQueryChunk
                || heartbeatTimer <= 0
                || !lastQueryComplete) {
            runQuery();
        }
    }

    /** 查一次共享索引,把最近的目标并进名单。 */
    private void runQuery() {
        if (!(player.level() instanceof ServerLevel sl)) {
            return;
        }
        lastQueryChunk = ChunkPos.asLong(player.blockPosition());
        heartbeatTimer = QUERY_HEARTBEAT_TICKS;
        queryCooldown = QUERY_MIN_GAP_TICKS;
        TargetIndex.Result res = TargetIndex.query(sl, player.blockPosition(), r.targets,
                MAX_ORES, QUERY_MAX_CHUNK_RADIUS, QUERY_BUILD_BUDGET);
        lastQueryComplete = res.complete();
        com.dwinovo.numen.Constants.LOG.debug(
                "[numen-task] mine query feet={} raw={} complete={} known(before merge)={}",
                player.blockPosition().toShortString(), res.hits().size(), res.complete(),
                knownOres.size());
        mergeHits(res.hits());
    }

    /** Add fresh, non-blacklisted hits to knownOres, then prune (which re-validates
     *  every entry against the live world and keeps the nearest {@link #MAX_ORES}). */
    private void mergeHits(List<BlockPos> hits) {
        // One-off Set view for dedup: knownOres stays a distance-ordered list (prune sorts it),
        // but membership checks against it must not be linear scans — a big batch times a
        // linear contains is O(N^2) on the server thread.
        Set<BlockPos> seen = new HashSet<>(knownOres);
        for (BlockPos hit : hits) {
            BlockPos p = hit.immutable();
            if (blacklist.contains(p) || !seen.add(p)) continue;
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
            if (!WorkProfile.of(player).instaBreak()
                    && !BlockHelper.canHarvest(player.getInventory(), state)) {
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

    private void blacklistAttempt(MineNavigationAttempt attempt, String reason) {
        if (attempt == null) return;
        BlockPos pos = attempt.pos();
        if (attempt.kind() == MineNavigationAttempt.Kind.ORE) {
            BlockState state = player.level().getBlockState(pos);
            if (!knownOres.contains(pos) || state.isAir()
                    || !r.targets.contains(state.getBlock())) {
                knownOres.remove(pos);   // vanished/changed is not a navigation failure
                return;
            }
            attempt.recordFailure(blacklist, dropBlacklist);
            knownOres.remove(pos);
        } else {
            if (!drops.contains(pos)) return;   // already picked up/despawned
            attempt.recordFailure(blacklist, dropBlacklist);
            drops.remove(pos);
        }
        com.dwinovo.numen.Constants.LOG.info(
                "[numen-task] mine blacklisted {} kind={} reason={} (feet={}, {} ore(s) left,"
                        + " {} ore(s) blacklisted)",
                pos.toShortString(), attempt.kind().name().toLowerCase(), reason,
                player.blockPosition().toShortString(), knownOres.size(), blacklist.size());
    }

    /** Forget all transient state owned by a business target that ended or became invalid. */
    private void discardCurrentBusinessTarget() {
        navigationAttempt = null;
        arrivedAttempt = null;
        miningTarget = null;
        arrivedRecovery.clear();
        noShotRecovery.clear();
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

    /** Stop the nav and clear branch-mode state. */
    @Override
    protected void stopNav() {
        super.stopNav();
        navIsBranch = false;
        navigationAttempt = null;
        arrivedAttempt = null;
    }

    @Override
    protected void cleanup() {
        // super.cleanup() = stopNav() (nav.stop clears the overlay when a nav exists) + an explicit
        // so a task that finished while shaft-mining (nav == null) still
        // clears its lingering goal boxes. Then release the dig + the index registration.
        super.cleanup();
        digger.cancel();
        discardCurrentBusinessTarget();
        if (player.level() instanceof ServerLevel sl) {
            TargetIndex.unregister(sl, r.targets);
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
