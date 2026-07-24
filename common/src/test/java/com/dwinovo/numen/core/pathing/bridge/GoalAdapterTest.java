package com.dwinovo.numen.core.pathing.bridge;

import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.goals.GoalBlock;
import com.dwinovo.numen.core.pathing.goals.GoalComposite;
import com.dwinovo.numen.core.pathing.goals.GoalGetToBlock;
import com.dwinovo.numen.core.pathing.goals.GoalNear;
import com.dwinovo.numen.core.pathing.goals.GoalRunAway;
import com.dwinovo.numen.core.pathing.goals.GoalTwoBlocks;
import com.dwinovo.numen.core.pathing.goals.GoalXZ;
import com.dwinovo.numen.core.pathing.goals.GoalYLevel;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目标映射逐工厂断言(纯逻辑,无 MC 引导):每个 NavGoal 工厂产物
 * 映射到约定的内核目标类型,且在采样格阵上成员判定与启发式与源目标
 * 一致(标注了刻意放宽的两处除外)。
 */
class GoalAdapterTest {

    private static final BlockPos T = new BlockPos(11, 64, -7);
    private static final double EPS = 1e-9;

    /** 在 T 周围 ±4 的格阵上断言成员判定完全一致。 */
    private static void assertSameMembership(NavGoal source, Goal mapped) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos p = T.offset(dx, dy, dz);
                    assertEquals(source.isAt(p), mapped.isInGoal(p.getX(), p.getY(), p.getZ()),
                            "membership at " + p + " for " + mapped);
                }
            }
        }
    }

    /** 在 T 周围 ±4 的格阵上断言启发式逐点一致。 */
    private static void assertSameHeuristic(NavGoal source, Goal mapped) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos p = T.offset(dx, dy, dz);
                    assertEquals(source.heuristic(p), mapped.heuristic(p.getX(), p.getY(), p.getZ()),
                            EPS, "heuristic at " + p + " for " + mapped);
                }
            }
        }
    }

    @Test
    void exactMapsToGoalBlock() {
        NavGoal source = NavGoal.exact(T);
        Goal mapped = GoalAdapter.toEngineGoal(source);
        assertInstanceOf(GoalBlock.class, mapped);
        assertSameMembership(source, mapped);
        assertSameHeuristic(source, mapped);
    }

    @Test
    void columnMapsToGoalXZ() {
        NavGoal source = NavGoal.column(T.getX(), T.getZ());
        Goal mapped = GoalAdapter.toEngineGoal(source);
        assertInstanceOf(GoalXZ.class, mapped);
        assertSameMembership(source, mapped);
        assertSameHeuristic(source, mapped);
        assertTrue(mapped.isInGoal(T.getX(), -1000, T.getZ()), "column admits any height");
    }

    @Test
    void yLevelMapsToGoalYLevel() {
        NavGoal source = NavGoal.yLevel(T.getY());
        Goal mapped = GoalAdapter.toEngineGoal(source);
        assertInstanceOf(GoalYLevel.class, mapped);
        assertSameMembership(source, mapped);
        assertSameHeuristic(source, mapped);
    }

    @Test
    void nearKeepsFractionalRadius() {
        NavGoal source = NavGoal.near(T, 2.5);
        Goal mapped = GoalAdapter.toEngineGoal(source);
        assertInstanceOf(GoalNear.class, mapped);
        assertSameMembership(source, mapped);
        assertSameHeuristic(source, mapped);
        // 小数半径不得取整:距中心 (2,1,1)(距离平方 6 ≤ 6.25)在内,
        // (2,2,1)(距离平方 9 > 6.25)在外
        assertTrue(mapped.isInGoal(T.getX() + 2, T.getY() + 1, T.getZ() + 1));
        assertFalse(mapped.isInGoal(T.getX() + 2, T.getY() + 2, T.getZ() + 1));
    }

    @Test
    void nearGroundKeepsGroundBand() {
        NavGoal source = NavGoal.nearGround(T, 3.0);
        Goal mapped = GoalAdapter.toEngineGoal(source);
        assertInstanceOf(GoalNear.class, mapped);
        assertSameMembership(source, mapped);
        assertSameHeuristic(source, mapped);
        // 垫柱回归钉:半径内但高两格的格不算到达
        assertFalse(mapped.isInGoal(T.getX() + 1, T.getY() + 2, T.getZ()));
        assertTrue(mapped.isInGoal(T.getX() + 1, T.getY() + 1, T.getZ()));
    }

    @Test
    void getToBlockKeepsMembership() {
        NavGoal source = NavGoal.getToBlock(T);
        Goal mapped = GoalAdapter.toEngineGoal(source);
        assertInstanceOf(GoalGetToBlock.class, mapped);
        // 成员判定逐格一致(身高修正的 Manhattan 公式相同);启发式
        // 形状刻意不同——旧词表减"一步+一跳"松量,内核按修正后 y 差
        // 估价——两者都以同一目标为瞄点,不断言数值相等
        assertSameMembership(source, mapped);
        assertEquals(0.0, mapped.heuristic(T.getX(), T.getY(), T.getZ()), EPS,
                "目标格自身的启发式为零(内核瞄点是方块本身,贴邻成员留一步余量)");
    }

    @Test
    void adjacentMapsToGetToBlockStance() {
        NavGoal source = NavGoal.adjacent(T);
        Goal mapped = GoalAdapter.toEngineGoal(source);
        assertInstanceOf(GoalGetToBlock.class, mapped);
        // 约定的成员集置换:同层四个正交贴邻(核心站位)保留;
        // 目标格本身与正下两格新增;旧成员里 y+1 的贴邻不再是成员
        for (BlockPos p : List.of(T.north(), T.south(), T.east(), T.west())) {
            assertTrue(source.isAt(p) && mapped.isInGoal(p.getX(), p.getY(), p.getZ()),
                    "同层正交贴邻保留 " + p);
        }
        assertTrue(mapped.isInGoal(T.getX(), T.getY(), T.getZ()), "目标格本身可作路径终点");
        assertFalse(mapped.isInGoal(T.getX() + 2, T.getY(), T.getZ()), "两格外不算");
    }

    @Test
    void compositeMapsMembersRecursively() {
        NavGoal source = NavGoal.composite(List.of(
                NavGoal.exact(T), NavGoal.near(T.offset(6, 0, 2), 2.0)));
        Goal mapped = GoalAdapter.toEngineGoal(source);
        assertInstanceOf(GoalComposite.class, mapped);
        Goal[] members = ((GoalComposite) mapped).goals();
        assertEquals(2, members.length);
        assertInstanceOf(GoalBlock.class, members[0]);
        assertInstanceOf(GoalNear.class, members[1]);
        assertSameMembership(source, mapped);
        assertSameHeuristic(source, mapped);
    }

    @Test
    void mineColumnFamilyKeepsStanceBand() {
        // maxBelow=0 → 单格;=1 → 双格;=2 → 双格 + 补格,成员集逐格一致
        NavGoal band0 = NavGoal.mineColumn(T, 0);
        Goal mapped0 = GoalAdapter.toEngineGoal(band0);
        assertInstanceOf(GoalBlock.class, mapped0);
        assertSameMembership(band0, mapped0);

        NavGoal band1 = NavGoal.mineColumn(T, 1);
        Goal mapped1 = GoalAdapter.toEngineGoal(band1);
        assertInstanceOf(GoalTwoBlocks.class, mapped1);
        assertSameMembership(band1, mapped1);

        NavGoal band2 = NavGoal.mineColumn(T, 2);
        Goal mapped2 = GoalAdapter.toEngineGoal(band2);
        assertInstanceOf(GoalComposite.class, mapped2);
        assertSameMembership(band2, mapped2);
        assertSameHeuristic(band2, mapped2);
    }

    @Test
    void runAwayMapsToGoalRunAway() {
        NavGoal source = NavGoal.runAway(T, T.getY());
        Goal mapped = GoalAdapter.toEngineGoal(source);
        assertInstanceOf(GoalRunAway.class, mapped);
        assertSameHeuristic(source, mapped);
        // 旧语义永不到达;新目标在常规距离上同样不到达
        assertFalse(mapped.isInGoal(T.getX() + 500, T.getY(), T.getZ() + 500));
        assertFalse(mapped.isInGoal(T.getX(), T.getY(), T.getZ()));
    }

    @Test
    void anonymousNavGoalFallsBackToWrapper() {
        BlockPos c = T.above(3);
        NavGoal custom = new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                return feet.getY() == c.getY() && feet.getX() % 2 == 0;
            }
            @Override public double heuristic(BlockPos from) {
                return Math.abs(from.getY() - c.getY()) * 7.0;
            }
            @Override public BlockPos center() {
                return c;
            }
        };
        Goal mapped = GoalAdapter.toEngineGoal(custom);
        assertSameMembership(custom, mapped);
        assertSameHeuristic(custom, mapped);
    }

    @Test
    void compiledContractCarriesMappedEngineGoal() {
        // 编译契约的 engineGoal 与词表映射一致;sacred/arrival 原样穿透
        GoalCompiler.Compiled interact = GoalCompiler.interact(T);
        assertInstanceOf(GoalGetToBlock.class, interact.engineGoal());
        assertTrue(interact.sacred().contains(T.asLong()));

        GoalCompiler.Compiled standOn = GoalCompiler.block(true, T);
        assertInstanceOf(GoalBlock.class, standOn.engineGoal());
        assertTrue(standOn.sacred().isEmpty());

        GoalCompiler.Compiled occupied = GoalCompiler.block(false, T);
        assertInstanceOf(GoalGetToBlock.class, occupied.engineGoal());

        GoalCompiler.Compiled adjacent = GoalCompiler.standAdjacent(T);
        assertInstanceOf(GoalGetToBlock.class, adjacent.engineGoal());

        GoalCompiler.Compiled near = GoalCompiler.near(T, 4.0);
        assertInstanceOf(GoalNear.class, near.engineGoal());

        GoalCompiler.Compiled mine = GoalCompiler.mineField(
                List.of(GoalCompiler.Stance.at(T, 1)), List.of(T.offset(2, 0, 2)));
        assertInstanceOf(GoalComposite.class, mine.engineGoal());
        assertTrue(mine.sacred().contains(T.asLong()));
        // 成员判定与旧词表目标一致(矿柱带 + 掉落物邻域)
        assertSameMembership(mine.goal(), mine.engineGoal());
    }
}
