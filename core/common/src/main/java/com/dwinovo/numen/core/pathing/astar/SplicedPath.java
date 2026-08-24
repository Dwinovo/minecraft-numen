package com.dwinovo.numen.core.pathing.astar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.Movement;

import net.minecraft.core.BlockPos;

/** 拼接路径:首尾相接的两段合成一条,供执行器跨段无缝续跑。 */
public class SplicedPath extends PathBase {

    private final List<BlockPos> path;

    private final List<Movement> movements;

    private final int numNodes;

    private final Goal goal;

    private SplicedPath(List<BlockPos> path, List<Movement> movements, int numNodesConsidered, Goal goal) {
        this.path = path;
        this.movements = movements;
        this.numNodes = numNodesConsidered;
        this.goal = goal;
        sanityCheck();
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

    @Override
    public int length() {
        return path.size();
    }

    /**
     * 尝试把 first 与 second 拼成一条:要求 first 的终点等于 second 的
     * 起点。first 除末元素外的任何格位若也出现在 second 里(两段重叠),
     * 且不允许重叠截断,则拼接失败——执行器的路径必须严格首尾相接。
     */
    public static Optional<SplicedPath> trySplice(NavPath first, NavPath second, boolean allowOverlapCutoff) {
        if (second == null || first == null) {
            return Optional.empty();
        }
        if (!first.getDest().equals(second.getSrc())) {
            return Optional.empty();
        }
        Set<BlockPos> secondPos = new HashSet<>(second.positions());
        int firstPositionInSecond = -1;
        // 末元素与 second 起点重合是拼接的前提,只查 [0, length-1)
        for (int i = 0; i < first.length() - 1; i++) {
            if (secondPos.contains(first.positions().get(i))) {
                firstPositionInSecond = i;
                break;
            }
        }
        if (firstPositionInSecond != -1) {
            if (!allowOverlapCutoff) {
                return Optional.empty();
            }
        } else {
            firstPositionInSecond = first.length() - 1;
        }
        int positionInSecond = second.positions().indexOf(first.positions().get(firstPositionInSecond));
        if (!allowOverlapCutoff && positionInSecond != 0) {
            throw new IllegalStateException("待拼接的两段重叠方式不合法");
        }
        List<BlockPos> positions = new ArrayList<>();
        List<Movement> movements = new ArrayList<>();
        positions.addAll(first.positions().subList(0, firstPositionInSecond + 1));
        movements.addAll(first.movements().subList(0, firstPositionInSecond));
        positions.addAll(second.positions().subList(positionInSecond + 1, second.length()));
        movements.addAll(second.movements().subList(positionInSecond, second.length() - 1));
        return Optional.of(new SplicedPath(positions, movements,
                first.getNumNodesConsidered() + second.getNumNodesConsidered(), first.getGoal()));
    }
}
