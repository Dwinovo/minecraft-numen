package com.dwinovo.numen.core.pathing.astar;

import java.util.Optional;

import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.Moves;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;
import com.dwinovo.numen.core.Constants;
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
        // 按位置的代价表:踩(stand)叠到落点,穿(pass)叠到身体占的两格——规划查询给上一条路的
        // 格子加价出备选就靠它;FORBID 的格早在移动原语的可站/可穿判定里排除了
        com.dwinovo.numen.core.pathing.spec.PositionCosts positions = calcContext.spec.positions();
        boolean hasPositional = !positions.isEmpty();
        int timeCheckInterval = 1 << 6;
        // 循环前取样全部设置:计算中途改设置不改变本次搜索的行为
        int pathingMaxChunkBorderFetch = NavSettings.get().pathingMaxChunkBorderFetch;
        double minimumImprovement = NavSettings.get().minimumImprovementRepropagation ? MIN_IMPROVEMENT : 0;
        // 节点硬上限:与时间预算同为循环出口,谁先到谁停。命中后落到下方 bestSoFar(...) 返回,
        // 与超时收尾完全一致(有可用半程即 SEGMENT,否则 FAILURE)。见 NavSettings#maxNodesPerSearch。
        int maxNodes = NavSettings.get().maxNodesPerSearch;
        Moves[] allMoves = Moves.values();
        while (!openSet.isEmpty() && numNodes < maxNodes && numEmptyChunk < pathingMaxChunkBorderFetch && !cancelRequested) {
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
                // 到达价:停在这一格还要再付的价钱。出堆时的键只是乐观下界,把到达价补上放回堆里,
                // 等它按总价再次出堆才收——更便宜的终点(近处野树之于远处主人的原木)先出堆就先收。
                // 放回去的同时照常往外展开:终点格也是过路格,起点落在一块贵的站位里时,
                // 不展开就一步也走不出去,只能收下起点。
                double total = currentNode.cost
                        + goal.arrivalCost(currentNode.x, currentNode.y, currentNode.z);
                if (total > currentNode.combinedCost) {
                    currentNode.combinedCost = total;
                    openSet.insert(currentNode);
                } else {
                    return Optional.of(new Path(realStart, startNode, currentNode, numNodes, goal, calcContext));
                }
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
                if (hasPositional) {
                    // 踩叠在落点(脚下那格);穿叠在身体占的两格——脚与头。与 CellClass 里
                    // 硬禁的口径一致:那边对每个身体格都查 pass,软代价不能只算脚不算头,
                    // 否则一格头高的工地格拦不住她从下面钻过去。
                    long feet = BlockPos.asLong(res.x, res.y, res.z);
                    long head = BlockPos.asLong(res.x, res.y + 1, res.z);
                    actionCost += positions.stand(feet) + positions.pass(feet) + positions.pass(head);
                }
                PathNode neighbor = getNodeAtPosition(res.x, res.y, res.z, hashCode);
                double tentativeCost = currentNode.cost + actionCost;
                if (neighbor.cost - tentativeCost > minimumImprovement) {
                    neighbor.previous = currentNode;
                    // 这条边是哪个原语走出来的、原价多少,就在这里记下——装配路径时直接取,
                    // 不必再拿落点去猜(猜就是同一件事的第二处说法)
                    neighbor.previousMove = moves;
                    neighbor.previousMoveCost = res.cost;
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
        if (NavSettings.get().profile) {
            // Why did the loop stop? nodeCap = hit maxNodesPerSearch (gave up early — cap may be too low
            // for this terrain); exhausted = openSet emptied (genuinely no reachable path); timeout = time
            // budget; chunkBorder = ran into unloaded chunks. failing=true means no usable partial found
            // (this returns a FAILURE); failing=false means a best partial segment is returned.
            String reason = numNodes >= maxNodes ? "nodeCap"
                    : numEmptyChunk >= pathingMaxChunkBorderFetch ? "chunkBorder"
                    : openSet.isEmpty() ? "exhausted"
                    : "timeout";
            Constants.LOG.info("[nav-search] stop reason={} nodes={}/{} failing={} goal={}",
                    reason, numNodes, maxNodes, failing, goal);
        }
        return bestSoFar(true, numNodes);
    }
}
