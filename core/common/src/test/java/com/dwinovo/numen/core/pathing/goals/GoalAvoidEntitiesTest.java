package com.dwinovo.numen.core.pathing.goals;

import com.dwinovo.numen.core.pathing.goals.GoalAvoidEntities.Threat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 躲开一组威胁。钉的是三条:<b>每只按自己的危险半径</b>、<b>多个威胁一起算而不是只认最近的</b>、
 * 以及<b>坐标是实体真坐标对格心</b>。
 */
class GoalAvoidEntitiesTest {

    /** 威胁站在格心上,这样距离好数。 */
    private static Threat threat(double x, double z, double radius) {
        return new Threat(x + 0.5, 64.0, z + 0.5, radius);
    }

    private static GoalAvoidEntities avoid(Threat... threats) {
        return new GoalAvoidEntities(1.0, threats);
    }

    /** 出了半径才算脱身。 */
    @Test
    void arrivalMeansOutsideEveryRadius() {
        GoalAvoidEntities goal = avoid(threat(0, 0, 3.0));
        assertFalse(goal.isInGoal(2, 64, 0));
        assertTrue(goal.isInGoal(3, 64, 0));
    }

    /**
     * 每只用<b>自己的</b>半径。用一个统一的数只能取最大值,于是她躲僵尸也按蜘蛛的距离躲,
     * 永远打不着。
     */
    @Test
    void everyThreatBringsItsOwnRadius() {
        GoalAvoidEntities goal = avoid(threat(0, 0, 2.0), threat(10, 0, 6.0));
        assertTrue(goal.isInGoal(2, 64, 0));      // 离近的那只 2 格,够了
        assertFalse(goal.isInGoal(5, 64, 0));     // 离远的那只 5 格,它要 6
        assertTrue(goal.isInGoal(3, 64, 0));      // 两边都出了
    }

    /** 竖直不豁免:爆炸是球形的,头顶三格并不安全。 */
    @Test
    void heightIsNotSafety() {
        assertFalse(avoid(threat(0, 0, 3.0)).isInGoal(0, 70, 0));
    }

    /** 势场认得完所有威胁——两只一左一右时才不会直穿其中一只。 */
    @Test
    void everyThreatCountsInTheField() {
        Threat left = threat(0, 0, 3.0);
        Threat right = threat(10, 0, 3.0);
        double between = avoid(left, right).heuristic(5, 64, 0);
        double outside = avoid(left, right).heuristic(20, 64, 0);
        assertTrue(between > outside);
    }

    @Test
    void closerIsAlwaysMoreExpensive() {
        GoalAvoidEntities goal = avoid(threat(0, 0, 3.0));
        assertTrue(goal.heuristic(1, 64, 0) > goal.heuristic(4, 64, 0));
        assertTrue(goal.heuristic(4, 64, 0) > goal.heuristic(12, 64, 0));
    }

    /**
     * 势能按<b>半径的倍数</b>算,所以贴在各自半径边上的两只势能相同——危险程度已经写在
     * 半径里,不需要再开一个权重字段。
     */
    @Test
    void thePotentialScalesWithTheRadius() {
        double small = avoid(threat(0, 0, 2.0)).heuristic(2, 64, 0);
        double large = avoid(threat(0, 0, 6.0)).heuristic(6, 64, 0);
        assertEquals(small, large, 1e-9);
    }

    /**
     * 危险<b>相加</b>,不取平均。
     *
     * <p>曾经除以威胁个数,后果是人越多每一只越不值得躲:绕开一只贴脸的大史莱姆要多走一格
     * (成本约 4.6),而场上四只时绕开只省下 4.49,恰好压在天平上——"有时候绕得开,有时候
     * 直接从它身上穿过去"。穿过去就被顶住、每刻挨接触伤害。
     */
    @Test
    void dangerAddsUpInsteadOfAveragingOut() {
        Threat one = threat(0, 0, 3.0);
        Threat two = threat(0, 6, 3.0);
        double alone = avoid(one).heuristic(2, 64, 0);
        double crowded = avoid(one, two).heuristic(2, 64, 0);
        assertTrue(crowded > alone, "多一只怪不该让这一格变便宜");

        // 那一只自己的贡献不因为旁边多了谁而缩水
        double contribution = crowded - avoid(two).heuristic(2, 64, 0);
        assertEquals(alone, contribution, 1e-9);
    }

    /** 汇率只放大代价,不改变次序。 */
    @Test
    void theExchangeRateScalesTheField() {
        Threat t = threat(0, 0, 3.0);
        double weak = new GoalAvoidEntities(1.0, t).heuristic(2, 64, 0);
        double strong = new GoalAvoidEntities(40.0, t).heuristic(2, 64, 0);
        assertEquals(weak * 40.0, strong, 1e-9);
    }

    @Test
    void aRadiusCannotBeNegative() {
        assertThrows(IllegalArgumentException.class, () -> new Threat(0, 0, 0, -1.0));
    }

    @Test
    void avoidingNothingIsNotAGoal() {
        assertThrows(IllegalArgumentException.class, () -> new GoalAvoidEntities(1.0));
    }
}
