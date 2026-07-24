package com.dwinovo.numen.core.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangedAttackRulesTest {

    @Test
    void usesVanillaBowPowerCurve() {
        assertEquals(0.0, RangedAttackCompanionTask.bowPowerForTicks(0), 1.0e-6);
        assertEquals(0.6875, RangedAttackCompanionTask.bowPowerForTicks(15), 1.0e-6);
        assertEquals(1.0, RangedAttackCompanionTask.bowPowerForTicks(20), 1.0e-6);
        assertEquals(1.0, RangedAttackCompanionTask.bowPowerForTicks(40), 1.0e-6);
    }

    @Test
    void startsFollowingFromAUsefulFiringBand() {
        assertEquals(24.0, RangedAttackCompanionTask.initialFollowRadius(5.0), 1.0e-6);
        assertEquals(24.0, RangedAttackCompanionTask.initialFollowRadius(8.0), 1.0e-6);
        assertEquals(34.0, RangedAttackCompanionTask.initialFollowRadius(33.0), 1.0e-6);
    }

    @Test
    void releasesOnlyAfterEnoughDrawAndAccurateAim() {
        assertTrue(RangedAttackCompanionTask.canRelease(1.5, 15, 15));
        assertFalse(RangedAttackCompanionTask.canRelease(1.6, 15, 15));
        assertFalse(RangedAttackCompanionTask.canRelease(1.0, 14, 15));
    }

    @Test
    void prefersNearestTargetThenStableRuntimeId() {
        assertTrue(RangedAttackCompanionTask.compareTargetKeys(9.0, 20, 16.0, 1) < 0);
        assertTrue(RangedAttackCompanionTask.compareTargetKeys(16.0, 20, 16.0, 30) < 0);
        assertTrue(RangedAttackCompanionTask.compareTargetKeys(25.0, 20, 16.0, 30) > 0);
    }
}

