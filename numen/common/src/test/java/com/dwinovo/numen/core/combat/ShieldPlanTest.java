package com.dwinovo.numen.core.combat;

import com.dwinovo.numen.core.combat.ShieldPlan.Decision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 举不举盾。判据与 PR #13 的 ShieldCombatPolicy 同源。 */
class ShieldPlanTest {

    /** 手上正用着别的(拉弓、吃东西):这一刻别碰盾,两者抢同一个 useItem。 */
    @Test
    void anotherItemInUseWins() {
        assertEquals(Decision.WAIT, ShieldPlan.decide(true, true, false, false));
        assertEquals(Decision.WAIT, ShieldPlan.decide(true, true, false, true));
    }

    /** 冷却没好就举起来 —— 那段窗口本来什么都做不了,减速的代价正落在这儿。 */
    @Test
    void offCooldownWindowIsWhenTheShieldGoesUp() {
        assertEquals(Decision.RAISE, ShieldPlan.decide(true, false, false, false));
    }

    /** 攻击充能好了就该砍,不必再举。 */
    @Test
    void readyToSwingMeansNoNewBlock() {
        assertEquals(Decision.PROCEED, ShieldPlan.decide(true, false, false, true));
    }

    /** 举着的时候:冷却好了放下,没好就接着举。 */
    @Test
    void whileRaisedItTracksTheSwingCooldown() {
        assertEquals(Decision.HOLD, ShieldPlan.decide(true, false, true, false));
        assertEquals(Decision.RELEASE, ShieldPlan.decide(true, false, true, true));
    }

    /** 没盾、或者被斧子破了还在冷却:不关盾的事。 */
    @Test
    void noUsableShieldMeansCarryOn() {
        assertEquals(Decision.PROCEED, ShieldPlan.decide(false, false, false, false));
    }
}
