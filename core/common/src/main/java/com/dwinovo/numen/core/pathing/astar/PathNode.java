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

    /** cost + estimatedCostToGoal,堆按它排序;目标格出堆时补上到达价({@code Goal#arrivalCost})再放回。 */
    public double combinedCost;

    /** 松弛出本节点成本的前驱。 */
    public PathNode previous;

    /**
     * 走到本节点用的那个移动原语,与 {@link #previous} 成对。
     *
     * <p>搜索展开这条边时就知道是哪个原语,记下来,装配路径时直接取——不记的话装配只能
     * 枚举全部原语、拿落点去猜哪个是它,那就成了同一件事的第二处说法。起点没有入边,为 null。
     */
    public com.dwinovo.numen.core.pathing.moves.Moves previousMove;

    /**
     * 那条边的动作价,<b>搜索算出来的原价</b>(未叠 favoring 折扣、未加站位价)。
     *
     * <p>执行期拿它当"这个动作比规划时贵了多少"的基准,而执行期重算出来的也是原价,
     * 两边同口径才比得了。叠过折扣的 g 值差不是这个数。
     */
    public double previousMoveCost;

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
