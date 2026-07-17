package com.dwinovo.numen.core.pathing.astar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;

import net.minecraft.core.BlockPos;

/** astar 单测的共用假件:目标、假移动、假路径与直线路径工厂。 */
final class AstarTestSupport {

    private AstarTestSupport() {}

    /** 永不到达、启发式恒 0 的目标。 */
    static final Goal NEVER_GOAL = new Goal() {
        @Override
        public boolean isInGoal(int x, int y, int z) {
            return false;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return 0;
        }
    };

    /** 任何位置都算到达的目标。 */
    static final Goal ALWAYS_GOAL = new Goal() {
        @Override
        public boolean isInGoal(int x, int y, int z) {
            return true;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return 0;
        }
    };

    /** 不接玩家、成本固定的假移动(纯容器语义,不会被执行)。 */
    static final class TestMovement extends Movement {

        TestMovement(BlockPos src, BlockPos dest, double cost) {
            super(null, src, dest, new BlockPos[0]);
            override(cost);
        }

        @Override
        public double calculateCost(CalculationContext context, MutableMoveResult result) {
            return getCost();
        }

        @Override
        protected Set<BlockPos> calculateValidPositions() {
            return Set.of(getSrc(), getDest());
        }
    }

    /** 直接以给定列表为内容的假路径。 */
    static final class FakePath extends PathBase {

        private final List<BlockPos> positions;
        private final List<Movement> movements;
        private final Goal goal;

        FakePath(List<BlockPos> positions, List<Movement> movements, Goal goal) {
            this.positions = positions;
            this.movements = movements;
            this.goal = goal;
        }

        @Override
        public List<Movement> movements() {
            return Collections.unmodifiableList(movements);
        }

        @Override
        public List<BlockPos> positions() {
            return Collections.unmodifiableList(positions);
        }

        @Override
        public Goal getGoal() {
            return goal;
        }

        @Override
        public int getNumNodesConsidered() {
            return 0;
        }
    }

    /**
     * 沿 +x 的直线路径:从 startX 起共 length 个格位,y=64,z=0,
     * 每步一个成本为 1 的假移动。
     */
    static FakePath line(int startX, int length, Goal goal) {
        List<BlockPos> positions = new ArrayList<>();
        List<Movement> movements = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            positions.add(new BlockPos(startX + i, 64, 0));
        }
        for (int i = 0; i < length - 1; i++) {
            movements.add(new TestMovement(positions.get(i), positions.get(i + 1), 1));
        }
        return new FakePath(positions, movements, goal);
    }
}
