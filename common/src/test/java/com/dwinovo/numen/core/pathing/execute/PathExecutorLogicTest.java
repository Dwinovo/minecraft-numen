package com.dwinovo.numen.core.pathing.execute;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;

import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 执行器可离线验证的纯逻辑:重定位的回退扫/前跳扫窗口与提前接段条件。
 * 用假移动(直线平走序列)+ 假脚位序列驱动,不引导 MC。
 *
 * <p>onTick 全流程需要活实体与世界(视线、脚位、方块状态),无法离线
 * 测,留待游戏内验收。
 */
class PathExecutorLogicTest {

    /** 只带 src/dest 与合法位的假移动,成本恒 1。 */
    private static final class FakeMovement extends Movement {

        FakeMovement(BlockPos src, BlockPos dest) {
            super(null, src, dest, new BlockPos[0], null);
        }

        @Override
        public double calculateCost(CalculationContext context, MutableMoveResult result) {
            return 1;
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
}
