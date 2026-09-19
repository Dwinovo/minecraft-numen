package com.dwinovo.numen.core.pathing.execute;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.pathing.astar.Favoring;
import com.dwinovo.numen.core.pathing.astar.NavPath;
import com.dwinovo.numen.core.pathing.astar.PathCalcResult;
import com.dwinovo.numen.core.pathing.bridge.SearchDispatcher;
import com.dwinovo.numen.core.pathing.bridge.SearchHandle;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;

/**
 * 段规划状态机:目标 → 首段搜索 → 执行 → 提前规划接续段 → 无缝接段,
 * 直到进入目标或被取消。持有 current(在执行的段)/ next(算好的下一
 * 段)/ inProgress(在飞搜索)三个槽位与本次导航的成本上下文。
 *
 * <p>每 tick 一次 {@link #tick()}:先记录假起点(expectedSegmentStart),
 * 依次处理暂停/取消请求、在飞搜索合法性、当前段推进、段界分流、提前
 * 接段与拼接、提前规划触发,最后把本 tick 的输入/视角记录提交到实体。
 *
 * <p><b>开走前的放行口</b>:每一段路(首段、接续段、外部采纳的整路)被采纳之前先过
 * {@code admission};不放行的段扣在 {@link #heldPath} 里——不执行、不再派搜索、不提前规划,
 * 等调用方 {@link #releaseHeld} 或放弃。执行开始前要问主人的路,就停在这里问。
 *
 * <p>独立可实例化:构造只要玩家、搜索派发器、成本上下文工厂与放行口,不挂接
 * 任何任务层。
 */
public final class PathingCore {

    private final NumenPlayer player;
    private final RouteSpec spec;
    private final ExecHarness harness;
    private final SearchDispatcher dispatcher;
    /**
     * 搜索用成本上下文工厂:每次派发搜索时取样一份冻结快照(背包/附魔/
     * 语义开关随之刷新),整场搜索用同一把尺。
     */
    private final Supplier<CalculationContext> searchContextFactory;
    /**
     * 执行期成本上下文工厂:活世界视图,执行器逐 tick 复核成本时取样。
     * 与搜索侧同一套成本函数,只是世界读的是当下。
     */
    private final Supplier<CalculationContext> executionContextFactory;

    private PathExecutor current;
    private PathExecutor next;
    /** 算好了、没被放行的那一段:{@link #current} 为空时是首段,否则是接续段。 */
    private PathExecutor held;
    /** 一段路能不能开走;不能的扣进 {@link #held}。 */
    private final Predicate<NavPath> admission;
    /** 连续丢弃孤儿首段的容忍次数;超出即判首段失败,交上层裁决。 */
    private static final int MAX_ORPHAN_DISCARDS = 3;
    private int orphanDiscards;
    /** 连续"采纳即夭折且未挪窝"的路段容忍数;超出即判首段失败。 */
    private static final int MAX_STERILE_SEGMENTS = 4;
    private int sterileSegments;
    private int dispatches;
    private final java.util.TreeMap<String, Integer> outcomes = new java.util.TreeMap<>();

    private SearchHandle inProgress;
    /** 在飞搜索的 A* 展开起点(句柄不携带,提交时在此记录)。 */
    private BlockPos inProgressStart;
    private Goal goal;
    private CalculationContext context;
    private BlockPos expectedSegmentStart;

    /** 上一次 current.onTick() 的返回值(可安全中断)。 */
    private boolean safeToCancel = true;
    private boolean cancelRequested;
    private boolean calcFailedLastTick;

    /**
     * @param spec      这次导航的路线规格;段起点"脚下能不能站"按它判(两份上下文工厂建出的
     *                  上下文带的是同一份规格加上按目标变化的位置代价)
     * @param admission 一段路被采纳之前问:此刻能不能开走
     */
    public PathingCore(NumenPlayer player, SearchDispatcher dispatcher,
                       Supplier<CalculationContext> searchContextFactory,
                       Supplier<CalculationContext> executionContextFactory,
                       RouteSpec spec, Predicate<NavPath> admission) {
        this.player = player;
        this.spec = spec;
        this.admission = admission;
        this.harness = new ExecHarness(player);
        this.dispatcher = dispatcher;
        this.searchContextFactory = searchContextFactory;
        this.executionContextFactory = executionContextFactory;
    }

    // ==================== 对外 API ====================

    /**
     * 设定目标并开始寻路。目标失效裁决:当前段终点原本在旧目标内、
     * 而不在新目标内 → 旧段作废(软取消,不清键,下一 tick 无缝重算)。
     * 已在目标内 / 已有段或在飞搜索时不再发起新搜索。
     *
     * @return 是否真的发起了一次新搜索
     */
    public boolean setGoalAndPath(Goal newGoal) {
        if (NavSettings.get().cancelOnGoalInvalidation && goalInvalidatedBy(newGoal)) {
            Constants.LOG.debug("当前段终点不再被新目标认可,软取消");
            softCancelIfSafe();
        }
        if (held != null && leadsOutOf(held, newGoal)) {
            held = null;   // 扣着的那段通向的地方已经不算目标了:作废,按新目标重搜
        }
        this.goal = newGoal;
        if (goal == null) {
            return false;
        }
        BlockPos feet = PathExecutor.playerFeet(player);
        // 脚下就在目标里,而且停在这儿的到达价不高于别处的乐观总价:不用搜。脚下那个成员比别处
        // 贵(脚边是主人的原木、几格外有野树)就照样搜,由搜索按总价挑
        if (goal.isInGoal(feet.getX(), feet.getY(), feet.getZ())
                && goal.arrivalCost(feet.getX(), feet.getY(), feet.getZ())
                        <= goal.heuristic(feet.getX(), feet.getY(), feet.getZ())) {
            return false;
        }
        if (current != null || inProgress != null || held != null) {
            return false;
        }
        expectedSegmentStart = pathStart();
        startSearch(expectedSegmentStart);
        return true;
    }

    /**
     * 采纳一条已规划好的路径作为首段:目标与路径一并下发,身体从它的起点接着走。
     * 中途失败照常在本内核的规格下重搜——路径不是可交接的东西,目标加规格才是。
     * 只在空闲(无段、无在飞搜索)时可调。
     */
    public void seed(Goal goal, NavPath path) {
        if (current != null || inProgress != null || held != null) {
            throw new IllegalStateException("已有路段或在飞搜索,不能再采纳路径");
        }
        this.goal = goal;
        context = searchContextFactory.get();
        PathExecutor seeded = newExecutor(path);
        if (admission.test(path)) {
            current = seeded;
        } else {
            held = seeded;
        }
    }

    /** 目标失效裁决:当前段终点原本在旧目标内、而不在新目标内。 */
    private boolean goalInvalidatedBy(Goal newGoal) {
        return current != null && leadsOutOf(current, newGoal);
    }

    /** 这段路的终点原本在旧目标内,按新目标停在那儿不再算数(见 {@link Goal#keepsStop})。 */
    private boolean leadsOutOf(PathExecutor segment, Goal newGoal) {
        if (goal == null || newGoal == null) {
            return false;
        }
        BlockPos dest = segment.getPath().getDest();
        return goal.isInGoal(dest.getX(), dest.getY(), dest.getZ())
                && !Goal.keepsStop(goal, newGoal, dest.getX(), dest.getY(), dest.getZ());
    }

    /** 扣着没放行的那一段的路径;没有是 null。 */
    public NavPath heldPath() {
        return held == null ? null : held.getPath();
    }

    /** 放行扣着的那一段:没有在走的段就当首段开走,否则接在当前段后面。 */
    public void releaseHeld() {
        if (held == null) {
            return;
        }
        if (current == null) {
            current = held;
        } else {
            next = held;
        }
        held = null;
    }

    /**
     * 软取消:取消在飞搜索、丢弃段,但不清键——身体保持惯性,下一
     * tick 的重规划无缝接手。不安全时只取消在飞搜索。
     */
    public void softCancelIfSafe() {
        if (inProgress != null) {
            inProgress.cancel();
        }
        if (!isSafeToCancel()) {
            return;
        }
        current = null;
        next = null;
        held = null;
        cancelRequested = true;
    }

    /** 是否正在沿路径行进。 */
    public boolean isPathing() {
        return current != null;
    }

    /** 当前是否可安全中断(悬空放置、跑酷空中等时刻为 false)。 */
    public boolean isSafeToCancel() {
        return current == null || safeToCancel;
    }

    /** 本状态机的执行器至今真动过的地形(账本住在执行器里,这里只是递出去)。 */
    public TerrainBill ledger() {
        return harness.ledger();
    }

    /** 上一 tick 是否有一次首段计算以失败告终。 */
    public boolean calcFailedLastTick() {
        return calcFailedLastTick;
    }

    public Goal getGoal() {
        return goal;
    }

    public PathExecutor getCurrent() {
        return current;
    }

    public PathExecutor getNext() {
        return next;
    }

    public boolean hasInProgressSearch() {
        return inProgress != null;
    }

    /** 本内核迄今的搜索结论分布(排障用:分辨"搜不到路"与"只搜到半程")。 */
    public String outcomeSummary() {
        return dispatches + "派/" + outcomes
                + " 段[" + (current == null ? "无" : current.progressSummary()) + "]";
    }

    /** 在飞搜索此刻的最优部分路径;无在飞搜索或暂无候选时为空。 */
    public Optional<NavPath> inProgressBestPath() {
        return inProgress == null ? Optional.empty() : inProgress.bestPathSoFar();
    }

    public NumenPlayer player() {
        return player;
    }

    // ==================== 活跃实例注册表 ====================

    /** 每同伴最近一次 tick 过的内核(调试可视化等旁路消费者按此取用)。 */
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, PathingCore> LIVE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 当前各同伴最近活跃的内核快照(已移除实体的条目顺手清掉)。 */
    public static java.util.Collection<PathingCore> liveCores() {
        LIVE.values().removeIf(core -> core.player.isRemoved());
        return LIVE.values();
    }

    // ==================== 每 tick ====================

    /**
     * 推进一 tick。执行前强制记录假起点(执行会移动身位,搜索起点要
     * 用执行前的);末尾统一提交输入/视角并落地疾跑决策。
     */
    public void tick() {
        LIVE.put(player.getUUID(), this);
        expectedSegmentStart = pathStart();
        calcFailedLastTick = false;
        tickPath();
        harness.commitIfDirty();
        if (current != null) {
            player.setSprinting(current.isSprinting());
        }
    }

    private void tickPath() {
        pollSearchResult();
        if (cancelRequested) {
            cancelRequested = false;
            harness.clearAllKeys();
        }
        // 在飞搜索合法性:起点既不是当前段终点、也不是身位/假起点,
        // 其最优部分路径也不含这两者 → 玩家被打飞/传送,计算作废
        if (inProgress != null) {
            BlockPos calcFrom = inProgressStart;
            BlockPos feet = PathExecutor.playerFeet(player);
            Optional<NavPath> currentBest = inProgress.bestPathSoFar();
            if ((current == null || !current.getPath().getDest().equals(calcFrom))
                    && !calcFrom.equals(feet) && !calcFrom.equals(expectedSegmentStart)
                    && (currentBest.isEmpty()
                            || (!currentBest.get().positions().contains(feet)
                                && !currentBest.get().positions().contains(expectedSegmentStart)))) {
                Constants.LOG.debug("在飞搜索的起点已作废,取消该计算");
                inProgress.cancel();
            }
        }
        if (current == null) {
            return;
        }
        BlockPos feetBefore = PathExecutor.playerFeet(player);
        safeToCancel = current.onTick();
        if (current.failed() || current.finished()) {
            noteSegmentEnd(current, feetBefore);
            current = null;
            BlockPos feet = PathExecutor.playerFeet(player);
            if (goal == null || goal.isInGoal(feet.getX(), feet.getY(), feet.getZ())) {
                Constants.LOG.debug("已到达目标");
                next = null;
                held = null;
                sterileSegments = 0;
                return;
            }
            if (next != null && !next.getPath().positions().contains(feet)
                    && !next.getPath().positions().contains(expectedSegmentStart)) {
                // 段中途失败,身位已不在计划好的下一段上,只能忍痛丢弃
                Constants.LOG.debug("下一段不含当前身位,丢弃");
                next = null;
            }
            if (next != null) {
                Constants.LOG.debug("无缝接上已计划的下一段");
                current = next;
                next = null;
                current.onTick(); // 不浪费本 tick,立即推进
                return;
            }
            if (held != null && !held.getPath().positions().contains(feet)
                    && !held.getPath().positions().contains(expectedSegmentStart)) {
                held = null;   // 扣着的接续段接不上身位了,和 next 同一口径丢弃
            }
            if (held != null || inProgress != null) {
                return; // 段刚结束:接续段扣着等放行,或等在飞计算
            }
            startSearch(expectedSegmentStart);
            return;
        }
        // 当前段进行中:身位恰在下一段格位上时提前跳段
        if (safeToCancel && next != null && next.snipsnapifpossible()) {
            Constants.LOG.debug("提前接入下一段");
            current = next;
            next = null;
            current.onTick();
            return;
        }
        if (NavSettings.get().splicePath) {
            current = current.trySplice(next);
        }
        if (next != null && current.getPath().getDest().equals(next.getPath().getDest())) {
            next = null;
        }
        if (inProgress != null || next != null || held != null) {
            return;
        }
        BlockPos dest = current.getPath().getDest();
        if (goal == null || goal.isInGoal(dest.getX(), dest.getY(), dest.getZ())) {
            return; // 当前段已能走到目标
        }
        // 剩余成本不含当前移动:超长的末尾移动不该阻塞提前规划
        double ticksRemaining = current.getPath().ticksRemainingFrom(current.getPosition() + 1);
        if (ticksRemaining < NavSettings.get().planningTickLookahead) {
            Constants.LOG.debug("当前段剩余约 {} tick,提前规划接续段", (int) ticksRemaining);
            startSearch(current.getPath().getDest());
        }
    }

    // ==================== 搜索派发与收割 ====================

    private void startSearch(BlockPos start) {
        if (inProgress != null) {
            throw new IllegalStateException("已有在飞搜索");
        }
        if (goal == null) {
            return;
        }
        // 每次派发都重新取样冻结快照:背包/工具/饥饿与规格的位置代价
        // (当前目标的 sacred 格)以派发一刻为准
        context = searchContextFactory.get();
        long primaryTimeout;
        long failureTimeout;
        NavSettings settings = NavSettings.get();
        if (current == null) {
            primaryTimeout = settings.primaryTimeoutMS;
            failureTimeout = settings.failureTimeoutMS;
        } else {
            primaryTimeout = settings.planAheadPrimaryTimeoutMS;
            failureTimeout = settings.planAheadFailureTimeoutMS;
        }
        // 躲谁由目标说了算:战斗目标自带威胁表(位置+每只自己的危险半径),
        // 别的导航照旧走全局开关(默认关)。
        Favoring favoring = new Favoring(current == null ? null : current.getPath(), context,
                com.dwinovo.numen.core.pathing.astar.Avoidance.forGoal(goal, player));
        // 真实脚位与展开起点同层且 XZ 各差 ≤1 时,假起点素材用脚位
        BlockPos feet = PathExecutor.playerFeet(player);
        BlockPos realStart = start;
        if (feet.getY() == start.getY()
                && Math.abs(feet.getX() - start.getX()) <= 1
                && Math.abs(feet.getZ() - start.getZ()) <= 1) {
            realStart = feet;
        }
        inProgressStart = start;
        dispatches++;
        inProgress = dispatcher.submit(realStart, start, goal, context, favoring,
                primaryTimeout, failureTimeout);
    }

    /**
     * 空转段记账:一个路段被采纳后当刻夭折、身位一步没挪,就是"规划器算得出、
     * 执行器不认账"——两边对同一动作的可行性判断不一致。重新规划的输入完全
     * 没变,必然算出同一条路,于是规划器与执行器可以对着掐到天荒地老。
     *
     * <p>这个循环整个发生在 {@code core.tick()} 内部:路段生于此刻、死于此刻,
     * 外层每次看到的都是"无段",PlayerNav 那套"上一刻有段、这一刻没了"的执行
     * 失败记账因此从不触发——静默活锁。连续空转够数即按首段失败上报,让上层
     * 去跳格,和孤儿段同一原则:任何结局都必须收敛出裁决。
     */
    private void noteSegmentEnd(PathExecutor segment, BlockPos feetBefore) {
        if (!segment.failed() || !PathExecutor.playerFeet(player).equals(feetBefore)) {
            sterileSegments = 0;   // 走动过、或正常收尾:不是空转
            return;
        }
        if (++sterileSegments < MAX_STERILE_SEGMENTS) {
            return;
        }
        Constants.LOG.info(
                "[numen-path] 连续 {} 个路段被采纳即夭折且身位未动(段长 {},因由 {}),"
                        + "判首段失败——规划器与执行器对同一动作判断不一致",
                sterileSegments, segment.getPath().length(), segment.failureCause());
        sterileSegments = 0;
        calcFailedLastTick = true;
    }

    /** 搜索结论分类计数(INFO 级定期摊开):重派活锁靠这张表定位——派了多少次、
     *  每种结论各多少,一眼看出刻数被哪条分支吞掉。原有分支日志全是 debug 级,
     *  发布态不落盘,查线上问题时等于没有。 */
    private void countOutcome(String kind) {
        outcomes.merge(kind, 1, Integer::sum);
        if (dispatches % 128 != 0) {
            return;
        }
        Constants.LOG.info("[numen-path-outcome] 派发{} 结论{} 目标={} 身位={} 段={}",
                dispatches, outcomes, goal,
                PathExecutor.playerFeet(player).toShortString(),
                current == null ? "无" : current.getPath().length());
    }

    /** 收割在飞搜索:首段须包含假起点(否则是孤儿段),接续段须首尾相接。 */
    private void pollSearchResult() {
        if (inProgress == null) {
            return;
        }
        PathCalcResult result = inProgress.poll();
        if (result == null) {
            return;
        }
        inProgress = null;
        inProgressStart = null;
        Optional<PathExecutor> executor = result.getPath().map(this::newExecutor);
        if (current == null) {
            if (executor.isPresent()) {
                if (executor.get().getPath().positions().contains(expectedSegmentStart)) {
                    orphanDiscards = 0;
                    if (admission.test(executor.get().getPath())) {
                        current = executor.get();
                    } else {
                        held = executor.get();
                    }
                    countOutcome("首段采纳:" + result.getType() + "x"
                            + executor.get().getPath().length());
                } else {
                    countOutcome("孤儿段");
                    // 孤儿段必须记账:丢弃既不产生可走的段、也不产生裁决,而上层
                    // 见"无段且无在飞搜索"就重新派发——静默丢弃 = 每秒数百次的
                    // 重派活锁,身体一步不走,任务层永远等不到失败结论。连续丢够
                    // 就是这个起点搜不出能走的路,按首段失败上报,让上层去跳格。
                    if (++orphanDiscards >= MAX_ORPHAN_DISCARDS) {
                        Constants.LOG.info(
                                "[numen-path] 连续 {} 次孤儿段:起点 {} 不在结果路径上"
                                        + "(路径起点 {},长 {}),判首段失败",
                                orphanDiscards, expectedSegmentStart,
                                executor.get().getPath().getSrc(),
                                executor.get().getPath().length());
                        orphanDiscards = 0;
                        calcFailedLastTick = true;
                    } else {
                        Constants.LOG.debug("丢弃起点不符的孤儿路径段");
                    }
                }
            } else {
                countOutcome("无路径:" + result.getType());
                if (result.getType() != PathCalcResult.Type.CANCELLATION
                        && result.getType() != PathCalcResult.Type.EXCEPTION) {
                    Constants.LOG.debug("首段计算失败");
                    orphanDiscards = 0;
                    calcFailedLastTick = true;
                }
            }
        } else {
            if (next == null && held == null) {
                if (executor.isPresent()) {
                    if (executor.get().getPath().getSrc().equals(current.getPath().getDest())) {
                        if (admission.test(executor.get().getPath())) {
                            next = executor.get();
                        } else {
                            held = executor.get();
                        }
                        countOutcome("接续采纳:" + result.getType());
                    } else {
                        countOutcome("接续不接");
                        Constants.LOG.debug("丢弃起点与当前段终点不接的接续段");
                    }
                } else {
                    countOutcome("接续无路径");
                    Constants.LOG.debug("接续段计算失败");
                }
            } else {
                Constants.LOG.warn("收到计算结果时已有下一段,丢弃该结果");
            }
        }
    }

    private PathExecutor newExecutor(NavPath path) {
        Supplier<CalculationContext> recost =
                executionContextFactory != null ? executionContextFactory : () -> context;
        // 区块边界暂停查活世界(只查内存,不触发加载):后到的区块生成
        // 完成后暂停即刻解除,不被搜索时的冻结快照钉死
        var liveView = com.dwinovo.numen.core.pathing.cache.LoadedOnlyView.of(player.level());
        ChunkLoadedTest liveLoaded =
                liveView instanceof com.dwinovo.numen.core.pathing.cache.LoadedOnlyView v
                        ? v::isLoaded : context.loadedTest;
        return new PathExecutor(path, player, harness,
                recost,
                () -> inProgress == null ? Optional.empty() : inProgress.bestPathSoFar(),
                liveLoaded);
    }

    // ==================== 假起点 ====================

    /**
     * 新段的搜索起点(脚下不可站时的假起点)。语义与 Movement.pathStart
     * 同一,提取到基类静态助手后此处直接转发,逻辑不再重复。
     */
    public BlockPos pathStart() {
        return Movement.pathStart(player, spec);
    }


    /**
     * 无条件全停:取消在飞搜索、丢弃当前/下一段、放弃目标、清键停挖。
     * 不看 safeToCancel——外部要求彻底停下(任务清理)时身体状态由
     * 调用方兜底。
     */
    public void forceCancel() {
        if (inProgress != null) {
            inProgress.cancel();
            inProgress = null;
            inProgressStart = null;
        }
        current = null;
        next = null;
        held = null;
        goal = null;
        harness.clearAllKeys();
        harness.stopBreaking();
    }
}
