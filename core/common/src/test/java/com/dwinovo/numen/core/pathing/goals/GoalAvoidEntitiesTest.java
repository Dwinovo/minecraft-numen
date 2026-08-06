package com.dwinovo.numen.core.pathing.goals;

import com.dwinovo.numen.core.pathing.goals.GoalAvoidEntities.Threat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 躲避势场。
 *
 * <p>最要紧的一条是<b>两个威胁一左一右时不能穿过其中一个</b>——只看最近那一个的目标
 * (旧的 {@code GoalRunAway})会把"贴着左边那只跑向右边"算成好路,因为它眼里右边不存在。
 */
class GoalAvoidEntitiesTest {

    private static GoalAvoidEntities avoid(double distance, Threat... threats) {
        return new GoalAvoidEntities(distance, 1.0, threats);
    }

    // ==================== 到达 ====================

    /** 离每个威胁都够远才算脱身,不是离最近那个够远。 */
    @Test
    void arrivalNeedsClearanceFromEveryThreat() {
        GoalAvoidEntities goal = avoid(5.0,
                new Threat(0, 64, 0, 1.0), new Threat(20, 64, 0, 1.0));
        assertTrue(goal.isInGoal(10, 64, 0), "两边各 10 格,脱身了");
        assertFalse(goal.isInGoal(18, 64, 0), "离左边远不代表安全,右边还有一个");
    }

    /** 竖直不计:头顶三格并不安全,爆炸是球形的。 */
    @Test
    void heightDoesNotCountAsClearance() {
        assertFalse(avoid(5.0, new Threat(0, 64, 0, 1.0)).isInGoal(0, 74, 0));
    }

    // ==================== 势场 ====================

    /** 越远越便宜——这是这片势场存在的全部意义。 */
    @Test
    void fartherIsCheaper() {
        GoalAvoidEntities goal = avoid(5.0, new Threat(0, 64, 0, 1.0));
        assertTrue(goal.heuristic(20, 64, 0) < goal.heuristic(5, 64, 0));
    }

    /** 权重大的威胁在同样距离上更贵——引信点着的爬行者就该比没点着的更催她走开。 */
    @Test
    void aHeavierThreatCostsMoreAtTheSameDistance() {
        double light = avoid(5.0, new Threat(0, 64, 0, 1.0)).heuristic(6, 64, 0);
        double heavy = avoid(5.0, new Threat(0, 64, 0, 5.0)).heuristic(6, 64, 0);
        assertTrue(heavy > light, "点火的该更贵:" + heavy + " vs " + light);
    }

    /**
     * 两个威胁一左一右,正中间必须比贴着任一个更便宜。<b>这一条只看最近威胁的实现是过不了的</b>
     * ——在它眼里贴着左边那只时"离最近威胁 1 格",走到中间"离最近威胁 10 格",
     * 可它算不出中间还有右边那只在拉扯。
     */
    @Test
    void theMiddleBeatsHuggingEitherOne() {
        GoalAvoidEntities goal = avoid(5.0,
                new Threat(0, 64, 0, 1.0), new Threat(20, 64, 0, 1.0));
        double middle = goal.heuristic(10, 64, 0);
        assertTrue(middle < goal.heuristic(1, 64, 0), "贴着左边不该比走中间便宜");
        assertTrue(middle < goal.heuristic(19, 64, 0), "贴着右边不该比走中间便宜");
    }

    /** 站在威胁身上不能算出无穷:无穷会让所有"贴脸"的格子成为平手,搜索就没法在其中择优。 */
    @Test
    void standingOnTopOfAThreatIsExpensiveButStillComparable() {
        double onTop = avoid(5.0, new Threat(0, 64, 0, 1.0)).heuristic(0, 64, 0);
        assertTrue(Double.isFinite(onTop), "不能是无穷");
        assertEquals(GoalAvoidEntities.ON_TOP_OF_THREAT, onTop, 1e-9);
    }

    /** 势场强度是等比放大的:调它只改绕路的坚决程度,不改哪个位置更好。 */
    @Test
    void thePenaltyFactorScalesWithoutReorderingPositions() {
        Threat t = new Threat(0, 64, 0, 1.0);
        var weak = new GoalAvoidEntities(5.0, 1.0, t);
        var strong = new GoalAvoidEntities(5.0, 40.0, t);
        assertEquals(weak.heuristic(6, 64, 0) * 40.0, strong.heuristic(6, 64, 0), 1e-9);
        assertTrue(strong.heuristic(20, 64, 0) < strong.heuristic(6, 64, 0));
    }

    // ==================== 契约 ====================

    @Test
    void aThreatMustCarryAPositiveWeight() {
        assertThrows(IllegalArgumentException.class, () -> new Threat(0, 0, 0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new Threat(0, 0, 0, -1.0));
    }

    @Test
    void avoidingNothingIsAProgrammingErrorNotAnEmptyField() {
        assertThrows(IllegalArgumentException.class, () -> new GoalAvoidEntities(5.0, 1.0));
    }
}
