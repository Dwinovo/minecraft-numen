package com.dwinovo.numen.core.task.mine;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.FailureType;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.bridge.ContextFactory;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.moves.ActionCosts;
import com.dwinovo.numen.core.pathing.moves.BlockReach;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.act.BlockDigger;
import com.dwinovo.numen.core.pathing.execute.PathExecutor;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.pathing.util.NavProfiler;
import com.dwinovo.numen.core.scan.BlockScanner;
import com.dwinovo.numen.core.scan.BlockSearch;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.permission.Action;
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
 * <h2>两种用法,一个循环</h2>
 * {@code block_ids} 用法的候选来自搜索({@link BlockSearch});{@code groups} 用法的候选只是点名的团里的格子
 * (派发时从团簿取好、记在任务记录里),每格还得是扫描时记下的那种方块,不往外扩。除了候选从哪来,走、挖、
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
 *   <li><b>in place</b> — a target whose {@link NavGoal#mineStance} the body is standing in
 *       (within block reach, feet not above it) is broken on the spot, cheapest first,
 *       auto-switching to the best tool — no pathing. The digger clears what stands in the
 *       line of sight first, if it is safe to break ({@link #plausibleToBreak}).</li>
 *   <li><b>composite goal</b> — otherwise head for the whole ore field at once:
 *       one A* search over {@link NavGoal#composite} of the same stances, so it walks to
 *       the CLOSEST reachable ore (not greedy-nearest, which is often the walled-in one).
 *       Arrival and the in-place pick are one criterion, so wherever the search ends, the
 *       dig side agrees.</li>
 *   <li><b>够不着是一批的属性,不是某一格的罪</b> — 复合目标在完整的图上搜不出路,意思是
 *       <b>这一刻这一批都到不了</b>,不是"最近那颗有问题"。所以不记账到任何一格,也不原样
 *       再搜:同一个局面(起点、目标、掉落物)第二次撞上无路,如实收工、报告剩下的走不到
 *       ({@link #unreachable})。站着既没挖掉一格、也没挪窝超过 {@link #STALL_TICKS} 刻,同样收工。</li>
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
    private static final double MINE_SPEED = 1.0;
    /** 同一格连续这么多刻拉不出射线,就记进 {@link #unworkable} —— 站位说够得着,
     *  可射线始终成不了(挡在中间的挖不得、瞄准量化)。没有这条,挖掘会永远等一个
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

    private final List<BlockPos> knownOres = new ArrayList<>();
    /**
     * 当前地形下挖不动的格子 —— <b>只有 {@code NO_SHOT} 进得来</b>:站位说够得着,却连续
     * 二十刻拉不出射线(挡在中间的挖不得、瞄准量化)。这是关于<b>这一格</b>的、可复现的事实。
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
     * 按总价挑目标的导航在这一格落定了(搜索挑中的就是脚下):手边够得着、价钱不超过 {@link #settledPrice}
     * 的就是该挖的,不再因为别处估价更低而让路。挖掉一格或挪了窝就作废。
     */
    private BlockPos settledAt;
    /**
     * 落定那一刻停在这一格的到达价——搜索按总价挑中这儿时认下的价钱。停下来是为了捡脚边的掉落物(到达价 0),
     * 就不能拿它当挖一格贵东西(主人的原木)的理由。
     */
    private double settledPrice;
    /** Items the target blocks drop (simulated via the server loot tables). The
     *  count is over THESE in the inventory, not blocks broken — redstone_ore yields ~4 redstone. */
    private Set<Item> dropItems = Set.of();
    /** Matching items already in the inventory when the task began — the count is the DELTA above this
     *  (companion semantics: "gather N more", not an absolute "have N in the inventory"). */
    private int baseline;
    /** Nearby dropped items to collect (walked over for native pickup), refreshed per tick. */
    private List<BlockPos> drops = List.of();
    /**
     * 她自己敲出来、还没进包的那几件掉落物(实体 id)。
     *
     * <p><b>为什么认 id 而不是数附近的掉落物</b>:主人扔在旁边的、开工前就躺在那儿的、
     * 别人挖的,物品类型全都对得上。把它们算进来会让她少挖。认 id 才框得住"我这一单造出来的"。
     *
     * <p><b>为什么不会重复计</b>:原版拾取是同一刻里先进背包、再 {@code discard()} 实体,
     * 所以那一刻它从这本账消失、在背包增量里出现,两头不重叠;背包满了只捡走一半时实体还活着、
     * {@code getCount()} 变小,账也跟着对。
     *
     * <p>实体没了(烧了、被主人捡了、自然消失)就自动出账,她自己再补一块——不需要超时或重试。
     * 走不到的也出账,见 {@link #unreachableDrops}。
     */
    private final it.unimi.dsi.fastutil.ints.IntOpenHashSet ourDrops =
            new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

    /**
     * 走不到的掉落物(实体 id)。够数之后导航只奔地上的掉落物;这一批在完整的图上搜不出路、或她守着它们
     * 卡住了,就说明它们一件也进不了包——砍树冠时原木常弹到树叶顶上,站在地上够不着,她也没有垫脚的方块。
     *
     * <p>它们从 {@link #ourDrops} 出账、不再当目标,够没够数只按还拿得到的算,她接着挖别的补上。不出账的话,
     * "到手 + 在路上"永远够数:她不再挖,又捡不到,只能缺着数收工。记的是这一批,不挑哪一件顶罪——
     * 目标里只有它们,搜不出路说的就是它们全体。
     */
    private final it.unimi.dsi.fastutil.ints.IntOpenHashSet unreachableDrops =
            new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

    /** 已经记过账的格:每一格只在挖开它之后认一次。 */
    private final Set<BlockPos> claimedCells = new HashSet<>();

    /** 这一刻"够了,别再敲新的了"——到手加上还没进包的已经够数。见 {@link #inFlight()}。 */
    private boolean quotaMet;
    /** 无掉落画像(创造)下的进度计数:破坏的目标方块数——背包增量在
     *  这种画像下恒为 0,数拾取物会让任务铲平半径 32 chunk 后报败。 */
    private int brokenTargets;

    /** groups 用法里还没收进名单的点名格。挖不动的格也回到这里,地形一变还能再收。 */
    private final Set<BlockPos> remaining = new HashSet<>();
    /** groups 用法里轮到时已经不是扫描时那种方块、而旅程账上也没有她挖过的格(别人挖掉、换掉的)。 */
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
    /** 上一次真有进展(挖掉一格)或明显挪窝时的 {@link #workTicks()} 与位置 —— 卡死判定的量尺。 */
    private long lastProgressWork;
    private BlockPos lastProgressPos;

    /** 目标图还没回来时连续无路的次数(见 {@link NoPathVerdict}),只用来让日志只打第一次。 */
    private int coldMapFails;
    /** 完整的图上搜不出路时的局面:从哪儿搜、要挖的格、地上的掉落物。同一个局面再撞上一次就收工。 */
    private record NoPathScene(BlockPos feet, Set<BlockPos> ores, Set<BlockPos> drops) {}
    /** 上一次搜不出路时的局面;还没有为 null。 */
    private NoPathScene lastNoPath;
    /**
     * 目标图有了:至少一次查询已经回来(或点名用法不用查)。搜索按工作量收工,回来的就是这个问法在这个世界上
     * 的完整答案——被节数上限截断也是确定的截断,重查不会更全。还没回来时,终局判定("附近没有目标"、
     * "都够不着")得等它。
     */
    private boolean mapped;
    /** 最近一次回来的查询被节数上限截断时的那句话({@link BlockSearch.ScanResult#sectionCapNote});没截断为 null。 */
    private String capNote;
    /** 在飞的搜索句柄;0 表示没有。 */
    private int searchId;
    /** 回来了还没并进名单的搜索结果。 */
    private BlockSearch.ScanResult arrived;
    /** 开工时在哪个世界向 {@link BlockSearch} 持有了目标登记;收尾在同一个世界放下(途中换了维度也不放错)。没持有为 null。 */
    private ServerLevel heldIn;

    // Progressive dig (blocks break tick-by-tick at legitimate player speed, not
    // instabreak) — shared with the path executor so all breaking reads the same.
    private final BlockDigger digger;
    /** 正在挖的目标;挖掘器这一刻可能在挖挡在它前面的那一格,锁的是目标,不是挖掘器手里那一格。没有为 null。 */
    private BlockPos digTarget;

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
                        + " first; to just destroy a block regardless of drops, goto beside it and run use block left"
                        + " on it.",
                        FailureType.WRONG_TOOL);
            }
            return null;
        });
    }

    /** 这件活是 groups 用法:只挖点名的格子。 */
    private boolean byGroups() {
        return !r.named.isEmpty();
    }

    @Override
    protected void onStart() {
        // Count toward `count` by ITEMS gathered, not blocks broken: resolve what these
        // blocks drop, and snapshot how many we already hold so the tally is the delta above it.
        dropItems = computeDropItems();
        baseline = inventoryMatch();
        if (byGroups()) {
            // 候选就是点名的格子,不用搜
            remaining.addAll(r.named.keySet());
            mapped = true;
        } else {
            // 持有目标的登记,让共享索引在任务期间保持新鲜,并立即起首次搜索;冷区域的读地形由
            // 每刻的读节配额分摊,首批结果回来前 onTick 的终局判定会等着({@link #mapped})。
            if (player.level() instanceof ServerLevel sl) {
                BlockSearch.hold(sl, r.targets);
                heldIn = sl;
            }
            runQuery();
        }
        lastProgressWork = workTicks();
        lastProgressPos = player.blockPosition();
        // 与 goto 的 start 日志对称:一任务一条,让日志里能看到任务确实启动了
        com.dwinovo.numen.core.Constants.LOG.info(
                "[numen-task] mine start targets={} count={} cells={} feet={}",
                r.label, r.count, byGroups() ? r.named.size() : "-", player.blockPosition().toShortString());
    }

    @Override
    protected TaskState onTick() {
        // 进度口径随画像:有掉落 = 数拾取到的物品(一块矿可能出多个);
        // 无掉落(创造) = 数破坏的目标方块——否则永远数不满。挖完点名的团为止的,数挖掉的格。
        boolean untilGone = r.count == MineBlockTaskRecord.UNTIL_GONE;
        // 导航顺路挖开的格也是她挖的,先入账再算够没够——漏了它们,账就会一时多一时少
        sweepNavBreaks();
        int gathered = WorkProfile.of(player).dropsLoot() && !untilGone
                ? Math.max(0, inventoryMatch() - baseline)
                : brokenTargets;
        r.setMined(gathered);
        // 完工只认到手的:回执里那句 "gathered 12/12" 得是真的
        if (!untilGone && gathered >= r.count) {
            progressNote = "gathered all requested";
            return TaskState.SUCCESS;
        }
        // 还敲不敲下一块,是另一个问题:背包是滞后指标(掉落物有 10 tick 拾取延迟),
        // 只看到手会在那段空窗里多敲两三块。算上已经敲掉、还躺在地上的,够了就只去捡。
        quotaMet = !untilGone && gathered + inFlight() >= r.count;


        Level level = player.level();

        // Maintain the ore list every tick — INCLUDING while a dig below is latched:
        // prune (cheap — knownOres is capped at 64) revalidates against the live world;
        // a search is started on demand (list low / new chunk / slow heartbeat) instead of
        // on a fixed rescan cadence — the block-change hook keeps the shared index current
        // in between.
        long tUpkeep = NavProfiler.begin();
        absorbSearch();
        prune();
        maybeQuery();
        NavProfiler.end("mine.upkeep", tUpkeep);

        // 0) Continue an in-progress dig, locked onto its target (no re-selection)
        //    until it breaks or the body no longer stands where it can work it.
        if (digTarget != null) {
            if (quotaMet) {
                // 够数了,手上这块也不敲完:敲完就是多一块
                digger.cancel();
                digTarget = null;
            } else if (level.getBlockState(digTarget).isAir() || !canWork(digTarget)) {
                digger.cancel();
                digTarget = null;
            } else {
                if (nav != null) {
                    nav.pause();   // stand still for the dig; goal/path/in-flight search stay warm
                }
                return mineProgress(digTarget);
            }
        }

        long tDrops = NavProfiler.begin();
        drops = droppedItems();
        NavProfiler.end("mine.drops", tDrops);

        // 1) Mine any target whose stance we already stand in (no pathing).
        BlockPos reachable = quotaMet ? null : reachableTarget();
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
            Permit permit = permit(Action.breakBlock(reachable, level.getBlockState(reachable)));
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
        if ((!quotaMet && !knownOres.isEmpty()) || !drops.isEmpty()) {
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
                    settledAt = PathExecutor.playerFeet(player);
                    settledPrice = oreFieldCompiled().goal().arrivalCost(settledAt);
                    // [ANCHOR arrived-dud] 到了,却没有可挖的。站位与原地就挖是同一个判据,所以这
                    // <b>不构成关于任何一颗矿的证据</b>:她到的是复合目标里的<b>掉落物</b>成员(刚捡完
                    // 东西,附近本来就没矿),或者这一刻人在空中(reachableTarget 第一行就要求 onGround)。
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
                    // [ANCHOR nav-cold-map] 目标图还没回来，这个“没路”不算证据。
                    //
                    // 首次查询回来之前,她只认得地上的掉落物;朝它们搜不出路,说明不了名单里将会有的
                    // 那些目标。拿这种无路去收工，是把“我还不知道”当成了“不可能”。
                    //
                    // 跟上面 ARRIVED-dud 是同一条纪律：收工只该给真正失败的路。
                    if (NoPathVerdict.of(mapped) == NoPathVerdict.Verdict.REQUERY) {
                        if (++coldMapFails == 1) {
                            com.dwinovo.numen.core.Constants.LOG.info(
                                    "[numen-task] mine nav failed ({}) 但目标图还没回来 —— 不收工,等它 | nearestOre={}",
                                    nav.failType(), nearestOreInfo());
                        }
                        stopNav();
                        return TaskState.RUNNING;
                    }
                    // [ANCHOR nav-failed] 完整图上真的没路。
                    //
                    // <b>这句话的主语是"这一批",不是"最近那颗"。</b>复合目标撒在全部目标上,
                    // 搜不出路的意思是一个都到不了 —— 拿"离脚最近的"顶罪只是猜,所以什么都不记。
                    // 也不原样再搜:同一个局面(同一个起点、同一批目标、同一批掉落物)再搜一遍还是这个
                    // 结论,她只会站着不动、每次烧满搜索预算、永远不收工。所以同一个局面第二次撞上无路
                    // 就如实收工,剩下的交给模型;局面变了(掉落物落了地、刚挖掉的格不再当掉落物等着、
                    // 名单变了)才值得再搜。
                    com.dwinovo.numen.core.Constants.LOG.info(
                            "[numen-task] mine nav failed ({}): {} | 复合目标 {} 个,nearestOre={}",
                            nav.failType(), nav.failReason(), knownOres.size(), nearestOreInfo());
                    NoPathScene scene = new NoPathScene(PathExecutor.playerFeet(player), Set.copyOf(knownOres),
                            Set.copyOf(drops));
                    if (scene.equals(lastNoPath)) {
                        return unreachable(nav.failReason()
                                + (knownOres.isEmpty() ? "" : "; the nearest is " + nearestOreInfo()));
                    }
                    lastNoPath = scene;
                    stopNav();
                    return TaskState.RUNNING;
                }
            }
        }

        // 3) No ore known and nothing dropped nearby. A search still in flight, or none back
        //    yet, means "don't know yet", not "nothing there" — wait for it before any verdict.
        //    这样等着的刻是在等搜索,不算干活(awaitSearch)。
        if (!mapped || searchId != 0) {
            awaitSearch();
            return TaskState.RUNNING;
        }
        //    Finish with whatever we gathered (the tool's contract: "fewer than count in
        //    range still succeeds") — the body does not wander off across the world looking
        //    for more; widening the search is the model's call.
        if (r.getMined() > 0) {
            progressNote = (byGroups() ? "nothing left to dig in " + r.label : "no more " + r.label + " in range")
                    + leftovers(null);
            return TaskState.SUCCESS;
        }
        return noOreFailure();
    }

    // ---- goals ----

    /** The whole mining objective, compiled: a stance per ore + a walk-over member
     *  per nearby drop — one A* search heads for the closest of either. The route
     *  may chop targets en route; see {@link GoalCompiler#mineField}. */
    private GoalCompiler.Compiled oreFieldCompiled() {
        if ((quotaMet || knownOres.isEmpty()) && drops.isEmpty()) {
            // Degenerate frame (targets vanished between ticks): stand where we are.
            return GoalCompiler.standOn(player.blockPosition());
        }
        return GoalCompiler.mineField(
                quotaMet ? List.of() : new ArrayList<>(knownOres),
                this::digCost, new ArrayList<>(drops), BlockReach.of(player));
    }

    /** 到了之后挖它的价钱;这一刻没算过的按不许挖的价。 */
    private double digCost(BlockPos ore) {
        return digCosts.getOrDefault(ore, ActionCosts.COST_INF);
    }

    /**
     * 挑目标用的总价:走到它的站位(站位的估价,与复合目标给 A* 的同一把尺;已经站在站位里是 0)
     * 加上挖它的价钱。
     */
    private double targetCost(BlockPos ore, BlockPos feet, BlockReach reach) {
        return NavGoal.mineStance(ore, reach).heuristic(feet) + digCost(ore);
    }

    /** 身体此刻站着的这一格是不是 {@code ore} 的站位——原地就挖与导航到位是这同一个判据。 */
    private boolean canWork(BlockPos ore) {
        return NavGoal.mineStance(ore, BlockReach.of(player)).isAt(PathExecutor.playerFeet(player));
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
        List<BlockPos> out = new ArrayList<>();
        for (ItemEntity ie : nearbyDrops()) {
            BlockPos p = ie.blockPosition();
            // 贴着某颗已知矿的掉落物不单独设目标——挖那颗矿自然会带身体过去。够数之后
            // 不再去挖任何矿,这条捷径就不成立了,那时每一件都得自己走过去捡。
            if (!quotaMet && nearKnownOre(p)) continue;
            out.add(p);
        }
        return out;
    }

    /** 附近这件活要的掉落物:目标会掉的物品,不在 {@link #unreachableDrops} 里。 */
    private List<ItemEntity> nearbyDrops() {
        Level level = player.level();
        // 搜集范围 = 服务端视距(身体周围的加载邻域),与目标扫描的事实边界同源。
        // 视距下限取原版 server.properties 的 3:PlayerList 的视距是发给客户端的同步值,
        // 只有专用/集成服启动时会配置——GameTestServer 这类开发服上它是 0,不设下限的话
        // 收集箱塌成脚下一格,挖出的矿就躺在两格外"看不见"。
        int reach = level instanceof ServerLevel sl
                ? Math.max(3, sl.getServer().getPlayerList().getViewDistance()) * 16 : 128;
        AABB box = new AABB(player.blockPosition()).inflate(reach);
        return level.getEntitiesOfClass(ItemEntity.class, box,
                ie -> dropItems.contains(ie.getItem().getItem()) && !unreachableDrops.contains(ie.getId()));
    }

    /** 距任一已知矿位 3 格内(distSqr ≤ 9)——挖那颗矿自然会带身体过去。 */
    private boolean nearKnownOre(BlockPos p) {
        return knownOres.stream().anyMatch(ore -> ore.distSqr(p) <= 9);
    }

    /**
     * The in-place mining pick: the cheapest known target whose {@link NavGoal#mineStance} the body is
     * standing in right now — mined on the spot, no pathing; equal prices go to the nearest. It is the
     * very criterion the navigator arrives by, so an arrival always has something to dig here, and
     * whatever blocks the line of sight is the digger's to clear. Anything not workable from here is
     * left to the navigator.
     *
     * <p>够得着的也可能不是该挖的:别处有按乐观估价就更便宜的({@link #targetCost}:走过去 + 挖它),
     * 就先让导航按总价去挑。导航挑完仍停在这儿({@link #settledAt}),而且认下的价钱够挖它
     * ({@link #settledPrice}),说明别处的便宜只是估价上的,那就挖手边的。
     */
    private BlockPos reachableTarget() {
        if (!player.onGround()) return null;
        Level level = player.level();
        BlockPos feet = PathExecutor.playerFeet(player);
        BlockReach reach = BlockReach.of(player);
        BlockPos best = null;
        double bestCost = Double.MAX_VALUE;
        double bestD = Double.MAX_VALUE;
        for (BlockPos ore : knownOres) {
            if (level.getBlockState(ore).isAir() || !NavGoal.mineStance(ore, reach).isAt(feet)) {
                continue;
            }
            double cost = digCost(ore);
            double d = ore.distSqr(feet.above());
            if (cost > bestCost || (cost == bestCost && d >= bestD)) {
                continue;
            }
            bestCost = cost;
            bestD = d;
            best = ore;
        }
        if (best == null || (feet.equals(settledAt) && bestCost <= settledPrice)) {
            return best;
        }
        for (BlockPos ore : knownOres) {
            if (!ore.equals(best) && targetCost(ore, feet, reach) < bestCost) {
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

    // ---- mining (progressive, tick-by-tick like a real player) ----

    /** Advance the shared dig one tick (it switches to the best tool itself); on the tick the TARGET
     *  breaks, drop it from the ore list. A {@link BlockDigger.DigResult#BROKE_OCCLUDER} (a leaf cleared
     *  to open the line of sight) is NOT the target, so the ore stays. The digger clears only occluders
     *  that pass the same cut as the targets themselves ({@link #plausibleToBreak}). The progress count
     *  is read from the inventory each tick, not here — one block can yield several items, and the drops
     *  take a moment to be picked up.
     *
     *  <p>Recovery: 连续的 {@code NO_SHOT}(站位说够得着,可挖掘始终成不了射线)记数,满
     *  {@link #MAX_NO_SHOT_TICKS} 就把<b>那一格</b>记进 {@link #unworkable} 继续往下走,
     *  而不是永远等一个不会来的射线。<b>记的是这一格,不是猜一格</b> —— 这是唯一一处
     *  按格记账的地方,因为它是唯一一件关于那一格的可复现事实。 */
    private TaskState mineProgress(BlockPos pos) {
        digTarget = pos.immutable();
        switch (digger.digStep(pos, occluder -> plausibleToBreak(
                ContextFactory.forExecution(player, terrain.spec()), occluder,
                player.level().getBlockState(occluder)))) {
            case BROKE_TARGET -> {
                digTarget = null;
                knownOres.remove(pos);
                brokenTargets++;
                noteProgress();
                // 地形变了 —— 挡住射线的那个檐口可能正好就是这一格。旧的"挖不动"结论全部作废。
                unworkable.clear();
                settledAt = null;
                claimDrops(pos);
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
                        if (byGroups()) {
                            remaining.add(pos.immutable());   // 地形一变(挖掉任何一格)还能再收
                        }
                        digger.cancel();   // release the in-progress-dig latch on this ore
                        digTarget = null;
                        clearNoShot();
                    }
                } else {
                    noShotPos = pos.immutable();
                    noShotTicks = 1;
                }
            }
            case BROKE_OCCLUDER -> {
                // 为了拉出射线挖掉的是挡在前面的那一格,不是目标:进旅程账,回执交代,它若也是要挖的格就算她挖的。
                // 挖掉一格就是进展,一路挖开几片树叶不算卡住
                recordBreak(digger.lastBroken());
                claimDrops(digger.lastBroken() == null ? null : digger.lastBroken().pos());
                noteProgress();
                clearNoShot();
            }
            // PROGRESSING — real progress; reset the stall counter.
            default -> clearNoShot();
        }
        return TaskState.RUNNING;
    }

    private void clearNoShot() {
        noShotPos = null;
        noShotTicks = 0;
    }

    // ---- item counting (progress = matching items held in the inventory) ----

    /**
     * 她挖开的这一格掉出来的东西记进 {@link #ourDrops}。每一格只认一次,就在挖开它之后那一刻。
     *
     * <p>不卡实体年龄:那一刻躺在这一格里的东西,她走过去照样会捡进包、照样会被
     * {@link #inventoryMatch()} 数到。既然到手时算,承诺时就得一起算,两边才对得上。
     *
     * <p>无掉落画像(创造)下这本账永远是空的,进度改数敲掉的格数。
     */
    private void claimDrops(BlockPos cell) {
        if (cell == null || !WorkProfile.of(player).dropsLoot() || !claimedCells.add(cell.immutable())) {
            return;
        }
        AABB box = new AABB(cell).inflate(1.5);
        for (ItemEntity ie : player.level().getEntitiesOfClass(ItemEntity.class, box)) {
            // 走不到的那几件躺在刚挖开的格子旁边也不再认领——认领了又会算进够数
            if (dropItems.contains(ie.getItem().getItem()) && !unreachableDrops.contains(ie.getId())) {
                ourDrops.add(ie.getId());
            }
        }
    }

    /**
     * 把导航这条路上挖开的格也入账。
     *
     * <p>为了走过去而挖开的格和她照着目标敲掉的格一样,掉的东西都会进她的包。旅程账
     * ({@code nav.ledger()} / {@link #brokeOnTheWay})是"她挖了什么"的唯一出处,所以这里问它,
     * 而不是另设一套记录。导航拆掉时账会并进旅程账,所以 {@link #stopNav()} 之前再扫一次。
     */
    private void sweepNavBreaks() {
        if (nav == null) {
            return;
        }
        for (var broken : nav.ledger().breaks()) {
            claimDrops(broken.pos());
        }
    }

    @Override
    protected void stopNav() {
        sweepNavBreaks();
        super.stopNav();
    }

    /** 已经敲出来、还没进包的件数;顺手把没了的出账。 */
    private int inFlight() {
        int sum = 0;
        var it = ourDrops.iterator();
        while (it.hasNext()) {
            net.minecraft.world.entity.Entity e = player.level() instanceof ServerLevel sl
                    ? sl.getEntity(it.nextInt()) : null;
            if (!(e instanceof ItemEntity ie) || ie.isRemoved()
                    || !dropItems.contains(ie.getItem().getItem())) {
                it.remove();
                continue;
            }
            sum += ie.getItem().getCount();
        }
        return sum;
    }

    /** Matching items currently carried (sum of stack counts whose item the targets drop). 盔甲/副手不算采集所得。 */
    private int inventoryMatch() {
        if (dropItems.isEmpty()) return baseline;   // before start() resolved the set — no progress yet
        return com.dwinovo.numen.core.PlayerInv.carriedCount(player.getInventory(),
                s -> dropItems.contains(s.getItem()));
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

    /**
     * 按需补货。block_ids 用法:名单快吃完 / 进入新 chunk / 慢心跳到点 / 上次没走完,才起一次搜索。
     * groups 用法:名单空了,或快吃完且过了间隔,就从点名格里收。
     */
    private void maybeQuery() {
        --queryCooldown;
        --heartbeatTimer;
        if (byGroups()) {
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
                || heartbeatTimer <= 0) {
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
        mapped = true;
        coldMapFails = 0;
        capNote = res.sectionCapNote();
        com.dwinovo.numen.core.Constants.LOG.debug(
                "[numen-task] mine query feet={} raw={} capped={} known(before merge)={}",
                player.blockPosition().toShortString(), res.matches().size(), capNote != null,
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
        boolean wanted = byGroups() ? r.named.get(p) == state.getBlock() : r.targets.contains(state.getBlock());
        if (state.isAir() || !wanted) {
            // 点名的格不在了:旅程账上有,就是她顺路挖的(导航穿过它、为拉射线挖掉的遮挡物),算她挖掉的一格;
            // 账上没有,才记成别人动过。点名的格只会从名单或待收里各验出一次"不在了",不会重复计数
            if (byGroups()) {
                if (brokeOnTheWay(p)) {
                    brokenTargets++;
                } else {
                    gone.add(p.immutable());
                }
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
            if (byGroups()) {
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
        lastProgressWork = workTicks();
        lastProgressPos = player.blockPosition();
    }

    /**
     * 真卡住了吗。<b>既没挖掉一格、也没挪出 {@link #STALL_MOVE} 格</b>,持续
     * {@link #STALL_TICKS} 刻才算 —— 走远路去挖矿一刻都不算,她在动。刻数是干活的刻
     * ({@link #workTicks()}):等规划的刻不算卡住,往下挖 170 格的搜索还没回来时她只是在等路。
     *
     * @return 该收工就给终态,否则 null
     */
    private TaskState stalledOut() {
        if (lastProgressPos == null
                || player.blockPosition().distSqr(lastProgressPos) > STALL_MOVE * STALL_MOVE) {
            noteProgress();
            return null;
        }
        long idle = workTicks() - lastProgressWork;
        if (idle < STALL_TICKS) {
            return null;
        }
        com.dwinovo.numen.core.Constants.LOG.info(
                "[numen-task] mine 卡住 {} 刻:没挖掉任何一格、也没挪窝 | feet={} 名单 {} 个",
                idle, player.blockPosition().toShortString(), knownOres.size());
        return unreachable("stuck there with nothing minable in place for " + STALL_TICKS / 20 + " seconds");
    }

    /**
     * 剩下的一个都到不了,收工:挖到过就算成功,如实交代剩下多少没够着;一个没挖到就按 {@code NO_PATH} 失败。
     *
     * <p>够数之后导航的目标只有地上的掉落物,这时到不了的是那批掉落物,不是还没挖的目标:把它们记进
     * {@link #unreachableDrops},接着挖别的补上,不收工。
     *
     * @param why 为什么到不了,原话进回执
     */
    private TaskState unreachable(String why) {
        if (quotaMet && !knownOres.isEmpty()) {
            return writeOffDrops(why);
        }
        String where = player.blockPosition().toShortString();
        String what = knownOres.isEmpty() ? "the drops left on the ground"
                : "the remaining " + knownOres.size() + " " + noun();
        if (r.getMined() > 0) {
            progressNote = "then could not reach " + what + " from " + where + " (" + why + ")" + leftovers(null);
            return TaskState.SUCCESS;
        }
        fail("found " + knownOres.size() + " " + noun() + " but could not reach any of them from " + where
                + " (" + why + "); gathered 0. Move me somewhere else, or clear a way first."
                + leftovers(null), FailureType.NO_PATH);
        return TaskState.FAILED;
    }

    /** 够数所靠的那批掉落物走不到:全部出账,清掉无路的局面与卡住的计时,下一刻按还拿得到的重新算够没够。 */
    private TaskState writeOffDrops(String why) {
        List<ItemEntity> lost = nearbyDrops();
        for (ItemEntity ie : lost) {
            unreachableDrops.add(ie.getId());
            ourDrops.remove(ie.getId());
        }
        com.dwinovo.numen.core.Constants.LOG.info(
                "[numen-task] mine 够数靠的 {} 件掉落物走不到({}),出账接着挖 | feet={} 名单 {} 个",
                lost.size(), why, player.blockPosition().toShortString(), knownOres.size());
        lastNoPath = null;
        stopNav();
        noteProgress();
        return TaskState.RUNNING;
    }

    /** 挖不成的候选为什么挖不成,回执里的说法。 */
    private static final String RULED_OUT_WHY = "unbreakable, excluded by the spec, or fluid or loose falling"
            + " blocks beside them";

    /** 回执里怎么称呼要挖的东西:方块名,groups 用法说"这些方块的格子"。 */
    private String noun() {
        return byGroups() ? "cells of " + r.label : r.label;
    }

    /** 到目前为止的收获,一句话。 */
    private String soFar() {
        return r.count == MineBlockTaskRecord.UNTIL_GONE
                ? "dug " + r.getMined() + " of " + r.cells() + " cells"
                : "gathered " + r.getMined();
    }

    /**
     * 找到了或点名了、却没挖成的各因为什么;都没有是空串。
     *
     * @param told 回执正文已经说过的那一类(失败的主因),不再重复;没有为 null
     */
    private String leftovers(Set<BlockPos> told) {
        List<String> parts = new ArrayList<>(4);
        if (!gone.isEmpty() && told != gone) {
            parts.add(gone.size() + " were gone or had changed before I got to them");
        }
        if (!unharvestable.isEmpty() && told != unharvestable) {
            parts.add(unharvestable.size() + " can't be harvested with the current tools");
        }
        if (!unworkable.isEmpty() && told != unworkable) {
            parts.add(unworkable.size() + " gave no clear shot from any stance");
        }
        if (!ruledOut.isEmpty() && told != ruledOut) {
            parts.add(ruledOut.size() + " can't be broken here (" + RULED_OUT_WHY + ")");
        }
        return parts.isEmpty() ? "" : "; not mined: " + String.join(", ", parts);
    }

    /** Terminal "nothing gathered, no ore left to go for" failure, distinguishing a
     *  genuinely empty field ({@code MINED_OUT} — widening the search or stopping is the
     *  LLM's call) from a field that WAS found but every target turned out unworkable
     *  ({@code NO_PATH} — 没有任何站位能对它拉出射线), with the counts.
     *  「走不到」那一档不在这里 —— 它由 {@link #unreachable} 收工。 */
    private TaskState noOreFailure() {
        if (!unharvestable.isEmpty()) {
            // Targets exist but the carried tools can't make them drop — the actionable
            // problem is the tool, not the deposit. Names the escape hatches explicitly.
            fail("found " + unharvestable.size() + " " + noun() + " but none can be harvested with"
                    + " the current tools (mining would destroy them without any drop); gathered "
                    + r.getMined() + ". Equip a better tool (gear wear) and retry; to just destroy"
                    + " blocks regardless of drops, goto beside them and run use block left on each."
                    + leftovers(unharvestable), FailureType.WRONG_TOOL);
        } else if (!unworkable.isEmpty()) {
            fail("found " + unworkable.size() + " " + noun() + " nearby but no clear shot at any"
                    + " of them from any stance I could take; gathered 0" + leftovers(unworkable),
                    FailureType.NO_PATH);
        } else if (!ruledOut.isEmpty()) {
            fail("found " + ruledOut.size() + " " + noun() + " but none of them can be broken here ("
                    + RULED_OUT_WHY + "); gathered 0"
                    + leftovers(ruledOut), FailureType.MINED_OUT);
        } else if (byGroups()) {
            fail("all " + r.named.size() + " cells of " + r.label + " were gone or had changed since the scan;"
                    + " gathered 0. scan_blocks again to see what is there now.", FailureType.TARGET_LOST);
        } else {
            fail("no reachable " + r.label + " found in the loaded area around me"
                    + (capNote == null ? "" : " (" + capNote + ")"), FailureType.MINED_OUT);
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
        if (heldIn != null) {
            BlockSearch.release(heldIn, r.targets);
            heldIn = null;
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

    /** {@code gathered 3/8 oak_log} 或 {@code dug 3/12 cells of oak_log}。 */
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
