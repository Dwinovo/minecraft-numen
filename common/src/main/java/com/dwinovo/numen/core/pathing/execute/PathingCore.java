package com.dwinovo.numen.core.pathing.execute;

import java.util.Optional;
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
 * <p>独立可实例化:构造只要玩家、搜索派发器与成本上下文工厂,不挂接
 * 任何任务层。
 */
public final class PathingCore {

    private final NumenPlayer player;
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
    private SearchHandle inProgress;
    /** 在飞搜索的 A* 展开起点(句柄不携带,提交时在此记录)。 */
    private BlockPos inProgressStart;
    private Goal goal;
    private CalculationContext context;
    private BlockPos expectedSegmentStart;

    /** 上一次 current.onTick() 的返回值(可安全中断)。 */
    private boolean safeToCancel = true;
    private boolean pauseRequestedLastTick;
    private boolean unpausedLastTick = true;
    private boolean pausedThisTick;
    private boolean cancelRequested;
    private boolean calcFailedLastTick;

    public PathingCore(NumenPlayer player, SearchDispatcher dispatcher,
                       Supplier<CalculationContext> searchContextFactory,
                       Supplier<CalculationContext> executionContextFactory) {
        this.player = player;
        this.harness = new ExecHarness(player);
        this.dispatcher = dispatcher;
        this.searchContextFactory = searchContextFactory;
        this.executionContextFactory = executionContextFactory;
    }

    /**
     * 搜索与执行同一份快照的简便构造:执行期复核直接读本次搜索的上下文。
     * <b>注意</b>:执行期成本复核因此读不到世界变化(快照冻结),生产
     * 路径必须走双工厂构造(执行侧供活世界上下文),此构造仅限测试。
     */
    public PathingCore(NumenPlayer player, SearchDispatcher dispatcher,
                       Supplier<CalculationContext> contextFactory) {
        this(player, dispatcher, contextFactory, null);
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
        this.goal = newGoal;
        if (goal == null) {
            return false;
        }
        BlockPos feet = PathExecutor.playerFeet(player);
        if (goal.isInGoal(feet.getX(), feet.getY(), feet.getZ())) {
            return false;
        }
        if (current != null || inProgress != null) {
            return false;
        }
        expectedSegmentStart = pathStart();
        startSearch(expectedSegmentStart);
        return true;
    }

    /** 目标失效裁决:当前段终点原本在旧目标内、而不在新目标内。 */
    private boolean goalInvalidatedBy(Goal newGoal) {
        if (current == null || goal == null || newGoal == null) {
            return false;
        }
        BlockPos dest = current.getPath().getDest();
        return goal.isInGoal(dest.getX(), dest.getY(), dest.getZ())
                && !newGoal.isInGoal(dest.getX(), dest.getY(), dest.getZ());
    }

    /** 请求暂停;下一 tick 在安全点生效(路径保留,isPathing 变 false)。 */
    public void requestPause() {
        pauseRequestedLastTick = true;
    }

    /** 安全时取消当前段(取消在飞搜索、清段、清键、停挖)。 */
    public boolean cancelSegmentIfSafe() {
        if (isSafeToCancel()) {
            segmentCancel();
            return true;
        }
        return false;
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
        cancelRequested = true;
    }

    /** 全面取消:段取消(若安全)并放弃目标。 */
    public boolean cancelEverything() {
        boolean doIt = cancelSegmentIfSafe();
        goal = null;
        return doIt;
    }

    /** 是否正在沿路径行进(有段且本 tick 未暂停)。 */
    public boolean isPathing() {
        return current != null && !pausedThisTick;
    }

    /** 当前是否可安全中断(悬空放置、跑酷空中等时刻为 false)。 */
    public boolean isSafeToCancel() {
        return current == null || safeToCancel;
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

    /** 在飞搜索此刻的最优部分路径;无在飞搜索或暂无候选时为空。 */
    public Optional<NavPath> inProgressBestPath() {
        return inProgress == null ? Optional.empty() : inProgress.bestPathSoFar();
    }

    public NumenPlayer player() {
        return player;
    }

    public ExecHarness harness() {
        return harness;
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
        if (current != null && !pausedThisTick) {
            player.setSprinting(current.isSprinting());
        }
    }

    private void tickPath() {
        pausedThisTick = false;
        pollSearchResult();
        // 暂停请求:安全点生效;首次进入暂停时清键停挖,此后静默保持
        if (pauseRequestedLastTick && isSafeToCancel()) {
            pauseRequestedLastTick = false;
            if (unpausedLastTick) {
                harness.clearAllKeys();
                harness.stopBreaking();
            }
            unpausedLastTick = false;
            pausedThisTick = true;
            return;
        }
        unpausedLastTick = true;
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
        safeToCancel = current.onTick();
        if (current.failed() || current.finished()) {
            current = null;
            BlockPos feet = PathExecutor.playerFeet(player);
            if (goal == null || goal.isInGoal(feet.getX(), feet.getY(), feet.getZ())) {
                Constants.LOG.debug("已到达目标");
                next = null;
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
            if (inProgress != null) {
                return; // 段刚结束,等在飞计算
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
        if (inProgress != null) {
            return;
        }
        if (next != null) {
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
        // 每次派发都重新取样冻结快照:背包/工具/饥饿与语义开关
        // (sacred/deniedPlace)以派发一刻为准
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
        Favoring favoring = new Favoring(current == null ? null : current.getPath(), context,
                com.dwinovo.numen.core.pathing.astar.Avoidance.create(player));
        // 真实脚位与展开起点同层且 XZ 各差 ≤1 时,假起点素材用脚位
        BlockPos feet = PathExecutor.playerFeet(player);
        BlockPos realStart = start;
        if (feet.getY() == start.getY()
                && Math.abs(feet.getX() - start.getX()) <= 1
                && Math.abs(feet.getZ() - start.getZ()) <= 1) {
            realStart = feet;
        }
        inProgressStart = start;
        inProgress = dispatcher.submit(realStart, start, goal, context, favoring,
                primaryTimeout, failureTimeout);
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
                    current = executor.get();
                } else {
                    Constants.LOG.debug("丢弃起点不符的孤儿路径段");
                }
            } else if (result.getType() != PathCalcResult.Type.CANCELLATION
                    && result.getType() != PathCalcResult.Type.EXCEPTION) {
                Constants.LOG.debug("首段计算失败");
                calcFailedLastTick = true;
            }
        } else {
            if (next == null) {
                if (executor.isPresent()) {
                    if (executor.get().getPath().getSrc().equals(current.getPath().getDest())) {
                        next = executor.get();
                    } else {
                        Constants.LOG.debug("丢弃起点与当前段终点不接的接续段");
                    }
                } else {
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
        return Movement.pathStart(player);
    }

    // ==================== 内部取消 ====================

    private void segmentCancel() {
        if (inProgress != null) {
            inProgress.cancel();
        }
        if (current != null) {
            current = null;
            next = null;
            harness.clearAllKeys();
            harness.stopBreaking();
        }
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
        goal = null;
        harness.clearAllKeys();
        harness.stopBreaking();
    }
}
