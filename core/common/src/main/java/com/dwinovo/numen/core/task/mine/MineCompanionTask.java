package com.dwinovo.numen.core.task.mine;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.FailureType;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.bridge.ContextFactory;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.moves.ActionCosts;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.act.BlockDigger;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.pathing.util.NavProfiler;
import com.dwinovo.numen.core.scan.BlockScanner;
import com.dwinovo.numen.core.scan.BlockSearch;
import com.dwinovo.numen.core.scan.GroupBook;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.permission.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
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
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

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
 * <h2>两种用法,一个循环</h2>
 * {@code block_ids} 用法的候选来自搜索({@link BlockSearch});{@code groups} 用法的候选只是点名的团里的格子
 * ({@link GroupBook},开工时按编号取),每格还得是扫描时记下的那种方块,不往外扩。除了候选从哪来,走、挖、
 * 捡、问权限、收场都是同一条路。点名的格子途中被别人挖掉或变了,照常挖剩下的,回执如实交代;全都没了就
 * 如实收场。
 *
 * <h2>The loop</h2>
 * <ol>
 *   <li><b>knownOreLocations</b> — fed on demand by {@link BlockSearch} (the one way to
 *       find blocks; the task holds its targets there, so the shared index stays fresh
 *       through the block-change hook and repeated searches read a warm cache), and
 *       {@link #prune} every tick (drop ones mined / no longer matching / unworkable /
 *       hazardous), sorted by distance, capped at {@link #MAX_ORES}.</li>
 *   <li><b>in place</b> — any target the eyes can actually hit from where the body
 *       stands (centre or an exposed face, within block reach, unobstructed) is
 *       broken on the spot, nearest first, auto-switching to the best tool — no
 *       pathing, and never the block the body stands on.</li>
 *   <li><b>composite goal</b> — otherwise head for the whole ore field at once:
 *       one A* search over {@link NavGoal#composite} of {@link NavGoal#mine}
 *       stances, so it walks to the CLOSEST reachable ore (not greedy-nearest,
 *       which is often the walled-in one).</li>
 *   <li><b>够不着是一批的属性,不是某一格的罪</b> — 复合目标搜不出路,意思是
 *       <b>这一刻这一批都到不了</b>,不是"最近那颗有问题"。所以这里不记账到任何一格:
 *       重新规划就是了。既没挖掉一格、也没挪窝超过 {@link #STALL_TICKS} 刻,才收工,
 *       并如实报告"剩下的走不到"。</li>
 * </ol>
 *
 * <h2>主人的东西</h2>
 * 选目标不看权限:主人放的原木和野树一样是候选。路线规格默认是 {@link RouteSpec.Alter#ANY}(模型给的
 * {@code spec} 叠在上面,{@code avoid_break} 这类限制经 {@link #plausibleToBreak} 直接作用到候选上),
 * 需要主人同意的格在成本模型里乘 {@code CONSENT_COST_MULTIPLIER}——挑目标按"走过去 + 挖它"的
 * 同一套定价({@link #targetCost}),附近有野树时自然先挖野树。轮到一格,动手之前把这次挖掘交给
 * 权限层({@link #permit}):要问就等主人点头,同一行规则问出来的同一种方块从此本任务内不再问;
 * 不许(主人拒绝、观察模式、服务器退回)就按 {@link FailureType#REFUSED} 带着理由收场。路上要穿过需要
 * 同意的格,由导航在开走前问。mine 自己不判、不跳、不问,只提出动作。
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
    private static final double REACH_SQR = 4.5 * 4.5;
    private static final double MINE_SPEED = 1.0;
    /** 同一格连续这么多刻拉不出射线,就记进 {@link #unworkable} —— 够到测试说它能挖,
     *  可射线始终成不了(瞄准量化、站位上方有个檐口)。没有这条,挖掘会永远等一个
     *  不会来的射线。 */
    private static final int MAX_NO_SHOT_TICKS = 20;
    /**
     * 既没挖掉一格、也没挪窝多远,持续这么多刻就算真卡住了(二十秒)。
     *
     * <p><b>两个条件同时成立才算</b>:她走三十秒的路去远处挖矿,一刻都不算卡 —— 她在动。
     * 只有"站着不动又什么都没挖出来"才是卡住,而那种状态没有出口,只能收工报给主人。
     */
    private static final int STALL_TICKS = 400;

    /** 挪出这么远就算"她在动",进度计时重新起算。 */
    private static final double STALL_MOVE = 2.0;

    /** How long a just-broken target's cell stays a walk-over goal (ticks) — the drop
     *  takes a moment to spawn, and without this window the body sprints for the next
     *  ore before the item pops and leaves it behind. */
    private static final int DROP_LOITER_TICKS = 5;

    private final List<BlockPos> knownOres = new ArrayList<>();
    /**
     * 当前地形下挖不动的格子 —— <b>只有 {@code NO_SHOT} 进得来</b>:够到测试过了,却连续
     * 二十刻拉不出射线(瞄准量化、站位上方有个檐口)。这是关于<b>这一格</b>的、可复现的事实。
     *
     * <p>"走不到"不进这里:那是一批的属性,不是某一格的罪。掉落物更不进 —— 够不着的掉落物
     * 在复合目标下根本不会被选中。
     *
     * <p>而且它<b>不是永久的</b>:她成功挖掉任何一格,地形就变了(挡射线的那个檐口可能正好
     * 被挖了),整份作废重来。
     */
    private final Set<BlockPos> unworkable = new HashSet<>();
    /** Targets pruned because no carried tool harvests them (force=false only) — kept so the
     *  terminal failure can name the tool problem instead of reporting an empty field. */
    private final Set<BlockPos> unharvestable = new HashSet<>();
    /**
     * 每个候选"到了之后挖它"的价钱(刻),{@link #prune} 每刻按成本模型现算:挖掘耗时乘权限层的
     * 定价(需要同意的乘 {@code CONSENT_COST_MULTIPLIER},不许的是 {@code COST_INF})。
     */
    private final Map<BlockPos, Double> digCosts = new HashMap<>();
    /**
     * 按总价挑目标的导航在这一格落定了(搜索挑中的就是脚下):手边够得着的就是该挖的,不再因为
     * 别处估价更低而让路。挖掉一格或挪了窝就作废。
     */
    private BlockPos settledAt;
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

    /** groups 用法点名的格子和扫描时记下的方块;block_ids 用法为 null。 */
    private Map<BlockPos, Block> named;
    /** groups 用法里出现的方块种类。 */
    private Set<Block> namedKinds = Set.of();
    /** groups 用法里还没收进名单的点名格。挖不动的格也回到这里,地形一变还能再收。 */
    private final Set<BlockPos> remaining = new HashSet<>();
    /** groups 用法里轮到时已经不是扫描时那种方块的格(被挖掉、被换掉)。 */
    private final Set<BlockPos> gone = new HashSet<>();
    /** 挖不成的候选:挖不动的方块、规格禁挖的、贴着流体或悬空落沙的——{@link #plausibleToBreak} 说不的。 */
    private final Set<BlockPos> ruledOut = new HashSet<>();
    /** 这件活的路线规格:mine 的默认叠上模型给的。 */
    private final PlayerNav.ContextProvider terrain;

    /** 距下一次允许查询的冷却(tick)。 */
    private int queryCooldown;
    /** 距慢心跳强制刷新的剩余 tick。 */
    private int heartbeatTimer;
    /** 上一次查询时同伴所在 chunk(打包 long)——跨 chunk 视为看到新地形,触发补查。 */
    private long lastQueryChunk = Long.MIN_VALUE;
    private String progressNote = "done";
    /** The ore currently returning {@code NO_SHOT}, and for how many consecutive ticks. */
    private BlockPos noShotPos;
    private int noShotTicks;
    /** 上一次真有进展(挖掉一格)或明显挪窝的时刻与位置 —— 卡死判定的量尺。 */
    private long lastProgressTick;
    private BlockPos lastProgressPos;

    /** 地图不完整时连续无路的次数（见 {@link NoPathVerdict}）。 */
    private int coldMapFails;
    /** 上一次搜索是否走完了(没被期限截断)。还没有搜索回来、或被截断时为 false——
     *  终局判定("附近没有目标")必须等它为 true 才能下。 */
    private boolean lastQueryComplete;
    /** 在飞的搜索句柄;0 表示没有。 */
    private int searchId;
    /** 回来了还没并进名单的搜索结果。 */
    private BlockSearch.ScanResult arrived;
    /** 开工时在 {@link BlockSearch} 持有了目标登记;收尾只放下自己持有的。 */
    private boolean holding;

    // Progressive dig (blocks break tick-by-tick at legitimate player speed, not
    // instabreak) — shared with the path executor so all breaking reads the same.
    private final BlockDigger digger;

    public MineCompanionTask(NumenPlayer player, MineBlockTaskRecord record) {
        super(player, record);
        this.digger = new BlockDigger(player);
        this.terrain = PlayerNav.ContextProvider.of(record.spec);
    }

    @Override
    protected List<Precondition> preconditions() {
        // Fail fast if NO requested target is harvestable with the current inventory — mining it
        // would destroy the block for no drop. Same gate as the cost model
        // (BlockHelper.canHarvest, whole-inventory). prune() then drops any individual unharvestable
        // cell, so a mixed request (e.g. coal we can mine + diamond we can't) still works.
        return List.of(this::resolveGroups, () -> {
            if (WorkProfile.of(player).instaBreak()) {
                return null;   // 瞬破画像无视工具等级,工具门不适用
            }
            boolean anyHarvestable = kinds().stream().anyMatch(
                    b -> BlockHelper.canHarvest(player.getInventory(), b.defaultBlockState()));
            if (!anyHarvestable) {
                return new Precondition.Failure(
                        "can't harvest " + r.label + " with the current tools — mining it would"
                        + " destroy it without any drop. Equip a suitable tool (e.g. a pickaxe)"
                        + " first; to just destroy a block regardless of drops, goto beside it and use interact_at"
                        + " with button left.",
                        FailureType.WRONG_TOOL);
            }
            return null;
        });
    }

    /**
     * groups 用法:按编号取身体上团簿里最新一次扫描的团。有编号不在里面(更早的扫描、重启前的扫描、
     * 从没扫过)就明说过期,让模型重新扫描——旧编号不会被悄悄当成别的团。
     */
    private Precondition.Failure resolveGroups() {
        if (r.groups.isEmpty()) {
            return null;
        }
        GroupBook book = GroupBook.of(player);
        String stale = book.staleMessage(r.groups);
        if (stale != null) {
            return new Precondition.Failure(stale, FailureType.TARGET_LOST);
        }
        named = book.cells(r.groups);
        namedKinds = Set.copyOf(named.values());
        return null;
    }

    /** 这件活要的方块种类:block_ids 用法点名的,或 groups 用法团里扫描时记下的。 */
    private Set<Block> kinds() {
        return named == null ? r.targets : namedKinds;
    }

    @Override
    protected void onStart() {
        // Count toward `count` by ITEMS gathered, not blocks broken: resolve what these
        // blocks drop, and snapshot how many we already hold so the tally is the delta above it.
        dropItems = computeDropItems();
        baseline = inventoryMatch();
        if (named != null) {
            // 候选就是点名的格子,不用搜;没给 count 的话期限按格数给
            remaining.addAll(named.keySet());
            r.setCells(named.size());
            lastQueryComplete = true;
            if (r.count == MineBlockTaskRecord.UNTIL_GONE) {
                r.extendDeadlineTo(player.level().getGameTime() + MineBlockTaskRecord.timeoutTicks(named.size()));
            }
        } else {
            // 持有目标的登记,让共享索引在任务期间保持新鲜,并立即起首次搜索;冷区域的读地形由
            // 每刻的读节配额分摊,首批结果回来前 onTick 的终局判定会等着(lastQueryComplete)。
            if (player.level() instanceof ServerLevel sl) {
                BlockSearch.hold(sl, r.targets);
                holding = true;
            }
            runQuery();
        }
        lastProgressTick = player.level().getGameTime();
        lastProgressPos = player.blockPosition();
        // 与 goto 的 start 日志对称:一任务一条,让日志里能看到任务确实启动了
        com.dwinovo.numen.core.Constants.LOG.info(
                "[numen-task] mine start targets={} count={} cells={} feet={}",
                r.label, r.count, named == null ? "-" : named.size(), player.blockPosition().toShortString());
    }

    @Override
    protected TaskState onTick() {
        // 进度口径随画像:有掉落 = 数拾取到的物品(一块矿可能出多个);
        // 无掉落(创造) = 数破坏的目标方块——否则永远数不满。挖完点名的团为止的,数挖掉的格。
        boolean untilGone = r.count == MineBlockTaskRecord.UNTIL_GONE;
        int gathered = WorkProfile.of(player).dropsLoot() && !untilGone
                ? Math.max(0, inventoryMatch() - baseline)
                : brokenTargets;
        r.setMined(gathered);
        if (!untilGone && gathered >= r.count) {
            progressNote = "gathered all requested";
            return TaskState.SUCCESS;
        }

        Level level = player.level();

        // Maintain the ore list every tick — INCLUDING while a dig below is latched:
        // prune (cheap — knownOres is capped at 64) revalidates against the live world;
        // a search is started on demand (list low / new chunk / slow heartbeat / last one
        // cut short) instead of on a fixed rescan cadence — the block-change hook keeps
        // the shared index current in between.
        long tUpkeep = NavProfiler.begin();
        absorbSearch();
        prune();
        maybeQuery();
        NavProfiler.end("mine.upkeep", tUpkeep);

        // 0) Continue an in-progress dig, locked onto its block (no re-selection)
        //    until it breaks or drifts out of reach.
        BlockPos digging = digger.current();
        if (digging != null) {
            if (level.getBlockState(digging).isAir() || !reachable(digging)) {
                digger.cancel();
            } else {
                if (nav != null) {
                    nav.pause();   // stand still for the dig; goal/path/in-flight search stay warm
                }
                return mineProgress(digging);
            }
        }

        long tDrops = NavProfiler.begin();
        drops = droppedItems();
        NavProfiler.end("mine.drops", tDrops);

        // 1) Mine any target we can already reach + see from here (no pathing) —
        //    a tree gets mined from beside, never by digging under it.
        BlockPos reachable = reachableTarget();
        if (reachable != null) {
            // Mine in place with the nav merely PAUSED (inputs cleared each tick), never torn down:
            // the goal, current path segment, and any in-flight search stay warm, so when this dig
            // ends navigation resumes where it left off instead of cold-starting a fresh A* — that
            // cold start used to surface as a visible stall after every in-place dig. The goal-box
            // overlay also survives for free (nothing clears it anymore).
            if (nav != null) {
                nav.pause();
            }
            // 动手之前:这一格交给权限层。要问就站着等主人,不许就带着理由收场
            Permit permit = permit(Action.breakBlock(reachable, level.getBlockState(reachable)), r.describe());
            if (permit.state() == PermitState.WAITING) {
                InputDriver.halt(player);
                return TaskState.RUNNING;
            }
            if (permit.state() == PermitState.REFUSED) {
                fail("could not mine " + r.label + ": " + permit.refusal() + "; " + soFar(), FailureType.REFUSED);
                return TaskState.FAILED;
            }
            return mineProgress(reachable);
        }

        // 2) Head for the ore field + nearby drops (GoalComposite), arriving when a
        //    shaft opens up; drops are collected by walking over them (native pickup).
        if (!knownOres.isEmpty() || !drops.isEmpty()) {
            TaskState stalled = stalledOut();
            if (stalled != null) {
                return stalled;
            }
            if (nav == null) {
                stopNav();
                // Compiled front door: one composite over every known ore's stance plus nearby
                // drops. The route MAY chop a target on the way past — an en-route break is
                // progress (prune drops the cell, the drop members collect the item, and the
                // tally counts inventory); see GoalCompiler.mineField.
                // Revalidating: the ore field changes every few ticks (mined cells pruned,
                // rescans merging, unworkable cells trimming), so hand the freshly compiled goal to
                // the engine EVERY tick — the current segment is kept unless its destination
                // is no longer accepted by the new goal (then it soft-cancels and re-plans),
                // and standing in a stance whose ore just got mined out resumes navigation
                // instead of reporting a stale arrival.
                nav = PlayerNav.toRevalidating(player, this::oreFieldCompiled, MINE_SPEED,
                        () -> reachableTarget() != null, terrain);
            }
            switch (nav.tick()) {
                case RUNNING -> { return TaskState.RUNNING; }
                case ARRIVED -> {
                    // Arrival normally means an in-place target just became reachable — next tick step 1
                    // pauses the nav and digs. Only clear inputs here (pause), never tear the nav down:
                    // teardown would throw away the goal + any in-flight search and force a cold restart.
                    nav.pause();
                    // 搜索按总价挑中的就是这儿:手边够得着的就挖,别再为别处的估价让路
                    settledAt = player.blockPosition();
                    // [ANCHOR arrived-dud] 到了站位,却什么都够不到。<b>这不构成关于任何一颗矿的
                    // 证据</b>:最常见的两种成因根本不是故障 —— 她到的是复合目标里的<b>掉落物</b>
                    // 成员(刚捡完东西,附近本来就没矿),或者这一刻人在空中(reachableTarget 第一行
                    // 就要求 onGround)。剩下的"被别的矿包住、射线打不到"也只是<b>还没轮到它</b>,
                    // 外层挖掉自己就露出来了。
                    //
                    // 所以这里只重新规划。真卡住了由 STALL_TICKS 那把尺子收工,不记账到某一格。
                    if (reachableTarget() == null && !knownOres.isEmpty()) {
                        com.dwinovo.numen.core.Constants.LOG.debug(
                                "[numen-task] mine ARRIVED 但够不到 feet={} nearestOre={} —— 重规划",
                                player.blockPosition().toShortString(), nearestOreInfo());
                        stopNav();
                    }
                    return TaskState.RUNNING;   // a reachable shaft is handled next tick
                }
                case FAILED -> {
                    // [ANCHOR nav-cold-map] 地图自己都说了还没查完，这个“没路”不算证据。
                    //
                    // 世界刚加载时共用索引是冷的，第一次查询烧完预算也扫不完请求半径
                    // ({@code complete=false})，名单里可能只有几十格外的一簇，而脚边那片还没进图。
                    // 拿这种半张图上的无路去永久拉黑一个好方块，是把“我还不知道”当成了“不可能”。
                    //
                    // 跟上面 ARRIVED-dud 是同一条纪律：拉黑只该给真正失败的路。
                    if (NoPathVerdict.of(lastQueryComplete, coldMapFails)
                            == NoPathVerdict.Verdict.REQUERY) {
                        if (++coldMapFails == 1) {
                            com.dwinovo.numen.core.Constants.LOG.info(
                                    "[numen-task] mine nav failed ({}) 但目标图还没查完 —— 不拉黑，重查 | nearestOre={}",
                                    nav.failType(), nearestOreInfo());
                        }
                        stopNav();
                        queryCooldown = 0;   // 下一刻就接着建图，别干等冷却
                        return TaskState.RUNNING;
                    }
                    // [ANCHOR nav-failed] 完整图上真的没路。
                    //
                    // <b>这句话的主语是"这一批",不是"最近那颗"。</b>复合目标撒在全部目标上,
                    // 搜不出路的意思是一个都到不了 —— 拿"离脚最近的"顶罪只是猜,而猜错了不会
                    // 报错(日志只会写"记下 X",而 X 看着完全合理)。所以这里什么都不记,
                    // 重新规划;真的一直出不去,由 STALL_TICKS 收工。
                    com.dwinovo.numen.core.Constants.LOG.info(
                            "[numen-task] mine nav failed ({}): {} | 复合目标 {} 个,nearestOre={}",
                            nav.failType(), nav.failReason(), knownOres.size(), nearestOreInfo());
                    coldMapFails = 0;
                    stopNav();
                    return TaskState.RUNNING;
                }
            }
        }

        // 3) No ore known and nothing dropped nearby. A search still in flight, or the last
        //    one cut short, means "don't know yet", not "nothing there" — wait for it before
        //    any verdict. 等搜索的刻不烧任务预算:读地形按真实时间分摊,而期限数游戏刻——
        //    tick 远快于真实时间时(/tick rate、不限速的测试服),期限会在首查返回前烧光,
        //    任务无声 TIMEOUT。与 nav 规划在飞的冻结(AbstractCompanionTask)同一条保护。
        if (!lastQueryComplete || searchId != 0) {
            r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
            return TaskState.RUNNING;
        }
        //    Finish with whatever we gathered (the tool's contract: "fewer than count in
        //    range still succeeds") — the body does not wander off across the world looking
        //    for more; widening the search is the model's call.
        if (r.getMined() > 0) {
            progressNote = (named == null ? "no more " + r.label + " in range" : "nothing left to dig in " + r.label)
                    + leftovers();
            return TaskState.SUCCESS;
        }
        return noOreFailure();
    }

    // ---- goals ----

    /** The whole mining objective, compiled: a stance per ore + a walk-over member
     *  per nearby drop — one A* search heads for the closest of either. The route
     *  may chop targets en route; see {@link GoalCompiler#mineField}. */
    private GoalCompiler.Compiled oreFieldCompiled() {
        if (knownOres.isEmpty() && drops.isEmpty()) {
            // Degenerate frame (targets vanished between ticks): stand where we are.
            return GoalCompiler.standOn(player.blockPosition());
        }
        return GoalCompiler.mineField(
                new ArrayList<>(knownOres), this::digCost, new ArrayList<>(drops));
    }

    /** 到了之后挖它的价钱;这一刻没算过的按不许挖的价。 */
    private double digCost(BlockPos ore) {
        return digCosts.getOrDefault(ore, ActionCosts.COST_INF);
    }

    /**
     * 挑目标用的总价:走过去(目标函数的估价,与复合目标给 A* 的同一把尺)加上挖它的价钱。
     * 站在原地就够得着的不算路程。
     */
    private double targetCost(BlockPos ore, boolean inPlace) {
        double walk = inPlace ? 0 : NavGoal.pointBound(ore, player.blockPosition());
        return walk + digCost(ore);
    }


    /** 脚位到目标的最大垂直距离:站在目标正下方仰头,眼高 1.62 + 触及 4.5 ≈ 6.1,
     *  即目标底面在脚上 6 格内仍可命中——波段最多下探到此,再深就算站得住也打不到了。 */
    private static final int MAX_STANCE_DEPTH = 6;


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

    /** 该目标格是否真挖得成:挖穿成本无穷(挖不动/规格禁挖)、禁挖判定命中
     *  (冰/虫蚀/贴液体/悬空落沙邻格/世界边界)、或上下都被基岩封死的都不算。
     *  问的是挖不挖得动,不问许不许挖——那是执行开始时权限层的事。
     *  包内共享:goto 的 FIND 候选入册走同一道剪枝。 */
    public static boolean plausibleToBreak(CalculationContext ctx, BlockPos pos, BlockState state) {
        if (MovementHelper.getUnpricedMiningDurationTicks(ctx, pos.getX(), pos.getY(), pos.getZ(),
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
        // 视距下限取原版 server.properties 的 3:PlayerList 的视距是发给客户端的同步值,
        // 只有专用/集成服启动时会配置——GameTestServer 这类开发服上它是 0,不设下限的话
        // 收集箱塌成脚下一格,挖出的矿就躺在两格外"看不见"。
        int reach = level instanceof ServerLevel sl
                ? Math.max(3, sl.getServer().getPlayerList().getViewDistance()) * 16 : 128;
        AABB box = new AABB(player.blockPosition()).inflate(reach);
        List<BlockPos> out = new ArrayList<>();
        for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (!dropItems.contains(ie.getItem().getItem())) continue;
            BlockPos p = ie.blockPosition();
            if (nearKnownOre(p)) continue;
            out.add(p);
        }
        for (BlockPos p : anticipatedDrops.keySet()) {
            if (nearKnownOre(p)) continue;
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
     * The in-place mining pick: the cheapest known target the eyes can ACTUALLY hit from where the body
     * stands right now ({@link #reachable}: centre + exposed face points, within block reach, nothing
     * solid in the way) — mined on the spot, no pathing; equal prices go to the nearest. Column and
     * height don't matter; hittability does. The one hard exception is the support cell directly under
     * the feet — never dig out our own floor. Anything the eyes can't hit from here is left to the
     * navigator (walk to a stance, pillar up, etc.).
     *
     * <p>够得着的也可能不是该挖的:别处有按乐观估价就更便宜的({@link #targetCost}:走过去 + 挖它),
     * 就先让导航按总价去挑。导航挑完仍停在这儿({@link #settledAt}),说明别处的便宜只是估价上的,
     * 那就挖手边的。
     */
    private BlockPos reachableTarget() {
        if (!player.onGround()) return null;
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        BlockPos support = feet.below();
        BlockPos best = null;
        double bestCost = Double.MAX_VALUE;
        double bestD = Double.MAX_VALUE;
        for (BlockPos ore : knownOres) {
            if (ore.distSqr(feet) > IN_PLACE_FILTER_SQR) {
                break;   // sorted nearest-first — everything after this is farther still
            }
            if (ore.equals(support) || level.getBlockState(ore).isAir()) {
                continue;
            }
            double cost = targetCost(ore, true);
            double d = ore.distSqr(feet.above());
            if (cost > bestCost || (cost == bestCost && d >= bestD) || !reachable(ore)) {
                continue;
            }
            bestCost = cost;
            bestD = d;
            best = ore;
        }
        if (best == null || feet.equals(settledAt)) {
            return best;
        }
        for (BlockPos ore : knownOres) {
            if (!ore.equals(best) && targetCost(ore, false) < bestCost) {
                return null;
            }
        }
        // 地上等着捡的也按同一把尺:捡起来只花走过去的路程
        for (BlockPos drop : drops) {
            if (NavGoal.pointBound(drop, feet) < bestCost) {
                return null;
            }
        }
        return best;
    }

    /** Face points of a block (each face centre, from its collision shape), tried when the block's own
     *  centre is occluded — so a block whose centre is blocked but whose face is exposed still counts,
     *  the way a real click can catch it at an angle. */
    private static final Vec3[] BLOCK_FACE_POINTS = {
            new Vec3(0.5, 0, 0.5), new Vec3(0.5, 1, 0.5),
            new Vec3(0.5, 0.5, 0), new Vec3(0.5, 0.5, 1),
            new Vec3(0, 0.5, 0.5), new Vec3(1, 0.5, 0.5),
    };

    /**
     * Can the body reach {@code target} to break it from where it stands right now — an eye-line to the
     * block (its centre first, then each exposed face point) within block-interaction range
     * ({@link #REACH_SQR}) that nothing solid obstructs but the target itself. Reach is measured from the
     * EYE, so an upward target is reachable as high as a standing body's eyes allow — not merely what its
     * feet are next to — and a face-occluded block is still reachable via an exposed side.
     */
    private boolean reachable(BlockPos target) {
        Vec3 eyes = player.getEyePosition();
        if (reachableAt(eyes, target, Vec3.atCenterOf(target))) {
            return true;
        }
        VoxelShape shape = player.level().getBlockState(target).getShape(player.level(), target);
        if (shape.isEmpty()) {
            shape = Shapes.block();
        }
        for (Vec3 m : BLOCK_FACE_POINTS) {
            double xDiff = shape.min(Direction.Axis.X) * m.x + shape.max(Direction.Axis.X) * (1 - m.x);
            double yDiff = shape.min(Direction.Axis.Y) * m.y + shape.max(Direction.Axis.Y) * (1 - m.y);
            double zDiff = shape.min(Direction.Axis.Z) * m.z + shape.max(Direction.Axis.Z) * (1 - m.z);
            if (reachableAt(eyes, target,
                    new Vec3(target.getX() + xDiff, target.getY() + yDiff, target.getZ() + zDiff))) {
                return true;
            }
        }
        return false;
    }

    /** Is {@code point} within reach of {@code eyes}, and does an eye→point ray hit {@code target} first
     *  (nothing solid in the way)? */
    private boolean reachableAt(Vec3 eyes, BlockPos target, Vec3 point) {
        if (eyes.distanceToSqr(point) > REACH_SQR) {
            return false;
        }
        // OUTLINE (the selection shape), matching how a real click picks a block and what BlockDigger's
        // own reach ray uses — so this gate and the actual dig never disagree about whether a block is
        // hittable (a COLLIDER gate could green-light an ore the digger then can't draw a shot at).
        BlockHitResult hit = player.level().clip(new ClipContext(
                eyes, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    // ---- mining (progressive, tick-by-tick like a real player) ----

    /** Advance the shared dig one tick (it switches to the best tool itself); on the tick the TARGET
     *  breaks, drop it from the ore list. A {@link BlockDigger.DigResult#BROKE_OCCLUDER} (a leaf cleared
     *  to open the line of sight) is NOT the target, so the ore stays. The progress count is read from
     *  the inventory each tick, not here — one block can yield several items, and the drops take a
     *  moment to be picked up.
     *
     *  <p>Recovery: 连续的 {@code NO_SHOT}(够到测试过了,可挖掘始终成不了射线)记数,满
     *  {@link #MAX_NO_SHOT_TICKS} 就把<b>那一格</b>记进 {@link #unworkable} 继续往下走,
     *  而不是永远等一个不会来的射线。<b>记的是这一格,不是猜一格</b> —— 这是唯一一处
     *  按格记账的地方,因为它是唯一一件关于那一格的可复现事实。 */
    private TaskState mineProgress(BlockPos pos) {
        switch (digger.digStep(pos)) {
            case BROKE_TARGET -> {
                knownOres.remove(pos);
                brokenTargets++;
                noteProgress();
                // 地形变了 —— 挡住射线的那个檐口可能正好就是这一格。旧的"挖不动"结论全部作废。
                unworkable.clear();
                settledAt = null;
                if (WorkProfile.of(player).dropsLoot()) {
                    // 无掉落画像不登记逗留格:等一个永不出现的掉落物只会来回绕路
                    anticipatedDrops.put(pos.immutable(),
                            player.level().getGameTime() + DROP_LOITER_TICKS);
                }
                clearNoShot();
            }
            case REFUSED -> {
                // 挖掘落点的裁决不许(动手前放行之后世界变了,或挡在前面的遮挡物不许挖):
                // 权限层的拒绝就是这件活的结果,带着理由收场
                digger.cancel();
                fail("could not mine " + r.label + ": " + digger.refusal().reason() + "; " + soFar(),
                        FailureType.REFUSED);
                return TaskState.FAILED;
            }
            case NO_SHOT -> {
                if (pos.equals(noShotPos)) {
                    if (++noShotTicks >= MAX_NO_SHOT_TICKS) {
                        unworkable.add(pos.immutable());
                        knownOres.remove(pos);
                        if (named != null) {
                            remaining.add(pos.immutable());   // 地形一变(挖掉任何一格)还能再收
                        }
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
        return TaskState.RUNNING;
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
            for (Block b : kinds()) items.add(b.asItem());
            return items;
        }
        BlockPos origin = player.blockPosition();
        for (Block b : kinds()) {
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

    /**
     * 按需补货。block_ids 用法:名单快吃完 / 进入新 chunk / 慢心跳到点 / 上次没走完,才起一次搜索。
     * groups 用法:名单空了,或快吃完且过了间隔,就从点名格里收。
     */
    private void maybeQuery() {
        --queryCooldown;
        --heartbeatTimer;
        if (named != null) {
            if (knownOres.isEmpty() || (knownOres.size() < QUERY_LOW_WATER && queryCooldown <= 0)) {
                queryCooldown = QUERY_MIN_GAP_TICKS;
                admitNamed();
            }
            return;
        }
        if (queryCooldown > 0 || searchId != 0) {
            return;
        }
        if (knownOres.size() < QUERY_LOW_WATER
                || ChunkPos.asLong(player.blockPosition()) != lastQueryChunk
                || heartbeatTimer <= 0
                || !lastQueryComplete) {
            runQuery();
        }
    }

    /** 起一次搜索,结果回来后由 {@link #absorbSearch} 并进名单。 */
    private void runQuery() {
        if (!(player.level() instanceof ServerLevel sl)) {
            return;
        }
        lastQueryChunk = ChunkPos.asLong(player.blockPosition());
        heartbeatTimer = QUERY_HEARTBEAT_TICKS;
        queryCooldown = QUERY_MIN_GAP_TICKS;
        searchId = BlockSearch.start(player.getUUID(), sl, player.blockPosition(), QUERY_MAX_CHUNK_RADIUS * 16,
                MAX_ORES, r.targets, res -> {
                    searchId = 0;
                    arrived = res;
                });
    }

    /** 搜索回来了就把最近的目标并进名单。 */
    private void absorbSearch() {
        BlockSearch.ScanResult res = arrived;
        if (res == null) {
            return;
        }
        arrived = null;
        // 间隔从结果到手时起算:冷区域一次搜索可能跨过整个间隔,从起跑时算的话名单一空就接着起下一次,
        // 终局判定永远等不到"没有在飞的搜索"
        queryCooldown = QUERY_MIN_GAP_TICKS;
        heartbeatTimer = QUERY_HEARTBEAT_TICKS;
        lastQueryComplete = !res.deadlineHit();
        if (lastQueryComplete) {
            coldMapFails = 0;   // 图齐了，之前那几次无路不再算数
        }
        com.dwinovo.numen.core.Constants.LOG.debug(
                "[numen-task] mine query feet={} raw={} complete={} known(before merge)={}",
                player.blockPosition().toShortString(), res.matches().size(), lastQueryComplete,
                knownOres.size());
        mergeHits(res.matches());
    }

    /**
     * 按由近及远把还做得成的命中收进名单,收满 {@link #MAX_ORES} 个就停:铺天盖地的目标(石头)一次能回来
     * 上千格,逐格验完再截断是白花主线程——远处的下次查询还在。
     */
    private void mergeHits(List<BlockScanner.Hit> hits) {
        // One-off Set view for dedup: knownOres stays a distance-ordered list (prune sorts it),
        // but membership checks against it must not be linear scans — a big batch times a
        // linear contains is O(N^2) on the server thread.
        Set<BlockPos> seen = new HashSet<>(knownOres);
        Level level = player.level();
        CalculationContext ctx = ContextFactory.forExecution(player, terrain.spec());
        for (BlockScanner.Hit hit : hits) {
            if (knownOres.size() >= MAX_ORES) {
                break;
            }
            BlockPos p = hit.pos().immutable();
            if (!seen.add(p) || !stillCandidate(level, ctx, p)) {
                continue;
            }
            knownOres.add(p);
        }
        prune();
    }

    /**
     * groups 用法的补货:从还没收进名单的点名格里由近及远收,收满 {@link #MAX_ORES} 个为止。挖不动的格留在
     * 那儿等地形变;其余收不进的({@link #stillCandidate} 记了账)就此划掉。
     */
    private void admitNamed() {
        if (remaining.isEmpty() || knownOres.size() >= MAX_ORES) {
            return;
        }
        BlockPos feet = player.blockPosition();
        List<BlockPos> nearestFirst = new ArrayList<>(remaining);
        nearestFirst.sort(Comparator.comparingDouble(feet::distSqr));
        Level level = player.level();
        CalculationContext ctx = ContextFactory.forExecution(player, terrain.spec());
        for (BlockPos p : nearestFirst) {
            if (knownOres.size() >= MAX_ORES) {
                break;
            }
            if (unworkable.contains(p)) {
                continue;
            }
            remaining.remove(p);
            if (stillCandidate(level, ctx, p)) {
                knownOres.add(p);
            }
        }
        prune();
    }

    /**
     * 这一格还算不算候选:还是要的方块(groups 用法:还是扫描时记下的那种)、没被记成挖不动、挖得成、手里的
     * 工具收得到掉落。问的是"挖不挖得成",按这件活自己的规格算;许不许挖不在这里剪。收不进的记账,回执交代。
     */
    private boolean stillCandidate(Level level, CalculationContext ctx, BlockPos p) {
        var state = level.getBlockState(p);
        boolean wanted = named != null ? named.get(p) == state.getBlock() : r.targets.contains(state.getBlock());
        if (state.isAir() || !wanted) {
            if (named != null) {
                gone.add(p.immutable());
            }
            return false;
        }
        if (unworkable.contains(p)) {
            return false;
        }
        if (!plausibleToBreak(ctx, p, state)) {
            ruledOut.add(p.immutable());
            return false;
        }
        // Harvestability gate. Tool-skipped cells are remembered so the terminal failure
        // can say "you need a better tool" instead of the misleading "nothing found" (the
        // tool situation can also CHANGE mid-task: the only good pick breaking makes this
        // fire on re-prune).
        if (!WorkProfile.of(player).instaBreak()
                && !BlockHelper.canHarvest(player.getInventory(), state)) {
            unharvestable.add(p.immutable());
            return false;
        }
        return true;
    }

    private void prune() {
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        CalculationContext ctx = ContextFactory.forExecution(player, terrain.spec());
        knownOres.removeIf(p -> !stillCandidate(level, ctx, p));
        knownOres.sort(Comparator.comparingDouble(feet::distSqr));
        if (knownOres.size() > MAX_ORES) {
            List<BlockPos> farther = knownOres.subList(MAX_ORES, knownOres.size());
            if (named != null) {
                remaining.addAll(farther);   // 点名的格只是暂时排不上,不是没了
            }
            farther.clear();
        }
        // 挖每一块的价钱:同一个成本模型,需要主人同意的乘倍率,不许的是 INF——挑目标按价,不按剪
        digCosts.clear();
        for (BlockPos p : knownOres) {
            digCosts.put(p, MovementHelper.getMiningDurationTicks(ctx, p.getX(), p.getY(), p.getZ(),
                    level.getBlockState(p), true));
        }
    }

    /** Nearest known ore to the feet, or null — for the "near ore exists but heading far" diagnostics. */
    private BlockPos nearestOre() {
        BlockPos feet = player.blockPosition();
        return knownOres.stream().min(Comparator.comparingDouble(feet::distSqr)).orElse(null);
    }

    /** Log-friendly nearest-ore descriptor (ASCII so it survives any log encoding):
     *  "316,64,391 minecraft:oak_log dy=+0 dist=1.0" or "none". dy = ore.y - feet.y (spot "it's 4 up,
     *  needs pillaring" vs "same level"); the block id spots a mis-handled type (vine/leaves/etc.). */
    private String nearestOreInfo() {
        BlockPos n = nearestOre();
        if (n == null) {
            return "none";
        }
        BlockPos feet = player.blockPosition();
        String block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(player.level().getBlockState(n).getBlock()).toString();
        int dy = n.getY() - feet.getY();
        return n.toShortString() + " " + block + " dy=" + (dy >= 0 ? "+" + dy : dy)
                + " dist=" + String.format("%.1f", Math.sqrt(feet.distSqr(n)));
    }



    /** 挖掉了一格,或者明显挪了窝 —— 两者都算进展,卡死计时重新起算。 */
    private void noteProgress() {
        lastProgressTick = player.level().getGameTime();
        lastProgressPos = player.blockPosition();
    }

    /**
     * 真卡住了吗。<b>既没挖掉一格、也没挪出 {@link #STALL_MOVE} 格</b>,持续
     * {@link #STALL_TICKS} 刻才算 —— 走远路去挖矿一刻都不算,她在动。
     *
     * @return 该收工就给终态,否则 null
     */
    private TaskState stalledOut() {
        long now = player.level().getGameTime();
        if (lastProgressPos == null
                || player.blockPosition().distSqr(lastProgressPos) > STALL_MOVE * STALL_MOVE) {
            noteProgress();
            return null;
        }
        // 规划器在飞的刻不算卡住:搜索按真实时间给预算,而这把尺子数的是游戏刻。tick 远快于
        // 真实时间时(/tick rate 200、不限速的测试服),往下挖 170 格的搜索还没回来,400 刻已经
        // 烧完——她被判"够不着",其实只是在等路。与任务 deadline 的同一条保护(AbstractCompanionTask)。
        if (nav != null && nav.planningInFlight()) {
            lastProgressTick++;
            return null;
        }
        if (now - lastProgressTick < STALL_TICKS) {
            return null;
        }
        com.dwinovo.numen.core.Constants.LOG.info(
                "[numen-task] mine 卡住 {} 刻:没挖掉任何一格、也没挪窝 | feet={} 名单 {} 个",
                now - lastProgressTick, player.blockPosition().toShortString(), knownOres.size());
        String where = player.blockPosition().toShortString();
        if (r.getMined() > 0) {
            progressNote = "then got stuck at " + where + " — could not reach the remaining "
                    + knownOres.size() + " " + noun() + leftovers();
            return TaskState.SUCCESS;
        }
        fail("found " + knownOres.size() + " " + noun() + " but could not reach any of them from "
                + where + " — no path out, and nothing minable in place; gathered 0."
                + " Move me somewhere else, or clear a way first.", FailureType.NO_PATH);
        return TaskState.FAILED;
    }

    /** 挖不成的候选在回执里的说法。 */
    private static final String RULED_OUT = "can't be broken here (unbreakable, excluded by the spec, or fluid or"
            + " loose falling blocks beside them)";

    /** 回执里怎么称呼要挖的东西:方块名,或"g3 的格子"。 */
    private String noun() {
        return named == null ? r.label : "cells of " + r.label;
    }

    /** 到目前为止的收获,一句话。 */
    private String soFar() {
        return r.count == MineBlockTaskRecord.UNTIL_GONE
                ? "dug " + r.getMined() + " of " + r.cells() + " cells"
                : "gathered " + r.getMined();
    }

    /** 找到了或点名了、却没挖成的各因为什么;都没有是空串。 */
    private String leftovers() {
        List<String> parts = new ArrayList<>(4);
        if (!gone.isEmpty()) {
            parts.add(gone.size() + " were gone or had changed before I got to them");
        }
        if (!unharvestable.isEmpty()) {
            parts.add(unharvestable.size() + " can't be harvested with the current tools");
        }
        if (!unworkable.isEmpty()) {
            parts.add(unworkable.size() + " gave no clear shot from any stance");
        }
        if (!ruledOut.isEmpty()) {
            parts.add(ruledOut.size() + " " + RULED_OUT);
        }
        return parts.isEmpty() ? "" : "; not mined: " + String.join(", ", parts);
    }

    /** Terminal "nothing gathered, no ore left to go for" failure, distinguishing a
     *  genuinely empty field ({@code MINED_OUT} — widening the search or stopping is the
     *  LLM's call) from a field that WAS found but every target turned out unworkable
     *  ({@code NO_PATH} — 没有任何站位能对它拉出射线), with the counts.
     *  「走不到」那一档不在这里 —— 它由 {@link #stalledOut} 收工。 */
    private TaskState noOreFailure() {
        if (!unharvestable.isEmpty()) {
            // Targets exist but the carried tools can't make them drop — the actionable
            // problem is the tool, not the deposit. Names the escape hatches explicitly.
            fail("found " + unharvestable.size() + " " + noun() + " but none can be harvested with"
                    + " the current tools (mining would destroy them without any drop); gathered "
                    + r.getMined() + ". Equip a better tool (equip_item) and retry; to just destroy"
                    + " blocks regardless of drops, goto beside them and use interact_at with button left."
                    + leftovers(), FailureType.WRONG_TOOL);
        } else if (!unworkable.isEmpty()) {
            fail("found " + unworkable.size() + " " + noun() + " nearby but no clear shot at any"
                    + " of them from any stance I could take; gathered 0" + leftovers(),
                    FailureType.NO_PATH);
        } else if (!ruledOut.isEmpty()) {
            fail("found " + ruledOut.size() + " " + noun() + " but none " + RULED_OUT + "; gathered 0"
                    + leftovers(), FailureType.MINED_OUT);
        } else if (named != null) {
            fail("all " + named.size() + " cells of " + r.label + " were gone or had changed since the scan;"
                    + " gathered 0. scan_blocks again to see what is there now.", FailureType.TARGET_LOST);
        } else {
            fail("no reachable " + r.label + " found in the loaded area around me",
                    FailureType.MINED_OUT);
        }
        return TaskState.FAILED;
    }

    @Override
    protected void cleanup() {
        // super.cleanup() = stopNav() (nav.stop clears the overlay when a nav exists) + an explicit
        // so a task that finished while shaft-mining (nav == null) still
        // clears its lingering goal boxes. Then release the dig + the index registration.
        super.cleanup();
        digger.cancel();
        if (searchId != 0) {
            BlockSearch.cancel(searchId);
            searchId = 0;
        }
        if (holding && player.level() instanceof ServerLevel sl) {
            BlockSearch.release(sl, r.targets);
        }
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("target", r.label);
        if (r.count == MineBlockTaskRecord.UNTIL_GONE) {
            data.put("cells", r.cells());
            data.put("dug", r.getMined());
        } else {
            data.put("requested", r.count);
            data.put("gathered", r.getMined());
        }
        return data;
    }

    /** {@code gathered 3/8 oak_log} 或 {@code dug 3/12 cells of g3,g4}。 */
    private String tally() {
        return r.count == MineBlockTaskRecord.UNTIL_GONE
                ? "dug " + r.getMined() + "/" + r.cells() + " cells of " + r.label
                : "gathered " + r.getMined() + "/" + r.count + " " + r.label;
    }

    @Override
    protected String successMessage() {
        return tally() + " (" + progressNote + ")";
    }

    @Override
    protected String timeoutMessage() {
        return "timed out after I " + tally();
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted after I " + tally();
    }
}
