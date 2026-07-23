package com.dwinovo.numen.core.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FishingRulesTest {

    @Test
    void ignoresTheHookBeforeVanillaOpensTheBiteWindow() {
        assertFalse(FishCompanionTask.isBiteWindow(0));
        assertFalse(FishCompanionTask.isBiteWindow(-1));
    }

    @Test
    void detectsVanillasPrivateNibbleWindowDirectly() {
        assertTrue(FishCompanionTask.isBiteWindow(1));
        assertTrue(FishCompanionTask.isBiteWindow(40));
    }

    @Test
    void ballisticPitchLandsAtTheRequestedHeight() {
        double pitch = FishCompanionTask.solvePitchDegrees(6.3, -1.62);
        assertEquals(-1.62,
                FishCompanionTask.trajectoryHeightAtDistance(6.3, pitch), 1.0e-5);
    }

    @Test
    void longerCastsNeedMoreElevationAboveTheWater() {
        double shortPitch = FishCompanionTask.solvePitchDegrees(4.3, -1.62);
        double longPitch = FishCompanionTask.solvePitchDegrees(9.3, -1.62);
        assertTrue(longPitch < shortPitch);
    }
}
