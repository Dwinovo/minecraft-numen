package com.dwinovo.numen.core.pathing.astar;

import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.ActionCosts;

/**
 * 搜索节点:坐标、到目标的启发式(构造时算一次并缓存)、
 * 起点到此的实际成本与二者之和,以及回链与堆内位置。
 */
public final class PathNode {

    public final int x;
    public final int y;
    public final int z;

    /** 缓存的 goal.heuristic(x,y,z);NaN 视为目标实现的 bug,直接抛。 */
    public final double estimatedCostToGoal;

    /** 起点到此的实际成本,初始 INF,由搜索循环松弛改写。 */
    public double cost;

    /** 恒等于 cost + estimatedCostToGoal,堆按它排序。 */
    public double combinedCost;

    /** 松弛出本节点成本的前驱。 */
    public PathNode previous;

    /** 在二叉堆数组里的下标;-1 表示不在 open set(decrease-key 用)。 */
    public int heapPosition;

    public PathNode(int x, int y, int z, Goal goal) {
        this.previous = null;
        this.cost = ActionCosts.COST_INF;
        this.estimatedCostToGoal = goal.heuristic(x, y, z);
        if (Double.isNaN(estimatedCostToGoal)) {
            throw new IllegalStateException(
                    goal + " 在 (" + x + "," + y + "," + z + ") 算出了 NaN 启发式");
        }
        this.heapPosition = -1;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public boolean isOpen() {
        return heapPosition != -1;
    }

    /** 坐标混合哈希(与节点表的 long 键同源)。 */
    public static long longHash(int x, int y, int z) {
        long hash = 3241;
        hash = 3457689L * hash + x;
        hash = 8734625L * hash + y;
        hash = 2873465L * hash + z;
        return hash;
    }

    @Override
    public int hashCode() {
        return (int) longHash(x, y, z);
    }

    @Override
    public boolean equals(Object obj) {
        // 热路径:只与同类比较,跳过类型检查
        final PathNode other = (PathNode) obj;
        return x == other.x && y == other.y && z == other.z;
    }
}
