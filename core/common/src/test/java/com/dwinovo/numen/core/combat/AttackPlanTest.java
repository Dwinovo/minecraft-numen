package com.dwinovo.numen.core.combat;

import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.core.combat.AttackPlan.Stance;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 这一刻该怎么打。
 *
 * <p>钉的是两条:<b>够得着就用近战</b>(那同时也是省箭的那一支),以及<b>会炸的东西一律不贴身</b>
 * ——后者是今天真会把她炸死的那条路。
 */
class AttackPlanTest {

    private static AttackPlan.Situation at(double distance, boolean reachable, boolean hasRanged) {
        return new AttackPlan.Situation(distance, 4.0, reachable, hasRanged, false, 0.0);
    }

    private static AttackPlan.Situation explosiveAt(double distance, boolean hasRanged) {
        return new AttackPlan.Situation(distance, 4.0, true, hasRanged, true, 7.5);
    }

    // ==================== 够不够得着 ====================

    @Test
    void withinReachSheSwings() {
        assertEquals(Stance.MELEE, AttackPlan.decide(at(3.0, true, true)));
    }

    /** 有弓也照样走过去砍:走得到就不该花箭。 */
    @Test
    void reachableButFarSheWalksThereRatherThanSpendArrows() {
        assertEquals(Stance.CLOSE_IN, AttackPlan.decide(at(20.0, true, true)));
    }

    /** 走不到才动用远程——恶魂、悬崖对面、柱子上的东西都归这一支。 */
    @Test
    void unreachableIsWhatBowsAreFor() {
        assertEquals(Stance.RANGED, AttackPlan.decide(at(20.0, false, true)));
    }

    /** 走不到又没有远程手段:说打不了,而不是原地耗着。 */
    @Test
    void unreachableWithNoBowIsGivenUpRatherThanChasedForever() {
        assertEquals(Stance.ABANDON, AttackPlan.decide(at(20.0, false, false)));
    }

    // ==================== 该不该靠近 ====================

    /**
     * 会炸的东西<b>近在眼前也不打</b>。这一条与"够不够得着"无关——爬行者恰恰是完全够得着的,
     * 只按距离判就会直接判近战,那正是会被炸的那条路。
     */
    @Test
    void anExplosiveTargetIsNeverMeleedEvenPointBlank() {
        assertEquals(Stance.AVOID, AttackPlan.decide(explosiveAt(1.0, true)));
        assertEquals(Stance.AVOID, AttackPlan.decide(explosiveAt(1.0, false)));
    }

    /** 退到安全线以外,才轮到出手。 */
    @Test
    void pastTheSafeLineSheShoots() {
        assertEquals(Stance.RANGED, AttackPlan.decide(explosiveAt(8.0, true)));
    }

    /** 没有弓就是打不了它——不是"那就上去砍吧"。 */
    @Test
    void anExplosiveTargetWithoutABowIsAbandonedNotCharged() {
        assertEquals(Stance.ABANDON, AttackPlan.decide(explosiveAt(8.0, false)));
    }

    /** 安全线本身算安全:边界上不该再退一步,否则她会在这一格上来回改主意。 */
    @Test
    void theSafeLineItselfCounts() {
        assertEquals(Stance.RANGED, AttackPlan.decide(explosiveAt(7.5, true)));
    }

    // ==================== 边界 ====================

    /** 正好在够到距离上就该挥,不该再走一步——差一点点就会变成贴着目标反复起步。 */
    @Test
    void exactlyAtReachIsCloseEnough() {
        assertEquals(Stance.MELEE, AttackPlan.decide(at(4.0, true, false)));
    }
}
