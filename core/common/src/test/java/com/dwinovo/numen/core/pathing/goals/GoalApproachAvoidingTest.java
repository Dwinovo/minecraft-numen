package com.dwinovo.numen.core.pathing.goals;

import com.dwinovo.numen.core.pathing.goals.GoalAvoidEntities.Threat;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 走到目标跟前,路上绕开别的威胁。
 *
 * <p>钉的是两条:<b>站位好不好是到达条件的一部分</b>(别的怪够得着的格子不算到位),
 * 以及<b>贴着威胁走更贵</b>。
 */
class GoalApproachAvoidingTest {

    /** 危险半径给 0 的威胁只进边成本,不挡到达。 */
    private static Threat fieldOnly(int x, int z) {
        return new Threat(x + 0.5, 64.0, z + 0.5, 0.0);
    }

    private static Threat threat(int x, int z, double radius) {
        return new Threat(x + 0.5, 64.0, z + 0.5, radius);
    }

    private static Goal approachTo(int x, int y, int z) {
        return new GoalBlock(x, y, z);
    }

    /**
     * 估价是<b>纯距离</b>。躲开谁不进估价,进的是边成本({@code Avoidance.forGoal})。
     *
     * <p>加在估价里就得在"强到能绕开"和"弱到不搞坏搜索"之间挑,两头都做不到:估价必须是
     * 剩余成本的下界 A* 才敢剪枝,而"离怪多近"不随接近目标而下降。
     */
    @Test
    void theEstimateIsPureDistance() {
        Goal plain = approachTo(10, 64, 0);
        Goal wrapped = new GoalApproachAvoiding(plain,
                new GoalAvoidEntities(40.0, threat(5, 0, 3.0)));
        assertEquals(plain.heuristic(0, 64, 0), wrapped.heuristic(0, 64, 0), 1e-9);
        assertEquals(plain.heuristic(5, 64, 1), wrapped.heuristic(5, 64, 1), 1e-9);
    }

    /** 威胁表要交得出去——搜索器拿它建边成本的惩罚球。 */
    @Test
    void theThreatsAreReadableBySearch() {
        var field = new GoalAvoidEntities(40.0, threat(5, 0, 3.0));
        Goal wrapped = new GoalApproachAvoiding(approachTo(10, 64, 0), field);
        assertEquals(1, ((GoalApproachAvoiding) wrapped).repulsion().threats().length);
    }

    /** 没有要绕的东西就别白包一层——每个节点都要算势场,不该为空集付这个钱。 */
    @Test
    void nothingToAvoidReturnsThePlainApproach() {
        Goal plain = approachTo(5, 64, 5);
        assertSame(plain, GoalApproachAvoiding.wrap(plain, 40.0));
    }

    /**
     * 到达要两项都点头:走到了目标跟前,而且脚下这一格不在别人的危险半径里。
     * 只问吸引项的时候,势场只影响路线不影响落脚点——而一旦到达,A* 再不搜索,那份估价
     * 一次也用不上,她就停在被围着的那一格。
     */
    @Test
    void arrivalAlsoNeedsRoomFromTheOthers() {
        Goal plain = new GoalNear(new BlockPos(10, 64, 0), 3);
        var crowded = new GoalApproachAvoiding(plain,
                new GoalAvoidEntities(40.0, threat(9, 0, 2.5)));

        assertTrue(plain.isInGoal(9, 64, 1));            // 吸引项:到了
        assertFalse(crowded.isInGoal(9, 64, 1));         // 但那一格在另一只的危险半径里
        assertTrue(crowded.isInGoal(12, 64, 0));         // 同样到得了目标,而且出了半径
    }

    /** 危险半径为零的东西只进估价,不挡到达。 */
    @Test
    void aFieldOnlyThreatDoesNotBlockArrival() {
        Goal plain = new GoalNear(new BlockPos(10, 64, 0), 3);
        var wrapped = new GoalApproachAvoiding(plain,
                new GoalAvoidEntities(40.0, fieldOnly(9, 0)));
        assertTrue(wrapped.isInGoal(9, 64, 1));
    }
}
