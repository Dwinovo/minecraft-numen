package com.dwinovo.numen.core.task.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 拉弓与松手的判据(原版蓄力公式与放箭时机)。 */
class RangedShotRulesTest {

    /** 原版:拉满要一秒,再拉不会更强。 */
    @Test
    void aBowReachesFullPowerAtOneSecond() {
        assertEquals(1.0, RangedShot.bowPowerForTicks(20), 1e-9);
        assertEquals(1.0, RangedShot.bowPowerForTicks(40), 1e-9, "拉过头也就是满");
    }

    @Test
    void aBarelyDrawnBowIsWeak() {
        assertTrue(RangedShot.bowPowerForTicks(3) < 0.4);
        assertEquals(0.0, RangedShot.bowPowerForTicks(0), 1e-9);
    }

    @Test
    void powerRisesWithTheDraw() {
        assertTrue(RangedShot.bowPowerForTicks(5) < RangedShot.bowPowerForTicks(10));
        assertTrue(RangedShot.bowPowerForTicks(10) < RangedShot.bowPowerForTicks(15));
    }

    /** 对准了但还没拉够,松手就是一支软箭——两个条件都要满足。 */
    @Test
    void bothAimAndDrawAreRequiredBeforeLoosing() {
        assertTrue(RangedShot.canRelease(0.5, 15, 15));
        assertFalse(RangedShot.canRelease(0.5, 10, 15), "拉不够");
        assertFalse(RangedShot.canRelease(20.0, 15, 15), "没对准");
    }

    /** 夹角阈值就是判据本身,擦着边也算对准。 */
    @Test
    void theAimThresholdItselfCounts() {
        assertTrue(RangedShot.canRelease(RangedShot.AIM_THRESHOLD_DEGREES, 15, 15));
    }
}
