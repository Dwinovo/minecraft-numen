package com.dwinovo.numen.core.pathing.goals;

import com.dwinovo.numen.core.pathing.goals.GoalAvoidEntities.Threat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 走到目标跟前,路上绕开别的威胁。
 *
 * <p>钉的是两条:<b>绕路不改变终点</b>(到达判定只归吸引项),以及<b>贴着威胁走更贵</b>。
 */
class GoalApproachAvoidingTest {

    private static Goal approachTo(int x, int y, int z) {
        return new GoalBlock(x, y, z);
    }

    /** 到没到只问吸引项。势场再强也不能让"到了"变成"没到"。 */
    @Test
    void arrivalIsDecidedByTheApproachAlone() {
        Goal g = new GoalApproachAvoiding(approachTo(10, 64, 0),
                new GoalAvoidEntities(0.0, 40.0, new Threat(10, 64, 0, 5.0, false)));
        assertTrue(g.isInGoal(10, 64, 0), "站到目标格就算到了,哪怕那里势能很高");
    }

    /** 同一个位置,旁边有威胁就该更贵——这才会让 A* 去绕。 */
    @Test
    void walkingPastAThreatCostsMore() {
        Goal plain = approachTo(20, 64, 0);
        Goal avoiding = new GoalApproachAvoiding(approachTo(20, 64, 0),
                new GoalAvoidEntities(0.0, 40.0, new Threat(10, 64, 0, 1.0, false)));
        assertTrue(avoiding.heuristic(10, 64, 0) > plain.heuristic(10, 64, 0));
    }

    /** 离威胁远的那条路更便宜——绕开才有意义。 */
    @Test
    void theDetourIsCheaperThanThroughTheThreat() {
        Goal g = new GoalApproachAvoiding(approachTo(20, 64, 0),
                new GoalAvoidEntities(0.0, 40.0, new Threat(10, 64, 0, 1.0, false)));
        assertTrue(g.heuristic(10, 64, 6) < g.heuristic(10, 64, 0), "从旁边绕比直穿便宜");
    }

    /** 没有要绕的东西就别白包一层——每个节点都要算势场,不该为空集付这个钱。 */
    @Test
    void nothingToAvoidReturnsThePlainApproach() {
        Goal plain = approachTo(5, 64, 5);
        assertSame(plain, GoalApproachAvoiding.wrap(plain, 40.0));
    }

    /** 包了之后仍然认得出终点在哪。 */
    @Test
    void wrappingKeepsTheSameArrivalCells() {
        Goal plain = approachTo(5, 64, 5);
        Goal wrapped = GoalApproachAvoiding.wrap(plain, 40.0, new Threat(0, 64, 0, 1.0, false));
        assertEquals(plain.isInGoal(5, 64, 5), wrapped.isInGoal(5, 64, 5));
        assertEquals(plain.isInGoal(6, 64, 5), wrapped.isInGoal(6, 64, 5));
    }
}
