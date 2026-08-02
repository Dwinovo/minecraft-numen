package com.dwinovo.numen.core.pathing.execute;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.pathing.astar.NavPath;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;
import com.dwinovo.numen.core.pathing.moves.movements.MovementFall;

import net.minecraft.core.BlockPos;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行器可离线验证的纯逻辑:重定位的回退扫/前跳扫窗口与提前接段条件。
 * 用假移动(直线平走序列)+ 假脚位序列驱动,不引导 MC。
 *
 * <p>onTick 全流程需要活实体与世界(视线、脚位、方块状态),无法离线
 * 测,留待游戏内验收。
 */
class PathExecutorLogicTest {

    /** 只带 src/dest 与合法位的假移动,成本可配(默认 1)。 */
    private static class FakeMovement extends Movement {

        private final double cost;

        FakeMovement(BlockPos src, BlockPos dest) {
            this(src, dest, 1.0);
        }

        FakeMovement(BlockPos src, BlockPos dest, double cost) {
            super(null, src, dest, new BlockPos[0], null);
            this.cost = cost;
        }

        @Override
        public double calculateCost(CalculationContext context, MutableMoveResult result) {
            return cost;
        }

        @Override
        protected Set<BlockPos> calculateValidPositions() {
            return Set.of(src, dest);
        }
    }

    /** 沿 +X 的直线平走链:(0,64,0) → (n,64,0),n 个移动。 */
    private static List<Movement> straightLine(int n) {
        List<Movement> movements = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            movements.add(new FakeMovement(new BlockPos(i, 64, 0), new BlockPos(i + 1, 64, 0)));
        }
        return movements;
    }

    private static BlockPos at(int x) {
        return new BlockPos(x, 64, 0);
    }

    // ==================== 回退扫 ====================

    /** 身位落在已走过的移动里 → 回到那一步(取最早匹配)。 */
    @Test
    void backwardMatchFindsEarliestContaining() {
        List<Movement> movements = straightLine(10);
        // 推进到第 6 步,被击退回 x=3:movement[2](2→3) 与 movement[3](3→4) 都含 x=3,取最早的 2
        assertEquals(2, PathExecutor.findBackwardMatch(movements, 6, at(3)));
    }

    /** 身位不在任何已走过的移动里 → -1。 */
    @Test
    void backwardMatchMissesWhenOffPath() {
        List<Movement> movements = straightLine(10);
        assertEquals(-1, PathExecutor.findBackwardMatch(movements, 6, new BlockPos(3, 65, 5)));
    }

    /** 回退扫只查 [0, pathPosition):当前步自己的合法位不算回退。 */
    @Test
    void backwardMatchExcludesCurrent() {
        List<Movement> movements = straightLine(10);
        // x=7 只出现在 movement[6](6→7,当前步)与 movement[7] 里,回退窗口查不到
        assertEquals(-1, PathExecutor.findBackwardMatch(movements, 6, at(7)));
    }

    // ==================== 前跳扫 ====================

    /** 前跳窗口从 +3 起:+1/+2 的合法位刻意不认。 */
    @Test
    void forwardSkipStartsAtPlusThree() {
        List<Movement> movements = straightLine(12);
        // 当前第 2 步,身位 x=4:movement[3](3→4) 含它,但 3 = pathPosition+1,窗口外
        assertEquals(-1, PathExecutor.findForwardSkip(movements, 2, at(4)));
        // 身位 x=6:movement[5](5→6) 是 pathPosition+3,窗口内,返回 5
        assertEquals(5, PathExecutor.findForwardSkip(movements, 2, at(6)));
    }

    /** 前跳扫覆盖到路径末尾的最后一个移动。 */
    @Test
    void forwardSkipReachesTail() {
        List<Movement> movements = straightLine(12);
        assertEquals(11, PathExecutor.findForwardSkip(movements, 2, at(12)));
    }

    /** 身位不在任何后续移动里 → -1。 */
    @Test
    void forwardSkipMissesWhenOffPath() {
        List<Movement> movements = straightLine(12);
        assertEquals(-1, PathExecutor.findForwardSkip(movements, 2, new BlockPos(6, 70, 0)));
    }

    // ==================== 提前接段(snipsnap)条件 ====================

    private static List<BlockPos> positions(int n) {
        List<BlockPos> list = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            list.add(at(i));
        }
        return list;
    }

    /** 站稳且身位恰在格位序列上 → 返回该下标。 */
    @Test
    void snipsnapSnapsWhenOnGroundAndOnPath() {
        assertEquals(4, PathExecutor.snipsnapIndex(positions(8), at(4), true, false, 0.0));
    }

    /** 空中且不在液体里 → 不接(动量不可控)。 */
    @Test
    void snipsnapRefusesMidair() {
        assertEquals(-1, PathExecutor.snipsnapIndex(positions(8), at(4), false, false, 0.0));
    }

    /** 液体里允许接(视同站稳)。 */
    @Test
    void snipsnapAllowsLiquid() {
        assertEquals(4, PathExecutor.snipsnapIndex(positions(8), at(4), false, true, 0.0));
    }

    /** 严格下沉(竖速 < -0.1)→ 不接(可能正穿水下坠)。 */
    @Test
    void snipsnapRefusesSinking() {
        assertEquals(-1, PathExecutor.snipsnapIndex(positions(8), at(4), false, true, -0.2));
        assertEquals(-1, PathExecutor.snipsnapIndex(positions(8), at(4), true, false, -0.2));
    }

    /** 身位不在格位序列上 → -1。 */
    @Test
    void snipsnapMissesWhenOffPath() {
        assertEquals(-1, PathExecutor.snipsnapIndex(positions(8), new BlockPos(3, 65, 2), true, false, 0.0));
    }

    // ==================== 脱轨判定(纯逻辑) ====================

    /** 造一条直线 NavPath:n 个 +X 平走,positions 从 (0,64,0) 到 (n,64,0)。 */
    private static NavPath straightPath(int n) {
        List<Movement> movements = straightLine(n);
        List<BlockPos> pos = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            pos.add(at(i));
        }
        return new NavPath() {
            @Override public List<Movement> movements() { return movements; }
            @Override public List<BlockPos> positions() { return pos; }
            @Override public com.dwinovo.numen.core.pathing.goals.Goal getGoal() {
                throw new UnsupportedOperationException();
            }
            @Override public int getNumNodesConsidered() { return 0; }
        };
    }

    /** 玩家贴在路径格位中心水平投影 → 最近距离为格心 y 偏移(格心在格内 y=+0.5)。 */
    @Test
    void closestPathPosIsZeroAtCenter() {
        // 玩家在 (0,64,0) 格中心水平投影 (0.5,64.0,0.5):格心 (0.5,64.5,0.5),3D 距离 0.5
        assertEquals(0.5, PathExecutor.closestPathPosDist(
                straightLine(3), 0.5, 64.0, 0.5), 1e-6);
    }

    /** 玩家水平偏出路径 3 格 → 最近距离 > 2,判定脱轨。 */
    @Test
    void offPathWhenFarFromAllValidPositions() {
        // 路径沿 +X 在 y=64,玩家在 z=3(水平偏 3):最近格心水平距离 > 2
        double dist = PathExecutor.closestPathPosDist(straightLine(3), 0.5, 64.0, 3.5);
        assertTrue(dist > 2, "水平偏离 3 格应脱轨,实得 " + dist);
        NavPath path = straightPath(3);
        assertTrue(PathExecutor.possiblyOffPath(path, 0, dist, 2,
                0.5, 3.5, p -> PathExecutor.flatDistanceToCenter(p, 0.5, 3.5)));
    }

    /** 玩家在路径上但距格心略偏(≤2)→ 不脱轨。 */
    @Test
    void onPathWhenWithinLeniency() {
        double dist = PathExecutor.closestPathPosDist(straightLine(3), 0.7, 64.0, 0.5);
        assertTrue(dist <= 2, "贴路径内应不脱轨,实 " + dist);
        NavPath path = straightPath(3);
        assertFalse(PathExecutor.possiblyOffPath(path, 0, dist, 2,
                0.7, 0.5, p -> PathExecutor.flatDistanceToCenter(p, 0.7, 0.5)));
    }

    /** 坠落中改用水平距离:垂直远离不算脱轨。 */
    @Test
    void fallUsesFlatDistanceOnly() {
        // 造一条含 Fall 的路径:src(0,66,0)->dest(0,64,0) 的坠落
        List<Movement> movements = new ArrayList<>();
        movements.add(new MovementFall(null, new BlockPos(0, 66, 0), new BlockPos(0, 64, 0)));
        List<BlockPos> pos = List.of(new BlockPos(0, 66, 0), new BlockPos(0, 64, 0));
        NavPath path = new NavPath() {
            @Override public List<Movement> movements() { return movements; }
            @Override public List<BlockPos> positions() { return pos; }
            @Override public com.dwinovo.numen.core.pathing.goals.Goal getGoal() { throw new UnsupportedOperationException(); }
            @Override public int getNumNodesConsidered() { return 0; }
        };
        // 玩家正下方 y=65(坠落中),水平仍在落点上方 → 水平距离 0,不脱轨
        double flatDist = PathExecutor.flatDistanceToCenter(new BlockPos(0, 64, 0), 0.5, 0.5);
        assertEquals(0.0, flatDist, 1e-6);
        assertFalse(PathExecutor.possiblyOffPath(path, 0, 99.0, 2,
                0.5, 0.5, p -> PathExecutor.flatDistanceToCenter(p, 0.5, 0.5)));
        // 水平偏出 ≥2 → 脱轨
        assertTrue(PathExecutor.possiblyOffPath(path, 0, 99.0, 2,
                3.5, 0.5, p -> PathExecutor.flatDistanceToCenter(p, 3.5, 0.5)));
    }

    // ==================== 成本涨幅 / 超时(纯逻辑) ====================

    /** 估价涨幅 ≤ 容差 → 不中止。 */
    @Test
    void costIncreaseWithinToleranceDoesNotTrigger() {
        assertFalse(PathExecutor.costIncreaseExceedsTolerance(10.0, 15.0, 10.0));
        assertFalse(PathExecutor.costIncreaseExceedsTolerance(10.0, 20.0, 10.0)); // 恰好等于
    }

    /** 估价涨幅 > 容差 → 中止。 */
    @Test
    void costIncreaseBeyondToleranceTriggers() {
        assertTrue(PathExecutor.costIncreaseExceedsTolerance(10.0, 20.01, 10.0));
    }

    /** 单移动超时阈值 = 估价 + 宽限。 */
    @Test
    void timeoutIsCostPlusGrace() {
        assertEquals(110.0, PathExecutor.timedOutAt(10.0, 100));
        assertEquals(100.0, PathExecutor.timedOutAt(0.0, 100));
    }

    // ==================== 前瞻成本核验(纯逻辑,FakeMovement 不读 context) ====================

    /** 前瞻窗口内全可行 → -1。 */
    @Test
    void futureImpossibleReturnsMinusOneWhenAllFine() {
        List<Movement> movements = straightLine(6); // 每步成本 1,全可行
        assertEquals(-1, PathExecutor.firstFutureImpossible(movements, 0, 5, null));
    }

    /** 前瞻窗口内首个 INF 成本移动 → 返回其下标。 */
    @Test
    void futureImpossibleFindsFirstInf() {
        List<Movement> movements = new ArrayList<>();
        movements.add(new FakeMovement(at(0), at(1), 1.0));
        movements.add(new FakeMovement(at(1), at(2), 1.0));
        movements.add(new FakeMovement(at(2), at(3), COST_INF)); // 下标 2
        movements.add(new FakeMovement(at(3), at(4), 1.0));
        assertEquals(2, PathExecutor.firstFutureImpossible(movements, 0, 5, null));
    }

    /** 前瞻窗口不含当前步(+1 起),也不含路径末位。 */
    @Test
    void futureImpossibleWindowExcludesCurrentAndTail() {
        List<Movement> movements = new ArrayList<>();
        movements.add(new FakeMovement(at(0), at(1), COST_INF)); // 下标 0=当前,不查
        movements.add(new FakeMovement(at(1), at(2), 1.0));
        movements.add(new FakeMovement(at(2), at(3), COST_INF)); // 下标 2,在窗口内
        // lookahead=2 只查 i=1;窗口外不下标 2 → -1
        assertEquals(-1, PathExecutor.firstFutureImpossible(movements, 0, 2, null));
        // lookahead=3 查 i=1,2;命中下标 2
        assertEquals(2, PathExecutor.firstFutureImpossible(movements, 0, 3, null));
    }
}
