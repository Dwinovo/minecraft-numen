package com.dwinovo.numen.core.pathing.astar;

import java.util.Optional;

import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.Moves;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;
import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.border.WorldBorder;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;

/**
 * A* 主循环:遍历 22 个移动原语产边、favoring 修正动作成本、
 * 带最小改进阈值的松弛、chunk 边界计数与双轨墙钟超时。
 */
public final class AStarPathFinder extends AbstractNodeCostSearch {

    private final Favoring favoring;
    private final CalculationContext calcContext;

    /** 世界边界快照(构造时取样,worker 线程只读)。 */
    private final double borderMinX;
    private final double borderMaxX;
    private final double borderMinZ;
    private final double borderMaxZ;

    public AStarPathFinder(BlockPos realStart, int startX, int startY, int startZ,
                           Goal goal, Favoring favoring, CalculationContext context) {
        super(realStart, startX, startY, startZ, goal, context);
        this.favoring = favoring;
        this.calcContext = context;
        WorldBorder border = context.player.level().getWorldBorder();
        this.borderMinX = border.getMinX();
        this.borderMaxX = border.getMaxX();
        this.borderMinZ = border.getMinZ();
        this.borderMaxZ = border.getMaxZ();
    }

    /** (x,z) 格是否整格落在世界边界内。 */
    private boolean borderEntirelyContains(int x, int z) {
        return x + 1 > borderMinX && x < borderMaxX && z + 1 > borderMinZ && z < borderMaxZ;
    }

    @Override
    protected Optional<NavPath> calculate0(long primaryTimeout, long failureTimeout) {
        startNode = getNodeAtPosition(startX, startY, startZ, PathNode.longHash(startX, startY, startZ));
        startNode.cost = 0;
        startNode.combinedCost = startNode.estimatedCostToGoal;
        BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
        openSet.insert(startNode);
        // 七档各自追踪 estimatedCostToGoal + cost/COEFFICIENTS[i] 的最小值
        double[] bestHeuristicSoFar = new double[COEFFICIENTS.length];
        for (int i = 0; i < bestHeuristicSoFar.length; i++) {
            bestHeuristicSoFar[i] = startNode.estimatedCostToGoal;
            bestSoFar[i] = startNode;
        }
        MutableMoveResult res = new MutableMoveResult();
        long startTime = System.currentTimeMillis();
        long primaryTimeoutTime = startTime + primaryTimeout;
        long failureTimeoutTime = startTime + failureTimeout;
        // failing:尚无距起点 5 格以上的可用部分路径。找到之前烧满
        // failureTimeout,找到之后只跑 primaryTimeout。
        boolean failing = true;
        int numNodes = 0;
        int numEmptyChunk = 0;
        boolean isFavoring = !favoring.isEmpty();
        int timeCheckInterval = 1 << 6;
        // 循环前取样全部设置:计算中途改设置不改变本次搜索的行为
        int pathingMaxChunkBorderFetch = NavSettings.get().pathingMaxChunkBorderFetch;
        double minimumImprovement = NavSettings.get().minimumImprovementRepropagation ? MIN_IMPROVEMENT : 0;
        Moves[] allMoves = Moves.values();
        while (!openSet.isEmpty() && numEmptyChunk < pathingMaxChunkBorderFetch && !cancelRequested) {
            if ((numNodes & (timeCheckInterval - 1)) == 0) { // 每 64 节点查一次墙钟(约半毫秒)
                long now = System.currentTimeMillis();
                if (now - failureTimeoutTime >= 0 || (!failing && now - primaryTimeoutTime >= 0)) {
                    break;
                }
            }
            PathNode currentNode = openSet.removeLowest();
            mostRecentConsidered = currentNode;
            numNodes++;
            if (goal.isInGoal(currentNode.x, currentNode.y, currentNode.z)) {
                return Optional.of(new Path(realStart, startNode, currentNode, numNodes, goal, calcContext));
            }
            for (Moves moves : allMoves) {
                int newX = currentNode.x + moves.xOffset;
                int newZ = currentNode.z + moves.zOffset;
                if ((newX >> 4 != currentNode.x >> 4 || newZ >> 4 != currentNode.z >> 4)
                        && !calcContext.isLoaded(newX, newZ)) {
                    // 跨 chunk 且未加载:跳过;dynamicXZ 的落点未必真到那格,不计数
                    if (!moves.dynamicXZ) {
                        numEmptyChunk++;
                    }
                    continue;
                }
                if (!moves.dynamicXZ && !borderEntirelyContains(newX, newZ)) {
                    continue;
                }
                if (currentNode.y + moves.yOffset > calcContext.worldHeight
                        || currentNode.y + moves.yOffset < calcContext.worldBottom) {
                    continue;
                }
                res.reset();
                moves.apply(calcContext, currentNode.x, currentNode.y, currentNode.z, res);
                double actionCost = res.cost;
                if (actionCost >= COST_INF) {
                    continue;
                }
                if (actionCost <= 0 || Double.isNaN(actionCost)) {
                    throw new IllegalStateException(String.format(
                            "%s 从 (%d,%d,%d) 算出了非法成本 %s",
                            moves, currentNode.x, currentNode.y, currentNode.z, actionCost));
                }
                // 动态落点只能在成本有效后校验(INF 时落点未写)
                if (moves.dynamicXZ && !borderEntirelyContains(res.x, res.z)) {
                    continue;
                }
                if (!moves.dynamicXZ && (res.x != newX || res.z != newZ)) {
                    throw new IllegalStateException(String.format(
                            "%s 从 (%d,%d,%d) 落到了 (%d,·,%d) 而不是 (%d,·,%d)",
                            moves, currentNode.x, currentNode.y, currentNode.z, res.x, res.z, newX, newZ));
                }
                if (!moves.dynamicY && res.y != currentNode.y + moves.yOffset) {
                    throw new IllegalStateException(String.format(
                            "%s 从 (%d,%d,%d) 落到了 y=%d 而不是 y=%d",
                            moves, currentNode.x, currentNode.y, currentNode.z, res.y,
                            currentNode.y + moves.yOffset));
                }
                long hashCode = PathNode.longHash(res.x, res.y, res.z);
                if (isFavoring) {
                    // 折扣乘在动作成本上,按目的格哈希
                    actionCost *= favoring.calculate(hashCode);
                }
                PathNode neighbor = getNodeAtPosition(res.x, res.y, res.z, hashCode);
                double tentativeCost = currentNode.cost + actionCost;
                if (neighbor.cost - tentativeCost > minimumImprovement) {
                    neighbor.previous = currentNode;
                    neighbor.cost = tentativeCost;
                    neighbor.combinedCost = tentativeCost + neighbor.estimatedCostToGoal;
                    if (neighbor.isOpen()) {
                        openSet.update(neighbor);
                    } else {
                        openSet.insert(neighbor);
                    }
                    for (int i = 0; i < COEFFICIENTS.length; i++) {
                        double heuristic = neighbor.estimatedCostToGoal + neighbor.cost / COEFFICIENTS[i];
                        if (bestHeuristicSoFar[i] - heuristic > minimumImprovement) {
                            bestHeuristicSoFar[i] = heuristic;
                            bestSoFar[i] = neighbor;
                            if (failing && getDistFromStartSq(neighbor) > MIN_DIST_PATH * MIN_DIST_PATH) {
                                failing = false;
                            }
                        }
                    }
                }
            }
        }
        if (cancelRequested) {
            return Optional.empty();
        }
        return bestSoFar(true, numNodes);
    }
}
