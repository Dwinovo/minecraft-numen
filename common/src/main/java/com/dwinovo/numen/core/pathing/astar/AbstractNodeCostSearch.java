package com.dwinovo.numen.core.pathing.astar;

import java.util.Optional;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.settings.NavSettings;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;

/**
 * 按成本回链记录节点的搜索器公共骨架:节点表、七档 bestSoFar 部分
 * 路径候选、协作取消、结果后处理管线。实例一次性,不可复用。
 *
 * <p>线程模型:calculate 在 worker 线程跑,只读冻结的
 * {@link CalculationContext};取消位 volatile,任何线程可置。
 * {@link #bestPathSoFar()} 与 {@link #pathToMostRecentNodeConsidered()}
 * 供执行层在计算进行中窥视(回头暂停判定),读到的是近似快照。
 */
public abstract class AbstractNodeCostSearch {

    /**
     * 七档启发式松紧系数。追踪 estimatedCostToGoal + cost/系数 的最小值:
     * 系数越小越接近真实 A* 最优,越大越激进地朝目标扑(哪怕路径质量差)。
     */
    protected static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10};

    /** 部分路径至少要离起点这么多格才值得采用。 */
    protected static final double MIN_DIST_PATH = 5;

    /**
     * 松弛与 bestSoFar 更新的最小改进阈值:平地上斜走与直走的组合会
     * 产生 1e-16 量级的浮点差,不值得为此更新成本、堆调整、重传播。
     */
    protected static final double MIN_IMPROVEMENT = 0.01;

    /** 玩家真实脚位(与 A* 起点可能不同,见 Path 的假起点规则)。 */
    protected final BlockPos realStart;
    protected final int startX;
    protected final int startY;
    protected final int startZ;

    protected final Goal goal;

    private final CalculationContext context;

    /** 坐标混合哈希 → 节点,惰性建。 */
    private final Long2ObjectOpenHashMap<PathNode> map;

    protected PathNode startNode;

    /** 最近弹出考虑的节点(执行层回头暂停判定要用)。 */
    protected volatile PathNode mostRecentConsidered;

    /** 七档各自的最优候选节点。 */
    protected final PathNode[] bestSoFar = new PathNode[COEFFICIENTS.length];

    private volatile boolean isFinished;

    /** 协作取消位:主循环按此退出。 */
    protected volatile boolean cancelRequested;

    protected AbstractNodeCostSearch(BlockPos realStart, int startX, int startY, int startZ,
                                     Goal goal, CalculationContext context) {
        this.realStart = realStart;
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.goal = goal;
        this.context = context;
        NavSettings settings = NavSettings.get();
        this.map = new Long2ObjectOpenHashMap<>(settings.pathingMapDefaultSize, settings.pathingMapLoadFactor);
    }

    /** 请求取消;主循环在下一轮迭代协作退出。 */
    public void cancel() {
        cancelRequested = true;
    }

    /**
     * 计算模板:跑 {@link #calculate0} → postProcess 装配 →
     * 加载边界截断(默认关)→ 部分路径截尾 → 按终点是否入目标分类。
     *
     * @param primaryTimeout 已有可用部分路径时的预算(毫秒)
     * @param failureTimeout 毫无可用结果时烧满的预算(毫秒)
     */
    public synchronized PathCalcResult calculate(long primaryTimeout, long failureTimeout) {
        if (isFinished) {
            throw new IllegalStateException("搜索器一次性,不可复用");
        }
        cancelRequested = false;
        try {
            NavPath path = calculate0(primaryTimeout, failureTimeout)
                    .map(NavPath::postProcess).orElse(null);
            if (cancelRequested) {
                return new PathCalcResult(PathCalcResult.Type.CANCELLATION);
            }
            if (path == null) {
                return new PathCalcResult(PathCalcResult.Type.FAILURE);
            }
            path = path.cutoffAtLoadedChunks(context.loadedTest);
            path = path.staticCutoff(goal);
            BlockPos dest = path.getDest();
            if (goal.isInGoal(dest.getX(), dest.getY(), dest.getZ())) {
                return new PathCalcResult(PathCalcResult.Type.SUCCESS_TO_GOAL, path);
            }
            return new PathCalcResult(PathCalcResult.Type.SUCCESS_SEGMENT, path);
        } catch (Exception e) {
            Constants.LOG.error("路径计算异常", e);
            return new PathCalcResult(PathCalcResult.Type.EXCEPTION);
        } finally {
            // 无论 calculate0 是否抛异常都封存本实例
            isFinished = true;
        }
    }

    protected abstract Optional<NavPath> calculate0(long primaryTimeout, long failureTimeout);

    /** 节点到起点的距离平方(只用于比较,不开方)。 */
    protected double getDistFromStartSq(PathNode n) {
        int xDiff = n.x - startX;
        int yDiff = n.y - startY;
        int zDiff = n.z - startZ;
        return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff;
    }

    /**
     * 按哈希查节点,不存在则新建入表(构造时即算好启发式,NaN 抛)。
     *
     * @param hashCode 由 {@link PathNode#longHash(int, int, int)} 提供
     */
    protected PathNode getNodeAtPosition(int x, int y, int z, long hashCode) {
        PathNode node = map.get(hashCode);
        if (node == null) {
            node = new PathNode(x, y, z, goal);
            map.put(hashCode, node);
        }
        return node;
    }

    /** 到最近考虑节点的路径(渲染/诊断用)。 */
    public Optional<NavPath> pathToMostRecentNodeConsidered() {
        PathNode node = mostRecentConsidered;
        if (node == null || startNode == null) {
            return Optional.empty();
        }
        return Optional.of(new Path(realStart, startNode, node, 0, goal, context));
    }

    /** 当前最优部分路径(执行层回头暂停判定要用)。 */
    public Optional<NavPath> bestPathSoFar() {
        return bestSoFar(false, 0);
    }

    /**
     * 七档判据:按系数从紧到松,取第一个离起点超过
     * {@link #MIN_DIST_PATH} 格的非空档构造路径;七档都不够远
     * 说明确实无路可走,返回 empty。
     */
    protected Optional<NavPath> bestSoFar(boolean logInfo, int numNodes) {
        if (startNode == null) {
            return Optional.empty();
        }
        double bestDist = 0;
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            if (bestSoFar[i] == null) {
                continue;
            }
            double dist = getDistFromStartSq(bestSoFar[i]);
            if (dist > bestDist) {
                bestDist = dist;
            }
            if (dist > MIN_DIST_PATH * MIN_DIST_PATH) { // 距离平方与阈值平方比较
                if (logInfo && COEFFICIENTS[i] >= 3) {
                    Constants.LOG.debug("bestSoFar 采用系数 {},路径质量可能很差", COEFFICIENTS[i]);
                }
                return Optional.of(new Path(realStart, startNode, bestSoFar[i], numNodes, goal, context));
            }
        }
        if (logInfo) {
            Constants.LOG.debug("最松档也只走出 {} 格,判无路", Math.sqrt(bestDist));
        }
        return Optional.empty();
    }

    public final boolean isFinished() {
        return isFinished;
    }

    public final Goal getGoal() {
        return goal;
    }

    /** A* 展开用的起点(可能与 realStart 不同)。 */
    public BlockPos getStart() {
        return new BlockPos(startX, startY, startZ);
    }

    protected int mapSize() {
        return map.size();
    }
}
