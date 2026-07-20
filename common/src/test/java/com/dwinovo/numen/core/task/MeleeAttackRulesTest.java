package com.dwinovo.numen.core.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeleeAttackRulesTest {

    @Test
    void targetOrderIsNearestThenId() {
        assertTrue(compare(4, 9, 5, 1) < 0);
        assertTrue(compare(4, 8, 4, 9) < 0);
    }

    @Test
    void entityReachUsesFollowerDefaultUnlessNativeRangeIsLonger() {
        assertEquals(4.0, MeleeAttackCompanionTask.effectiveEntityReach(3.0), 0.0001);
        assertEquals(4.5, MeleeAttackCompanionTask.effectiveEntityReach(4.5), 0.0001);
    }

    @Test
    void approachRadiusKeepsTheBodyInsideEntityReach() {
        assertEquals(3.0, MeleeAttackCompanionTask.approachRadius(4.0), 0.0001);
        assertEquals(3.5, MeleeAttackCompanionTask.approachRadius(4.5), 0.0001);
        assertEquals(0.5, MeleeAttackCompanionTask.approachRadius(1.0), 0.0001);
    }

    @Test
    void backOffOnlyWhileCrowdedAndWaitingToSwing() {
        assertTrue(MeleeAttackCompanionTask.shouldBackOffBeforeSwing(true, false, false));
        assertTrue(MeleeAttackCompanionTask.shouldBackOffBeforeSwing(true, true, true));
        assertFalse(MeleeAttackCompanionTask.shouldBackOffBeforeSwing(true, false, true));
        assertFalse(MeleeAttackCompanionTask.shouldBackOffBeforeSwing(false, true, false));
    }

    @Test
    void entityReachUsesStrictSquaredDistance() {
        assertTrue(MeleeAttackCompanionTask.isWithinEntityReach(15.9, 4.0));
        assertFalse(MeleeAttackCompanionTask.isWithinEntityReach(16.0, 4.0));
    }

    @Test
    void nativeAttackWaitsForWeaponTargetAndCooldownReadiness() {
        assertTrue(MeleeAttackCompanionTask.canStartNativeAttack(false, false, true));
        assertFalse(MeleeAttackCompanionTask.canStartNativeAttack(true, false, true));
        assertFalse(MeleeAttackCompanionTask.canStartNativeAttack(false, true, true));
        assertFalse(MeleeAttackCompanionTask.canStartNativeAttack(false, false, false));
    }

    private static int compare(double distanceA, int idA, double distanceB, int idB) {
        return MeleeAttackCompanionTask.compareTargetKeys(distanceA, idA, distanceB, idB);
    }
}
