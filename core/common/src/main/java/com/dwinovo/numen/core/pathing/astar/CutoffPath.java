package com.dwinovo.numen.core.pathing.astar;

import java.util.Collections;
import java.util.List;

import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.Movement;

import net.minecraft.core.BlockPos;

/** 截断路径:原路径按下标区间截取的副本(段尾质量差时砍掉尾部)。 */
public class CutoffPath extends PathBase {

    private final List<BlockPos> path;

    private final List<Movement> movements;

    private final int numNodes;

    private final Goal goal;

    /**
     * 截取 [firstPositionToInclude, lastPositionToInclude] 闭区间的格位
     * 及其间的移动。
     */
    public CutoffPath(NavPath prev, int firstPositionToInclude, int lastPositionToInclude) {
        path = List.copyOf(prev.positions().subList(firstPositionToInclude, lastPositionToInclude + 1));
        movements = List.copyOf(prev.movements().subList(firstPositionToInclude, lastPositionToInclude));
        numNodes = prev.getNumNodesConsidered();
        goal = prev.getGoal();
        sanityCheck();
    }

    /** 从头截到 lastPositionToInclude(含)。 */
    public CutoffPath(NavPath prev, int lastPositionToInclude) {
        this(prev, 0, lastPositionToInclude);
    }

    @Override
    public Goal getGoal() {
        return goal;
    }

    @Override
    public List<Movement> movements() {
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
}
