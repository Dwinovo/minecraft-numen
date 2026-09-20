package com.dwinovo.numen.core.pathing.astar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.ActionCosts;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.Moves;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;

import net.minecraft.core.BlockPos;

/**
 * 搜索直接产出的路径:终点节点沿 previous 回链、反转得 position 序列。
 * postProcess 一次性装配相邻格间的移动原语并钉住执行期对照成本。
 */
public class Path extends PathBase {

    private final BlockPos start;
    private final BlockPos end;

    /** 途经格位;首元素恒等于 start,末元素恒等于 end。 */
    private final List<BlockPos> path;

    private final List<Movement> movements;

    private final List<PathNode> nodes;

    private final Goal goal;

    private final int numNodes;

    private final CalculationContext context;

    /** postProcess 是否已执行(只许一次;movements 只在其后可读)。 */
    private volatile boolean verified;

    /**
     * @param realStart 玩家真实脚位。与 A* 起点不同且路径退化为单节点时,
     *                  插一个 cost=0 的假起点,使得仍能装配出一个移动。
     */
    Path(BlockPos realStart, PathNode start, PathNode end, int numNodes,
         Goal goal, CalculationContext context) {
        this.end = new BlockPos(end.x, end.y, end.z);
        this.numNodes = numNodes;
        this.movements = new ArrayList<>();
        this.goal = goal;
        this.context = context;

        PathNode current = end;
        List<BlockPos> tempPath = new ArrayList<>();
        List<PathNode> tempNodes = new ArrayList<>();
        while (current != null) {
            tempNodes.add(current);
            tempPath.add(new BlockPos(current.x, current.y, current.z));
            current = current.previous;
        }

        // 起点与真实脚位不同且路径退化(start==end,无一条边)时,补假起点:
        // 回链方向是终点到起点,追加在末尾,反转后即成首格。
        BlockPos startNodePos = new BlockPos(start.x, start.y, start.z);
        if (!realStart.equals(startNodePos) && start.equals(end)) {
            this.start = realStart;
            PathNode fakeNode = new PathNode(realStart.getX(), realStart.getY(), realStart.getZ(), goal);
            fakeNode.cost = 0;
            tempNodes.add(fakeNode);
            tempPath.add(realStart);
        } else {
            this.start = startNodePos;
        }

        // 回链是终点到起点的倒序,反转成执行序
        Collections.reverse(tempPath);
        Collections.reverse(tempNodes);
        this.path = tempPath;
        this.nodes = tempNodes;
    }

    @Override
    public Goal getGoal() {
        return goal;
    }

    /**
     * 逐对相邻格位装配移动原语。
     *
     * @return true 表示装配中断(某一对已找不到可行移动,世界在计算期间变了)
     */
    private boolean assembleMovements() {
        if (path.isEmpty() || !movements.isEmpty()) {
            throw new IllegalStateException("路径为空或已装配过");
        }
        for (int i = 0; i < path.size() - 1; i++) {
            BlockPos src = path.get(i);
            BlockPos dest = path.get(i + 1);
            PathNode to = nodes.get(i + 1);
            Moves moves = to.previousMove;
            double cost = to.previousMoveCost;
            if (moves == null) {
                // 只有假起点那条边会走到这儿(真实脚位与 A* 起点不同、路径又退化成单节点时补的),
                // 搜索从没展开过它。问的仍是搜索自己的成本函数,不是另写一套判据。
                Movement fromStart = resolveSyntheticStart(src, dest);
                if (fromStart == null) {
                    return true;
                }
                movements.add(fromStart);
                continue;
            }
            movements.add(price(moves.build(context, src, dest), cost));
        }
        return false;
    }

    /** 钉住搜索算出的原价:执行期比"贵了多少"时,两边都是原价才比得了。 */
    private static Movement price(Movement move, double cost) {
        move.override(cost);
        return move;
    }

    /**
     * 假起点那条边:找出从真实脚位出发、落点恰为下一格的原语。
     *
     * <p>用的是搜索展开邻边时的同一个 {@link Moves#apply},所以"这条边是什么、值多少"
     * 依然只有一处说法;这里只是替搜索补算它从没展开过的那一条。
     */
    private Movement resolveSyntheticStart(BlockPos src, BlockPos dest) {
        MutableMoveResult res = new MutableMoveResult();
        for (Moves moves : Moves.values()) {
            res.reset();
            moves.apply(context, src.getX(), src.getY(), src.getZ(), res);
            if (res.cost < ActionCosts.COST_INF
                    && res.x == dest.getX() && res.y == dest.getY() && res.z == dest.getZ()) {
                return price(moves.build(context, src, dest), res.cost);
            }
        }
        Constants.LOG.debug("真实脚位 {} 接不上搜索起点 {}", src, dest);
        return null;
    }

    @Override
    public NavPath postProcess() {
        if (verified) {
            throw new IllegalStateException("路径不可重复 postProcess");
        }
        verified = true;
        boolean failed = assembleMovements();
        movements.forEach(m -> m.checkLoadedChunk(context));

        if (failed) {
            // 至少一个移动在计算期间变得不可行:截到已装配好的部分
            CutoffPath res = new CutoffPath(this, movements().size());
            if (res.movements().size() != movements.size()) {
                throw new IllegalStateException("截断后长度不符");
            }
            return res;
        }
        sanityCheck();
        return this;
    }

    @Override
    public List<Movement> movements() {
        if (!verified) {
            throw new IllegalStateException("路径尚未 postProcess");
        }
        return Collections.unmodifiableList(movements);
    }

    @Override
    public List<BlockPos> positions() {
        return Collections.unmodifiableList(path);
    }

    @Override
    public int getNumNodesConsidered() {
        return numNodes;
    }

    @Override
    public BlockPos getSrc() {
        return start;
    }

    @Override
    public BlockPos getDest() {
        return end;
    }
}
