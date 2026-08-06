package com.dwinovo.numen.core.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 什么时候可以挥这一下。 */
class SwingTest {

    @Test
    void reachNeverFallsBelowTheVanillaFloor() {
        assertEquals(4.0, Swing.reachOf(2.5), 1e-9);
        assertEquals(6.0, Swing.reachOf(6.0), 1e-9);
    }

    @Test
    void aFullyChargedSwingGoesOut() {
        assertTrue(Swing.mayStrike(false, false, 1.0f));
    }

    /** 冷却没走完就出手,伤害按充能打折,白挥。 */
    @Test
    void anUnchargedSwingWaits() {
        assertFalse(Swing.mayStrike(false, false, 0.5f));
    }

    /** 打在受击无敌帧里伤害会被吞掉——等它恢复再打,总伤害更高。 */
    @Test
    void aTargetStillInItsInvulnerabilityWindowIsNotHit() {
        assertFalse(Swing.mayStrike(false, true, 1.0f));
    }

    /** 换手当刻不出手:属性还没结算完,这一下会按旧武器算。 */
    @Test
    void theTickAWeaponIsSwappedInIsSkipped() {
        assertFalse(Swing.mayStrike(true, false, 1.0f));
    }

    /**
     * 阈值必须高过原版判暴击的 0.9,否则"可以挥了"会先于"能暴击"成立,
     * 她就永远打不出暴击——那是白丢一半伤害。
     */
    @Test
    void theThresholdStaysAboveTheVanillaCriticalHitLine() {
        assertTrue(Swing.ATTACK_READY > 0.9f, "原版 Player.attack 要求充能 > 0.9 才判暴击");
        assertTrue(Swing.mayStrike(false, false, Swing.ATTACK_READY));
    }
}
