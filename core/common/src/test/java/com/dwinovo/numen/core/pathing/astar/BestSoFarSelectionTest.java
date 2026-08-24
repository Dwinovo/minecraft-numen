package com.dwinovo.numen.core.pathing.astar;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.pathing.goals.Goal;

import net.minecraft.core.BlockPos;

import static com.dwinovo.numen.core.pathing.astar.AstarTestSupport.NEVER_GOAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 七档 bestSoFar 判据与 PathNode 构造契约。 */
class BestSoFarSelectionTest {

    /** calculate0 空转的测试搜索器,直接摆弄七档数组。 */
    private static final class TestSearch extends AbstractNodeCostSearch {

        TestSearch(int startX, int startY, int startZ, Goal goal) {
            super(new BlockPos(startX, startY, startZ), startX, startY, startZ, goal, null);
        }

        @Override
        protected Optional<NavPath> calculate0(long primaryTimeout, long failureTimeout) {
            return Optional.empty();
        }
    }

    /** 造一个回链到 start 的节点并赋成本。 */
    private static PathNode chained(PathNode previous, int x, int y, int z, double cost, Goal goal) {
        PathNode node = new PathNode(x, y, z, goal);
        node.previous = previous;
        node.cost = cost;
        return node;
    }

    @Test
    void startNodeNullYieldsEmpty() {
        TestSearch search = new TestSearch(0, 64, 0, NEVER_GOAL);
        assertTrue(search.bestPathSoFar().isEmpty());
    }

    @Test
    void allTiersTooCloseYieldsEmpty() {
        TestSearch search = new TestSearch(0, 64, 0, NEVER_GOAL);
        search.startNode = chained(null, 0, 64, 0, 0, NEVER_GOAL);
        // 全部档位停在距起点 4 格(距离平方 16 ≤ 25)的节点
        PathNode near = chained(search.startNode, 4, 64, 0, 10, NEVER_GOAL);
        for (int i = 0; i < 7; i++) {
            search.bestSoFar[i] = near;
        }
        assertTrue(search.bestPathSoFar().isEmpty());
    }

    @Test
    void exactlyFiveBlocksIsNotEnough() {
        // 判据是严格大于 25,恰好 5 格(25)不够
        TestSearch search = new TestSearch(0, 64, 0, NEVER_GOAL);
        search.startNode = chained(null, 0, 64, 0, 0, NEVER_GOAL);
        search.bestSoFar[0] = chained(search.startNode, 5, 64, 0, 10, NEVER_GOAL);
        assertTrue(search.bestPathSoFar().isEmpty());
    }

    @Test
    void firstQualifyingTierWins() {
        TestSearch search = new TestSearch(0, 64, 0, NEVER_GOAL);
        search.startNode = chained(null, 0, 64, 0, 0, NEVER_GOAL);
        // 档 0、1 空,档 2 距起点 6 格,档 3 更远——取档 2(第一个合格档)
        PathNode tier2 = chained(search.startNode, 6, 64, 0, 30, NEVER_GOAL);
        PathNode tier3 = chained(search.startNode, 20, 64, 0, 90, NEVER_GOAL);
        search.bestSoFar[2] = tier2;
        search.bestSoFar[3] = tier3;
        Optional<NavPath> result = search.bestPathSoFar();
        assertTrue(result.isPresent());
        assertEquals(new BlockPos(6, 64, 0), result.get().getDest());
    }

    @Test
    void nearTierIsSkippedForFartherLaterTier() {
        TestSearch search = new TestSearch(0, 64, 0, NEVER_GOAL);
        search.startNode = chained(null, 0, 64, 0, 0, NEVER_GOAL);
        // 档 0 太近(3 格),档 4 够远——跳过档 0 落到档 4
        search.bestSoFar[0] = chained(search.startNode, 3, 64, 0, 10, NEVER_GOAL);
        search.bestSoFar[4] = chained(search.startNode, 10, 64, 0, 60, NEVER_GOAL);
        Optional<NavPath> result = search.bestPathSoFar();
        assertTrue(result.isPresent());
        assertEquals(new BlockPos(10, 64, 0), result.get().getDest());
    }

    @Test
    void returnedPathTracesBackToStart() {
        TestSearch search = new TestSearch(0, 64, 0, NEVER_GOAL);
        search.startNode = chained(null, 0, 64, 0, 0, NEVER_GOAL);
        PathNode mid = chained(search.startNode, 3, 64, 0, 15, NEVER_GOAL);
        PathNode far = chained(mid, 7, 64, 0, 35, NEVER_GOAL);
        search.bestSoFar[0] = far;
        NavPath path = search.bestPathSoFar().orElseThrow();
        assertEquals(3, path.length());
        assertEquals(new BlockPos(0, 64, 0), path.getSrc());
        assertEquals(new BlockPos(7, 64, 0), path.getDest());
    }

    @Test
    void nanHeuristicThrowsAtNodeConstruction() {
        Goal nanGoal = new Goal() {
            @Override
            public boolean isInGoal(int x, int y, int z) {
                return false;
            }

            @Override
            public double heuristic(int x, int y, int z) {
                return Double.NaN;
            }
        };
        assertThrows(IllegalStateException.class, () -> new PathNode(0, 64, 0, nanGoal));
    }

    @Test
    void searchIsSingleUse() {
        TestSearch search = new TestSearch(0, 64, 0, NEVER_GOAL);
        assertEquals(PathCalcResult.Type.FAILURE, search.calculate(100, 200).getType());
        assertThrows(IllegalStateException.class, () -> search.calculate(100, 200));
    }
}
