package com.dwinovo.numen.core.pathing.astar;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
import com.dwinovo.numen.core.pathing.moves.Movement;

import net.minecraft.core.BlockPos;

/**
 * 一条可执行路径的只读契约。约定:
 * movements().get(i).getSrc() == positions().get(i),
 * movements().get(i).getDest() == positions().get(i+1),
 * 故 movements 数恒为 positions 数减一。
 */
public interface NavPath {

    /** 按序执行的移动原语列表。 */
    List<Movement> movements();

    /** 途经的全部格位,首元素为起点、末元素为终点。 */
    List<BlockPos> positions();

    /**
     * 执行前的一次性后处理(装配移动原语、钉住对照成本等)。
     * 仅搜索直接产出的 {@link Path} 支持;其余实现不支持。
     */
    default NavPath postProcess() {
        throw new UnsupportedOperationException();
    }

    /** 路径包含的格位数,等于 positions().size()。 */
    default int length() {
        return positions().size();
    }

    /** 本路径当时朝向的目标。 */
    Goal getGoal();

    /** 找到本路径前搜索器考虑过的节点数。 */
    int getNumNodesConsidered();

    default BlockPos getSrc() {
        return positions().get(0);
    }

    default BlockPos getDest() {
        List<BlockPos> pos = positions();
        return pos.get(pos.size() - 1);
    }

    /**
     * 从下标 pathPosition 起走完剩余路径的预计 tick 数。
     * 只读缓存成本,不触发重算。
     */
    default double ticksRemainingFrom(int pathPosition) {
        double sum = 0;
        List<Movement> movements = movements();
        for (int i = pathPosition; i < movements.size(); i++) {
            sum += movements.get(i).getCost();
        }
        return sum;
    }

    /** 在已加载 chunk 边界截断(开关默认关);默认不支持。 */
    default NavPath cutoffAtLoadedChunks(ChunkLoadedTest loadedTest) {
        throw new UnsupportedOperationException();
    }

    /** 部分路径截尾(段尾质量差);默认不支持。 */
    default NavPath staticCutoff(Goal destination) {
        throw new UnsupportedOperationException();
    }

    /**
     * 装配完整性自检:首尾一致、positions 与 movements 的长度关系、
     * 每个 movement 与相邻格位衔接、全程无环。任何违背即抛。
     */
    default void sanityCheck() {
        List<BlockPos> path = positions();
        List<Movement> movements = movements();
        if (!getSrc().equals(path.get(0))) {
            throw new IllegalStateException("起点与首格位不一致");
        }
        if (!getDest().equals(path.get(path.size() - 1))) {
            throw new IllegalStateException("终点与末格位不一致");
        }
        if (path.size() != movements.size() + 1) {
            throw new IllegalStateException(
                    "格位数(" + path.size() + ")与移动数(" + movements.size() + ")长度关系不成立");
        }
        Set<BlockPos> seenSoFar = new HashSet<>();
        for (int i = 0; i < path.size() - 1; i++) {
            BlockPos src = path.get(i);
            BlockPos dest = path.get(i + 1);
            Movement movement = movements.get(i);
            if (!src.equals(movement.getSrc())) {
                throw new IllegalStateException("第 " + i + " 个移动的 src 与格位不符");
            }
            if (!dest.equals(movement.getDest())) {
                throw new IllegalStateException("第 " + i + " 个移动的 dest 与格位不符");
            }
            if (seenSoFar.contains(src)) {
                throw new IllegalStateException("路径成环:重复经过 " + src);
            }
            seenSoFar.add(src);
        }
    }
}
