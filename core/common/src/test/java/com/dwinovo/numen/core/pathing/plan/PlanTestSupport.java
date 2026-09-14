package com.dwinovo.numen.core.pathing.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.dwinovo.numen.core.pathing.astar.NavPath;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;

import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;

/** 规划查询与路线簿单测的共用假件:目标契约、假移动、假路径、直线路径。纯 JVM,不引导 MC。 */
final class PlanTestSupport {

    private PlanTestSupport() {}

    /** 永不到达、启发式恒 0 的目标契约。 */
    static GoalCompiler.Compiled neverGoal() {
        NavGoal nav = new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                return false;
            }

            @Override public double heuristic(BlockPos from) {
                return 0;
            }

            @Override public BlockPos center() {
                return new BlockPos(100, 64, 0);
            }
        };
        Goal engine = new Goal() {
            @Override public boolean isInGoal(int x, int y, int z) {
                return false;
            }

            @Override public double heuristic(int x, int y, int z) {
                return 0;
            }
        };
        return new GoalCompiler.Compiled(nav, engine, LongSets.emptySet());
    }

    /** 不接玩家、成本固定的假移动(纯容器语义,不会被执行)。 */
    static final class TestMovement extends Movement {

        TestMovement(BlockPos src, BlockPos dest, double cost) {
            this(src, dest, cost, new BlockPos[0]);
        }

        TestMovement(BlockPos src, BlockPos dest, double cost, BlockPos[] toBreak) {
            super(null, RouteSpec.defaults(), src, dest, toBreak);
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
    static final class FakePath implements NavPath {

        private final List<BlockPos> positions;
        private final List<Movement> movements;

        FakePath(List<BlockPos> positions) {
            this(positions, new BlockPos[0]);
        }

        /** @param firstBreaks 第一段要挖的格(账单从这里来) */
        FakePath(List<BlockPos> positions, BlockPos[] firstBreaks) {
            this.positions = List.copyOf(positions);
            List<Movement> moves = new ArrayList<>();
            for (int i = 0; i < positions.size() - 1; i++) {
                moves.add(new TestMovement(positions.get(i), positions.get(i + 1), 1,
                        i == 0 ? firstBreaks : new BlockPos[0]));
            }
            this.movements = Collections.unmodifiableList(moves);
        }

        @Override
        public List<Movement> movements() {
            return movements;
        }

        @Override
        public List<BlockPos> positions() {
            return positions;
        }

        @Override
        public Goal getGoal() {
            return null;
        }

        @Override
        public int getNumNodesConsidered() {
            return 0;
        }
    }

    /** 沿 +x 的直线:从 startX 起共 length 格,y=64,给定 z。 */
    static FakePath line(int startX, int length, int z) {
        return new FakePath(linePositions(startX, length, z));
    }

    /** 同上,但第一段要挖掉给定的格。 */
    static FakePath lineBreaking(int startX, int length, int z, BlockPos... toBreak) {
        return new FakePath(linePositions(startX, length, z), toBreak);
    }

    private static List<BlockPos> linePositions(int startX, int length, int z) {
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            positions.add(new BlockPos(startX + i, 64, z));
        }
        return positions;
    }
}
