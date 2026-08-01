package com.dwinovo.numen.core.pathing.execute;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.pathing.astar.NavPath;
import com.dwinovo.numen.core.pathing.astar.SplicedPath;
import com.dwinovo.numen.core.pathing.astar.CutoffPath;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
import com.dwinovo.numen.core.pathing.moves.Input;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.moves.MovementState;
import com.dwinovo.numen.core.pathing.moves.MovementStatus;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;
import com.dwinovo.numen.core.pathing.moves.movements.MovementAscend;
import com.dwinovo.numen.core.pathing.moves.movements.MovementDescend;
import com.dwinovo.numen.core.pathing.moves.movements.MovementDiagonal;
import com.dwinovo.numen.core.pathing.moves.movements.MovementFall;
import com.dwinovo.numen.core.pathing.moves.movements.MovementParkour;
import com.dwinovo.numen.core.pathing.moves.movements.MovementTraverse;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;

/**
 * 路径执行器:驱动一条已算好的路径逐移动执行完。每 tick 一次
 * {@link #onTick()},完整职责链:
 * 终点推进 → 位置重定位(回退扫/前跳扫)→ 脱轨双带看门狗 →
 * 边界暂停 → 成本核验(进入新移动时前瞻重算)→ 本移动逐 tick 涨价
 * 中止 → 回头暂停 → 移动状态机分派 → 疾跑整体决策 → 单移动超时。
 *
 * <p>疾跑不走按键:移动原语请求的 SPRINT 键被执行器没收,由执行器
 * 结合前后移动的上下文(直跳上台、下坡连锁、V 形谷、坠落前越)统一
 * 决策,直接写实体疾跑状态。
 */
public final class PathExecutor {

    /** 离路径超过该距离立即取消。 */
    static final double MAX_MAX_DIST_FROM_PATH = 3;
    /** 离路径超过该距离开始计时。 */
    static final double MAX_DIST_FROM_PATH = 2;
    /** 脱轨计时超过该 tick 数取消(10 秒)。 */
    static final double MAX_TICKS_AWAY = 200;

    private final NavPath path;
    private final NumenPlayer player;
    private final ExecHarness harness;
    /** 执行期重算成本用的上下文(活世界视图,主线程)。 */
    private final Supplier<CalculationContext> contextSupplier;
    /** 在飞搜索的当前最优部分路径;无在飞搜索或暂无候选时为空。 */
    private final Supplier<Optional<NavPath>> inProgressBestPath;
    private final ChunkLoadedTest loadedTest;

    private int pathPosition;
    private int ticksAway;
    private int ticksOnCurrent;
    /** 距上次真实推进(移动完成/重定位/活跃挖掘)的 tick 数,liveness 信号。 */
    private int ticksSinceProgress;
    /** 身位连续不在路径任何合法位上的刻数;超限即取消重算。 */
    private int ticksNotInValid;
    private static final int MAX_TICKS_NOT_IN_VALID = 20;
    /** 单次 onTick 内递归推进次数守卫:回退扫/SUCCESS 推进后递归 onTick
     * 可能在某 movement 的 SUCCESS 判定与 validPositions 不自洽时空转
     * 爆栈。超过路径长度即判定为病态循环,取消而非继续递归。 */
    private int tickRecursionDepth;
    /** 最近若干次推进类型(pos@feet:reason)的环形记录,守卫触发时回溯定位循环。 */
    private final String[] recentSteps = new String[16];
    private int recentStepsHead;
    private Double currentMovementOriginalCostEstimate;
    private Integer costEstimateIndex;
    /** 剩余路径的三类格集需要重建(格集内容较上 tick 有变化时置位)。 */
    private boolean recalcBP = true;
    /** 剩余路径将要挖穿的全部格(每 tick 维护,可视化/外部查询用)。 */
    private HashSet<BlockPos> toBreak = new HashSet<>();
    /** 剩余路径将要放方块的全部格。 */
    private HashSet<BlockPos> toPlace = new HashSet<>();
    /** 剩余路径将要挤身而过的全部格。 */
    private HashSet<BlockPos> toWalkInto = new HashSet<>();
    private boolean failed;
    /** 取消原因(失败验尸与放弃判定的素材);未失败时为 null。 */
    private String failureCause;
    private boolean sprintNextTick;

    public PathExecutor(NavPath path, NumenPlayer player, ExecHarness harness,
                        Supplier<CalculationContext> contextSupplier,
                        Supplier<Optional<NavPath>> inProgressBestPath,
                        ChunkLoadedTest loadedTest) {
        this.path = path;
        this.player = player;
        this.harness = harness;
        this.contextSupplier = contextSupplier;
        this.inProgressBestPath = inProgressBestPath;
        this.loadedTest = loadedTest;
        this.pathPosition = 0;
    }

    /**
     * 脚位约定:实体坐标 y 加 0.1251(灵魂沙/农田顶面矮一截仍归上格),
     * 落在台阶格里再上抬一格。转发 {@link Movement#feet}——执行器与
     * 移动原语共用同一把尺,避免半砖顶面错位导致推进-回退循环。
     */
    public static BlockPos playerFeet(ServerPlayer player) {
        return Movement.feet(player);
    }

    /**
     * 推进一 tick(首层入口:重置递归深度守卫,委派 {@link #onTick0()})。
     *
     * @return true 表示一个移动刚完成/路径进入暂停等"稳定"状态,
     *         false 表示位置刚被重定位(本 tick 已递归重跑);进行中
     *         返回该移动的 safeToCancel。
     */
    public boolean onTick() {
        tickRecursionDepth = 0;
        recentStepsHead = 0;
        java.util.Arrays.fill(recentSteps, null);
        return onTick0();
    }

    /** 记一次推进到环形缓冲,守卫触发时回溯定位循环路径。 */
    private void recordStep(String step) {
        recentSteps[recentStepsHead] = step;
        recentStepsHead = (recentStepsHead + 1) % recentSteps.length;
    }

    /** 递归实现:回退扫/SUCCESS 推进后自调,深度守卫止爆。 */
    private boolean onTick0() {
        if (tickRecursionDepth++ > path.length()) {
            // 回退扫/SUCCESS 推进后递归 onTick 超过路径长度:某 movement 的
            // SUCCESS 判定与 validPositions 不自洽,推进-回退空转。取消止爆。
            BlockPos feet = playerFeet(player);
            Movement m = path.movements().get(Math.min(pathPosition, path.movements().size() - 1));
            StringBuilder trace = new StringBuilder();
            for (int i = 0; i < recentSteps.length; i++) {
                String s = recentSteps[(recentStepsHead + i) % recentSteps.length];
                if (s != null) {
                    trace.append('[').append(s).append("] ");
                }
            }
            Constants.LOG.warn("onTick 递归超路长 {} 格,推进空转取消。pos={} feet={} movement={} src={} dest={} valid={} 迹:{}",
                    path.length(), pathPosition, feet, m.getClass().getSimpleName(),
                    m.getSrc(), m.getDest(), m.getValidPositions(), trace);
            cancel("执行器推进空转(SUCCESS 与合法位不自洽,超 " + path.length() + " 步)");
            return false;
        }
        if (pathPosition == path.length() - 1) {
            pathPosition++; // 最后一个格位没有对应移动,直接完成
        }
        if (pathPosition >= path.length()) {
            return true;
        }
        Movement movement = path.movements().get(pathPosition);
        movement.setExecutionDelegate(harness);
        BlockPos feet = playerFeet(player);
        boolean inValid = movement.getValidPositions().contains(feet);
        if (tickRecursionDepth < 6 && !inValid) {
            Constants.LOG.debug("[diag] pos={} feet={} movement={} src={} dest={} valid={} contains={}",
                    pathPosition, feet, movement.getClass().getSimpleName(),
                    movement.getSrc(), movement.getDest(),
                    movement.getValidPositions(), inValid);
        }
        if (!inValid) {
            // 回退扫:被击退/卡顿回传后落在已走过的移动里,退回去重走
            int back = findBackwardMatch(path.movements(), pathPosition, feet);
            if (back != -1) {
                int previousPos = pathPosition;
                pathPosition = back;
                for (int j = pathPosition; j <= previousPos; j++) {
                    path.movements().get(j).reset();
                }
                recordStep("back " + previousPos + "->" + back + " feet=" + feet);
                onChangeInPathPosition();
                onTick0();
                return false;
            }
            // 前跳扫:从 +3 起(+1 由移动自己报成功,+2 不查),身位落在
            // 后面的移动里则跳过去
            int forward = findForwardSkip(path.movements(), pathPosition, feet);
            if (forward != -1) {
                pathPosition = forward - 1;
                recordStep("fwd ->" + (forward - 1) + " feet=" + feet);
                onChangeInPathPosition();
                onTick0();
                return false;
            }
            // 前后都对不上:身位不在这条路径的任何合法位里。动作的前提就是"人在
            // 起点",前提破了硬撑没有意义——只会耗满超时再重规划,期间人一动不动。
            // 台阶/楼梯把她自动抬高一格、蹭偏一格就足以造成这种脱节,而日式小屋
            // 满地是楼梯,越往高层越密。给一点宽限(可能只是瞬时越界),超了就
            // 取消,让规划器按她实际所在的位置重新算。
            // 裁决必须和动作自己的合法性判定同源:脚下那格没支撑时(站在柱顶
            // 边沿之类),搜索用的是旁边那格作"假起点",动作也认这个假起点。
            // 只按 feet 判就比动作自己更严,会把本来健康的路径一条条掐掉。
            if (movement.getValidPositions().contains(Movement.pathStart(player))) {
                ticksNotInValid = 0;
            } else if (++ticksNotInValid > MAX_TICKS_NOT_IN_VALID) {
                Constants.LOG.info(
                        "[numen-path] 身位脱离路径 {} 刻(身位{} 应在{}),取消重算",
                        ticksNotInValid, feet.toShortString(),
                        movement.getSrc().toShortString());
                cancel("身位 " + feet.toShortString() + " 不在路径任何合法位上");
                return false;
            }
        } else {
            ticksNotInValid = 0;
        }
        double distFromPath = closestPathPosDist();
        if (possiblyOffPath(distFromPath, MAX_DIST_FROM_PATH)) {
            ticksAway++;
            if (ticksAway > MAX_TICKS_AWAY) {
                Constants.LOG.debug("离路径太远太久,取消({} tick)", ticksAway);
                cancel("离开路径 " + ticksAway + " tick 未能回归");
                return false;
            }
        } else {
            ticksAway = 0;
        }
        if (possiblyOffPath(distFromPath, MAX_MAX_DIST_FROM_PATH)) {
            Constants.LOG.debug("离路径过远,立即取消");
            cancel("身位离开路径超过 " + (int) MAX_MAX_DIST_FROM_PATH + " 格");
            return false;
        }
        // 对 pathPosition±10 的移动丢弃格集缓存按当前世界重算;任一移动的
        // 格集内容有变即重建"剩余路径全量格集"(可视化与外部查询的数据源)。
        // 读取经"只读已加载"钳制:路径末端伸进未生成地形时不触发同步区块生成
        var level = com.dwinovo.numen.core.pathing.cache.LoadedOnlyView.of(player.level());
        for (int i = pathPosition - 10; i < pathPosition + 10; i++) {
            if (i < 0 || i >= path.movements().size()) {
                continue;
            }
            Movement m = path.movements().get(i);
            List<BlockPos> prevBreak = m.toBreak(level);
            List<BlockPos> prevPlace = m.toPlace(level);
            List<BlockPos> prevWalkInto = m.toWalkInto(level);
            m.resetBlockCache();
            if (!prevBreak.equals(m.toBreak(level))) {
                recalcBP = true;
            }
            if (!prevPlace.equals(m.toPlace(level))) {
                recalcBP = true;
            }
            if (!prevWalkInto.equals(m.toWalkInto(level))) {
                recalcBP = true;
            }
        }
        if (recalcBP) {
            HashSet<BlockPos> newBreak = new HashSet<>();
            HashSet<BlockPos> newPlace = new HashSet<>();
            HashSet<BlockPos> newWalkInto = new HashSet<>();
            for (int i = pathPosition; i < path.movements().size(); i++) {
                Movement m = path.movements().get(i);
                newBreak.addAll(m.toBreak(level));
                newPlace.addAll(m.toPlace(level));
                newWalkInto.addAll(m.toWalkInto(level));
            }
            toBreak = newBreak;
            toPlace = newPlace;
            toWalkInto = newWalkInto;
            recalcBP = false;
        }
        if (pathPosition < path.movements().size() - 1) {
            Movement next = path.movements().get(pathPosition + 1);
            if (!loadedTest.isLoaded(next.getDest().getX(), next.getDest().getZ())) {
                Constants.LOG.debug("下一移动的终点在已加载区块边缘,暂停");
                harness.clearAllKeys();
                return true;
            }
        }
        boolean canCancel = movement.safeToCancel();
        CalculationContext context = contextSupplier.get();
        if (costEstimateIndex == null || costEstimateIndex != pathPosition) {
            // 进入新移动:钉住路径计算时的估价做对照基准,并向前核验
            costEstimateIndex = pathPosition;
            currentMovementOriginalCostEstimate = movement.getCost();
            prepareSupplies(movement);
            int lookahead = NavSettings.get().costVerificationLookahead;
            int deadFuture = firstFutureImpossible(path.movements(), pathPosition, lookahead, context);
            if (deadFuture != -1 && canCancel) {
                Constants.LOG.debug("世界已变化,后续移动不可行,取消");
                cancel("世界已变化," + describe(path.movements().get(deadFuture)) + " 不再可行");
                return true;
            }
        }
        double currentCost = movement.recalculateCost(context);
        if (currentCost >= COST_INF && canCancel) {
            Constants.LOG.debug("世界已变化,当前移动不可行,取消");
            cancel("世界已变化," + describe(movement) + " 不再可行");
            return true;
        }
        if (!movement.calculatedWhileLoaded()
                && costIncreaseExceedsTolerance(currentMovementOriginalCostEstimate, currentCost,
                        NavSettings.get().maxCostIncrease)
                && canCancel) {
            // 只对"当时在未加载区块里估出来的"移动生效:加载后货不对板;
            // 加载时算的涨价属于自己路径的连锁反应,不管
            Constants.LOG.debug("移动估价 {} 涨到 {},取消", currentMovementOriginalCostEstimate, currentCost);
            cancel(describe(movement) + " 加载后估价从 "
                    + (int) (double) currentMovementOriginalCostEstimate + " 涨到 " + (int) currentCost);
            return true;
        }
        if (shouldPause()) {
            Constants.LOG.debug("在飞搜索的最优路径会回头经过脚下,暂停");
            harness.clearAllKeys();
            return true;
        }
        MovementStatus movementStatus = movement.update();
        if (movementStatus == MovementStatus.UNREACHABLE || movementStatus == MovementStatus.FAILED) {
            Constants.LOG.debug("移动报 {},取消", movementStatus);
            cancel(describe(movement) + " 执行报 " + movementStatus);
            return true;
        }
        if (movementStatus == MovementStatus.SUCCESS) {
            pathPosition++;
            recordStep("success ->" + pathPosition + " feet=" + playerFeet(player));
            onChangeInPathPosition();
            onTick0();
            return true;
        } else {
            sprintNextTick = shouldSprintNextTick();
            if (!sprintNextTick) {
                player.setSprinting(false); // 松开按键不会自动停疾跑
            }
            ticksOnCurrent++;
            // 活跃挖掘算真实推进(硬方块一挖几十 tick 是正常工作)
            if (harness.isDigging()) {
                ticksSinceProgress = 0;
            } else {
                ticksSinceProgress++;
            }
            if (ticksOnCurrent > timedOutAt(currentMovementOriginalCostEstimate,
                    NavSettings.get().movementTimeoutTicks)) {
                // 卡死的动作必须留声:类型、起讫、四邻实况、身位一次性摊开。
                // 动作卡住是寻路故障里最常见的一类,而它以前只在 debug 级留痕,
                // 发布态不落盘——线上出问题只能靠猜。这条 INFO 是排障的第一现场。
                Constants.LOG.info(
                        "[numen-path] 动作卡死 {} 耗时{}刻(估价{}) 无进展{}刻 身位{} 精确({}) "
                                + "起点格={} 终点格={} 终点上={} 终点下={}",
                        describe(movement), ticksOnCurrent,
                        (int) (double) currentMovementOriginalCostEstimate, ticksSinceProgress,
                        playerFeet(player).toShortString(),
                        String.format("%.2f,%.2f,%.2f", player.getX(), player.getY(), player.getZ()),
                        blockName(movement.getSrc()), blockName(movement.getDest()),
                        blockName(movement.getDest().above()), blockName(movement.getDest().below()));
                cancel(describe(movement) + " 卡住:耗时 " + ticksOnCurrent
                        + " tick,远超估价 " + (int) (double) currentMovementOriginalCostEstimate);
                return true;
            }
        }
        return canCancel;
    }

    // ==================== 重定位(纯逻辑,可测) ====================

    /** 回退扫:在 [0, pathPosition) 里找第一个合法位含 feet 的移动;无则 -1。 */
    static int findBackwardMatch(List<Movement> movements, int pathPosition, BlockPos feet) {
        for (int i = 0; i < pathPosition && i < movements.size(); i++) {
            if (movements.get(i).getValidPositions().contains(feet)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 前跳扫:在 [pathPosition+3, movements.size()) 里找第一个合法位含
     * feet 的移动;无则 -1。刻意跳过 +1(该移动自己会报 SUCCESS)与 +2。
     */
    static int findForwardSkip(List<Movement> movements, int pathPosition, BlockPos feet) {
        for (int i = pathPosition + 3; i < movements.size(); i++) {
            if (movements.get(i).getValidPositions().contains(feet)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 提前接段判定(纯逻辑):空中且不在液体里不接;严格下沉(竖速
     * < -0.1)不接(可能正穿水下坠);feet 恰在格位序列里 → 返回该
     * 下标,否则 -1。
     */
    static int snipsnapIndex(List<BlockPos> positions, BlockPos feet,
                             boolean onGround, boolean feetInLiquid, double deltaY) {
        if (!onGround && !feetInLiquid) {
            return -1;
        }
        if (deltaY < -0.1) {
            return -1;
        }
        return positions.indexOf(feet);
    }

    // ==================== 成本核验 / 超时(纯逻辑,可测) ====================

    /**
     * 从当前步后一格起向前看 {@code lookahead} 个移动,返回第一个成本
     * {@code >= COST_INF} 的下标;全可行返回 -1。窗口不含当前步与路径
     * 末位(末位无对应移动)。
     */
    static int firstFutureImpossible(List<Movement> movements, int pathPosition,
                                      int lookahead, CalculationContext context) {
        for (int i = 1; i < lookahead && pathPosition + i < movements.size(); i++) {
            Movement future = movements.get(pathPosition + i);
            if (future.calculateCost(context, new MutableMoveResult()) >= COST_INF) {
                return pathPosition + i;
            }
        }
        return -1;
    }

    /** 成本涨幅是否超过容差(仅对猜价移动生效,调用方负责 calculatedWhileLoaded)。 */
    static boolean costIncreaseExceedsTolerance(double originalEstimate, double currentCost,
                                                double maxCostIncrease) {
        return currentCost - originalEstimate > maxCostIncrease;
    }

    /** 单移动超时阈值:估价 tick + 宽限。 */
    static double timedOutAt(double originalCostEstimate, int movementTimeoutTicks) {
        return originalCostEstimate + movementTimeoutTicks;
    }

    // ==================== 脱轨 ====================

    /** 玩家到全路径所有合法位格中心的最小距离。 */
    private double closestPathPosDist() {
        return closestPathPosDist(path.movements(), player.getX(), player.getY(), player.getZ());
    }

    /** 纯逻辑:给定路径与玩家坐标,返回到全路径合法位格中心的最小距离。 */
    static double closestPathPosDist(List<Movement> movements, double px, double py, double pz) {
        double best = -1;
        for (Movement movement : movements) {
            for (BlockPos pos : movement.getValidPositions()) {
                double dist = distanceToCenter(pos, px, py, pz);
                if (dist < best || best == -1) {
                    best = dist;
                }
            }
        }
        return best;
    }

    /**
     * 是否可能脱轨。坠落中同时远离起点与终点属正常,当前移动是坠落时
     * 改用到坠落终点的水平距离判定。
     */
    private boolean possiblyOffPath(double distanceFromPath, double leniency) {
        return possiblyOffPath(path, pathPosition, distanceFromPath, leniency,
                player.getX(), player.getZ(), pos -> entityFlatDistanceToCenter(pos));
    }

    /**
     * 纯逻辑:给定路径、当前下标、玩家到路径最小距离与容差,判定是否
     * 可能脱轨。当前移动是坠落时改用到坠落终点(下标+1)的水平距离。
     * flatDist 按格位算玩家到该格中心的水平距离(坠落终点用)。
     */
    static boolean possiblyOffPath(NavPath path, int pathPosition, double distanceFromPath,
                                    double leniency, double px, double pz,
                                    java.util.function.Function<BlockPos, Double> flatDist) {
        if (distanceFromPath <= leniency) {
            return false;
        }
        if (path.movements().get(pathPosition) instanceof MovementFall) {
            BlockPos fallDest = path.positions().get(pathPosition + 1);
            return flatDist.apply(fallDest) >= leniency;
        }
        return true;
    }

    private double entityDistanceToCenter(BlockPos pos) {
        return distanceToCenter(pos, player.getX(), player.getY(), player.getZ());
    }

    /** 纯逻辑:玩家坐标到格位中心的 3D 距离。 */
    static double distanceToCenter(BlockPos pos, double px, double py, double pz) {
        double dx = pos.getX() + 0.5 - px;
        double dy = pos.getY() + 0.5 - py;
        double dz = pos.getZ() + 0.5 - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double entityFlatDistanceToCenter(BlockPos pos) {
        return flatDistanceToCenter(pos, player.getX(), player.getZ());
    }

    /** 纯逻辑:玩家坐标到格位中心的水平距离。 */
    static double flatDistanceToCenter(BlockPos pos, double px, double pz) {
        double dx = pos.getX() + 0.5 - px;
        double dz = pos.getZ() + 0.5 - pz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ==================== 回头暂停 / 提前接段 ====================

    /**
     * 在飞搜索的最优部分路径(≥3 格,去掉首格后)包含脚下 → 新路径
     * 会经过这里,不必再往前走。仅在站稳、身位通透、当前移动可取消
     * 时生效。
     */
    private boolean shouldPause() {
        Optional<NavPath> currentBest = inProgressBestPath.get();
        if (currentBest.isEmpty()) {
            return false;
        }
        if (!player.onGround()) {
            return false;
        }
        BlockPos feet = playerFeet(player);
        var level = com.dwinovo.numen.core.pathing.cache.LoadedOnlyView.of(player.level());
        if (!MovementHelper.canWalkOn(level, feet.below())) {
            return false; // 站位本身可疑(可能跑酷中),别停
        }
        if (!MovementHelper.canWalkThrough(level, feet)
                || !MovementHelper.canWalkThrough(level, feet.above())) {
            return false; // 身位被埋,别停
        }
        if (!path.movements().get(pathPosition).safeToCancel()) {
            return false;
        }
        List<BlockPos> positions = currentBest.get().positions();
        if (positions.size() < 3) {
            return false; // 太短,远不能确定真会走这条
        }
        return positions.subList(1, positions.size()).contains(feet);
    }

    /** 无论当前推进到哪,身位恰在路径格位上时直接吸附过去。 */
    public boolean snipsnapifpossible() {
        BlockPos feet = playerFeet(player);
        boolean feetInLiquid = !player.level().getFluidState(feet).isEmpty();
        int index = snipsnapIndex(path.positions(), feet,
                player.onGround(), feetInLiquid, player.getDeltaMovement().y);
        if (index == -1) {
            return false;
        }
        pathPosition = index;
        harness.clearAllKeys();
        return true;
    }

    // ==================== 疾跑决策 ====================

    /**
     * 疾跑整体决策。先没收移动原语请求的 SPRINT 键(疾跑由执行器
     * 直接写实体状态,不靠按键),再按当前移动类型与前后文判定。
     */
    private boolean shouldSprintNextTick() {
        boolean requested = harness.isKeyRequested(Input.SPRINT);
        harness.forceKey(Input.SPRINT, false);

        // 与成本模型同判据:允许疾跑且饥饿值足够
        if (!(NavSettings.get().allowSprint
                && (!com.dwinovo.numen.core.task.WorkProfile.of(player).hasHunger()
                        || player.getFoodData().getFoodLevel() > 6))) {
            return false;
        }
        Movement current = path.movements().get(pathPosition);

        // 平走→上台直跳:跳过平走那步,原地起跳直接冲上去
        if (current instanceof MovementTraverse traverse && pathPosition < path.length() - 3) {
            Movement next = path.movements().get(pathPosition + 1);
            if (next instanceof MovementAscend ascend
                    && sprintableAscend(traverse, ascend, path.movements().get(pathPosition + 2))) {
                if (skipNow(current)) {
                    Constants.LOG.debug("跳过平走,直跳上台");
                    pathPosition++;
                    onChangeInPathPosition();
                    onTick0();
                    harness.forceKey(Input.JUMP, true);
                    return true;
                }
            }
        }

        if (requested) {
            return true;
        }

        // 下降与上升不自行请求疾跑(它们不知道后面接什么),在此按前后文补
        if (current instanceof MovementDescend descend) {
            if (pathPosition < path.length() - 2) {
                Movement next = path.movements().get(pathPosition + 1);
                CalculationContext context = contextSupplier.get();
                if (MovementHelper.canUseFrostWalker(context, context.get(next.getDest().below()))) {
                    // 霜行者只在贴地跨过方块边缘时结冰,可能冲过头;下一步
                    // 同向平走/跑酷时强制慢速直进(跑酷且有耗材可放置替代除外)
                    if (next instanceof MovementTraverse || next instanceof MovementParkour) {
                        boolean couldPlaceInstead = NavSettings.get().allowPlace
                                && context.hasThrowaway && next instanceof MovementParkour;
                        boolean sameFlatDirection =
                                !current.getDirection().above().offset(next.getDirection()).equals(BlockPos.ZERO)
                                && current.getDirection().above().cross(next.getDirection()).equals(BlockPos.ZERO);
                        if (sameFlatDirection && !couldPlaceInstead) {
                            descend.forceSafeMode();
                        }
                    }
                }
            }
            if (descend.safeMode() && !descend.skipToAscend()) {
                return false; // 冲下去不安全
            }
            if (pathPosition < path.length() - 2) {
                Movement next = path.movements().get(pathPosition + 1);
                if (next instanceof MovementAscend
                        && current.getDirection().above().equals(next.getDirection().below())) {
                    // V 形:同向下降接上升,直接跳到上升步冲过去
                    pathPosition++;
                    onChangeInPathPosition();
                    onTick0();
                    Constants.LOG.debug("V 形谷,跳过下降直接上升");
                    return true;
                }
                if (canSprintFromDescendInto(current, next)) {
                    if (next instanceof MovementDescend && pathPosition < path.length() - 3) {
                        Movement nextNext = path.movements().get(pathPosition + 2);
                        if (nextNext instanceof MovementDescend
                                && !canSprintFromDescendInto(next, nextNext)) {
                            return false; // 连锁下降的下一环接不上,别开冲
                        }
                    }
                    if (playerFeet(player).equals(current.getDest())) {
                        pathPosition++;
                        onChangeInPathPosition();
                        onTick0();
                    }
                    return true;
                }
            }
        }
        if (current instanceof MovementAscend && pathPosition != 0) {
            Movement prev = path.movements().get(pathPosition - 1);
            if (prev instanceof MovementDescend
                    && prev.getDirection().above().equals(current.getDirection().below())) {
                // V 形谷底:动量还在,高度够了就松跳直接冲上去
                BlockPos center = current.getSrc().above();
                // 0.07 的余量吸收农田/灵魂沙顶面的矮一截
                if (player.position().y >= center.getY() - 0.07) {
                    harness.forceKey(Input.JUMP, false);
                    return true;
                }
            }
            if (pathPosition < path.length() - 2 && prev instanceof MovementTraverse traverse
                    && sprintableAscend(traverse, (MovementAscend) current,
                            path.movements().get(pathPosition + 1))) {
                return true;
            }
        }
        if (current instanceof MovementFall fall) {
            Vec3 overrideTarget = overrideFallTarget(fall);
            if (overrideTarget != null) {
                BlockPos fallDest = overrideFallDest(fall);
                if (!path.positions().contains(fallDest)) {
                    throw new IllegalStateException("坠落前越落点 " + fallDest + " 不在路径上");
                }
                if (playerFeet(player).equals(fallDest)) {
                    pathPosition = path.positions().indexOf(fallDest);
                    onChangeInPathPosition();
                    onTick0();
                    return true;
                }
                // 疾跑冲下坡不减速:清键、直接压目标视线与前进
                harness.clearAllKeys();
                Vec3 eye = player.getEyePosition();
                harness.applyRotation(new MovementState.MovementTarget(
                        MovementHelper.yawTo(eye, overrideTarget),
                        MovementHelper.pitchTo(eye, overrideTarget), false));
                harness.forceKey(Input.MOVE_FORWARD, true);
                return true;
            }
        }
        return false;
    }

    /**
     * 坠落前越:落差 ≤3 且无待挖块时,向后收集 ≤2 个同向平走,整柱
     * 通透且各落脚可站 → 把目标点押到扩展终点略前(len−0.4),冲刺
     * 直接飞过去。返回扩展的瞄准点;不可前越返回 null。
     */
    private Vec3 overrideFallTarget(MovementFall movement) {
        int extension = overrideFallExtension(movement);
        if (extension == 0) {
            return null;
        }
        Vec3i dir = movement.getDirection();
        Vec3i flatDir = new Vec3i(dir.getX(), 0, dir.getZ());
        double len = extension - 0.4;
        BlockPos dest = movement.getDest();
        return new Vec3(flatDir.getX() * len + dest.getX() + 0.5,
                dest.getY(),
                flatDir.getZ() * len + dest.getZ() + 0.5);
    }

    /** 坠落前越的扩展落点(路径上真实存在的格位)。 */
    private BlockPos overrideFallDest(MovementFall movement) {
        int extension = overrideFallExtension(movement);
        Vec3i dir = movement.getDirection();
        return movement.getDest().offset(dir.getX() * extension, 0, dir.getZ() * extension);
    }

    /** 前越可扩展的同向平走步数(0 = 不可前越)。 */
    private int overrideFallExtension(MovementFall movement) {
        Vec3i dir = movement.getDirection();
        if (dir.getY() < -3) {
            return 0;
        }
        var level = com.dwinovo.numen.core.pathing.cache.LoadedOnlyView.of(player.level());
        if (!movement.toBreak(level).isEmpty()) {
            return 0;
        }
        Vec3i flatDir = new Vec3i(dir.getX(), 0, dir.getZ());
        int i;
        outer:
        for (i = pathPosition + 1; i < path.length() - 1 && i < pathPosition + 3; i++) {
            Movement next = path.movements().get(i);
            if (!(next instanceof MovementTraverse)) {
                break;
            }
            if (!flatDir.equals(next.getDirection())) {
                break;
            }
            for (int y = next.getDest().getY(); y <= movement.getSrc().getY() + 1; y++) {
                BlockPos chk = new BlockPos(next.getDest().getX(), y, next.getDest().getZ());
                if (!MovementHelper.fullyPassable(level, chk)) {
                    break outer;
                }
            }
            if (!MovementHelper.canWalkOn(level, next.getDest().below())) {
                break;
            }
        }
        i--;
        return i - pathPosition;
    }

    /** 平走→上台直跳的起跳时机:已对中,且身后头顶通透或已走出足够远。 */
    private boolean skipNow(Movement current) {
        double offTarget = Math.abs(current.getDirection().getX()
                        * (current.getSrc().getZ() + 0.5 - player.position().z))
                + Math.abs(current.getDirection().getZ()
                        * (current.getSrc().getX() + 0.5 - player.position().x));
        if (offTarget > 0.1) {
            return false;
        }
        BlockPos headBonk = current.getSrc().subtract(current.getDirection()).above(2);
        if (MovementHelper.fullyPassable(player.level(), headBonk)) {
            return true;
        }
        // 身后头顶不通:再走出 0.8 才敢跳(免得起跳磕头)
        double flatDist = Math.abs(current.getDirection().getX()
                        * (headBonk.getX() + 0.5 - player.position().x))
                + Math.abs(current.getDirection().getZ()
                        * (headBonk.getZ() + 0.5 - player.position().z));
        return flatDist > 0.8;
    }

    /**
     * 平走接上升可否直跳疾跑:同向共线、两个落脚都可站、上升无待挖块、
     * 起跳柱与前柱三格全通透、头顶两处无危险格。
     */
    private boolean sprintableAscend(MovementTraverse current, MovementAscend next, Movement nextNext) {
        if (!NavSettings.get().sprintAscends) {
            return false;
        }
        if (!current.getDirection().equals(next.getDirection().below())) {
            return false;
        }
        if (nextNext.getDirection().getX() != next.getDirection().getX()
                || nextNext.getDirection().getZ() != next.getDirection().getZ()) {
            return false;
        }
        var level = com.dwinovo.numen.core.pathing.cache.LoadedOnlyView.of(player.level());
        if (!MovementHelper.canWalkOn(level, current.getDest().below())) {
            return false;
        }
        if (!MovementHelper.canWalkOn(level, next.getDest().below())) {
            return false;
        }
        if (!next.toBreak(level).isEmpty()) {
            return false;
        }
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 3; y++) {
                BlockPos chk = current.getSrc().above(y);
                if (x == 1) {
                    chk = chk.offset(current.getDirection());
                }
                if (!MovementHelper.fullyPassable(level, chk)) {
                    return false;
                }
            }
        }
        if (MovementHelper.avoidWalkingInto(level.getBlockState(current.getSrc().above(3)))) {
            return false;
        }
        return !MovementHelper.avoidWalkingInto(level.getBlockState(next.getDest().above(2)));
    }

    /** 下降可否疾跑冲进下一步:同向下降恒可;落点前方可站时同向平走/对角亦可。 */
    private boolean canSprintFromDescendInto(Movement current, Movement next) {
        if (next instanceof MovementDescend && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        if (!MovementHelper.canWalkOn(player.level(),
                current.getDest().offset(current.getDirection()))) {
            return false;
        }
        if (next instanceof MovementTraverse && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        return next instanceof MovementDiagonal && NavSettings.get().allowOvershootDiagonalDescend;
    }

    // ==================== 备货 / 状态迁移 ====================

    /**
     * 进入新移动时的执行期备货:要放置的移动把背包深处的耗材搬进快捷栏
     * (规划期按全背包判有料),超过安全落差且落点无水的坠落备水桶。
     */
    private void prepareSupplies(Movement movement) {
        if (movement.getToPlace() != null) {
            harness.ensureThrowawayInHotbar();
        }
        if (movement instanceof MovementFall
                && movement.getSrc().getY() - movement.getDest().getY() > NavSettings.get().maxFallHeightNoWater
                && !MovementHelper.isWater(player.level().getBlockState(movement.getDest()))) {
            harness.ensureWaterBucketInHotbar();
        }
    }

    private void onChangeInPathPosition() {
        harness.clearAllKeys();
        ticksOnCurrent = 0;
        ticksSinceProgress = 0;
    }

    /** 方块的短名(排障日志用)。 */
    private String blockName(BlockPos pos) {
        return player.level().getBlockState(pos).getBlock()
                .builtInRegistryHolder().key().identifier().getPath();
    }

    /** 移动的人话描述(失败原因素材):类型 + 起讫格。 */
    private static String describe(Movement movement) {
        return movement.getClass().getSimpleName()
                + " " + movement.getSrc().toShortString()
                + " -> " + movement.getDest().toShortString();
    }

    /** 取消:记录原因、清键、停挖、把推进下标押出末尾并标失败。 */
    private void cancel(String cause) {
        failureCause = cause;
        harness.clearAllKeys();
        harness.stopBreaking();
        pathPosition = path.length() + 3;
        failed = true;
    }

    // ==================== 拼接 / 历史裁剪 ====================

    /**
     * 把下一段拼进当前路径(保持推进状态);无法拼接或无下一段时,
     * 已执行历史超限则裁掉开头一截。
     */
    public PathExecutor trySplice(PathExecutor next) {
        if (next == null) {
            return cutIfTooLong();
        }
        return SplicedPath.trySplice(path, next.path, false).map(spliced -> {
            if (!spliced.getDest().equals(next.getPath().getDest())) {
                throw new IllegalStateException(
                        "拼接后终点 " + spliced.getDest() + " 与下一段终点 " + next.getPath().getDest() + " 不符");
            }
            PathExecutor ret = newWithSamePlumbing(spliced);
            ret.pathPosition = pathPosition;
            ret.currentMovementOriginalCostEstimate = currentMovementOriginalCostEstimate;
            ret.costEstimateIndex = costEstimateIndex;
            ret.ticksOnCurrent = ticksOnCurrent;
            ret.ticksSinceProgress = ticksSinceProgress;
            return ret;
        }).orElseGet(this::cutIfTooLong);
    }

    private PathExecutor cutIfTooLong() {
        if (pathPosition > NavSettings.get().maxPathHistoryLength) {
            int cutoffAmt = NavSettings.get().pathHistoryCutoffAmount;
            CutoffPath newPath = new CutoffPath(path, cutoffAmt, path.length() - 1);
            if (!newPath.getDest().equals(path.getDest())) {
                throw new IllegalStateException("裁剪历史后终点变了:" + newPath.getDest() + " != " + path.getDest());
            }
            Constants.LOG.debug("已执行历史过长,路径从 {} 裁到 {}", path.length(), newPath.length());
            PathExecutor ret = newWithSamePlumbing(newPath);
            ret.pathPosition = pathPosition - cutoffAmt;
            ret.currentMovementOriginalCostEstimate = currentMovementOriginalCostEstimate;
            if (costEstimateIndex != null) {
                ret.costEstimateIndex = costEstimateIndex - cutoffAmt;
            }
            ret.ticksOnCurrent = ticksOnCurrent;
            ret.ticksSinceProgress = ticksSinceProgress;
            return ret;
        }
        return this;
    }

    private PathExecutor newWithSamePlumbing(NavPath newPath) {
        return new PathExecutor(newPath, player, harness, contextSupplier, inProgressBestPath, loadedTest);
    }

    // ==================== 只读面 ====================

    public int getPosition() {
        return pathPosition;
    }

    public NavPath getPath() {
        return path;
    }

    public Goal getGoal() {
        return path.getGoal();
    }

    public boolean failed() {
        return failed;
    }

    /** 失败原因(人话);未失败为 null。 */
    public String failureCause() {
        return failureCause;
    }

    public boolean finished() {
        return pathPosition >= path.length();
    }

    /** 距上次真实推进(移动完成/重定位/活跃挖掘)的 tick 数。 */
    /** 卡在哪一步、哪种动作上(排障用):第几步/共几步 + 动作类型 + 起讫 + 无进展刻。 */
    public String progressSummary() {
        if (pathPosition >= path.movements().size()) {
            return pathPosition + "/" + path.length() + " 已走完";
        }
        Movement m = path.movements().get(pathPosition);
        var view = com.dwinovo.numen.core.pathing.cache.LoadedOnlyView.of(player.level());
        StringBuilder toBreak = new StringBuilder();
        for (BlockPos p : m.toBreak(view)) {
            toBreak.append(p.toShortString()).append('=')
                    .append(player.level().getBlockState(p).getBlock().builtInRegistryHolder()
                            .key().identifier().getPath()).append(' ');
        }
        return pathPosition + "/" + path.length() + " " + m.getClass().getSimpleName()
                + " " + m.getSrc().toShortString() + "->" + m.getDest().toShortString()
                + " 无进展" + ticksSinceProgress + "刻 身位" + playerFeet(player).toShortString()
                + " 起点格=" + blockName(m.getSrc()) + " 起点下=" + blockName(m.getSrc().below())
                + " 终点格=" + blockName(m.getDest())
                + " 待挖[" + toBreak.toString().trim() + "]";
    }

    public int ticksSinceProgress() {
        return ticksSinceProgress;
    }

    /** 本 tick 执行器的疾跑决策结果(由拥有者落到实体上)。 */
    public boolean isSprinting() {
        return sprintNextTick;
    }

    /** 剩余路径将要挖穿的全部格(只读)。 */
    public Set<BlockPos> toBreak() {
        return Collections.unmodifiableSet(toBreak);
    }

    /** 剩余路径将要放方块的全部格(只读)。 */
    public Set<BlockPos> toPlace() {
        return Collections.unmodifiableSet(toPlace);
    }

    /** 剩余路径将要挤身而过的全部格(只读)。 */
    public Set<BlockPos> toWalkInto() {
        return Collections.unmodifiableSet(toWalkInto);
    }
}
