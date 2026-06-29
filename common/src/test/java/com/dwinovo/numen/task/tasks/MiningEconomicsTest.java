package com.dwinovo.numen.task.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The anti-shaft bias: stone below the feet must lose to stone on the
 * hillside, while a deep ore with no lateral alternative must stay viable.
 */
class MiningEconomicsTest {

    @Test
    void targetsAtOrAboveFeetScoreTheirDistance() {
        assertEquals(10.0, MiningEconomics.score(10.0, 64, 64));
        assertEquals(10.0, MiningEconomics.score(10.0, 64, 70), "height above is free");
    }

    @Test
    void depthPaysPerBlockBelowFeet() {
        assertEquals(5.0 + 3 * MiningEconomics.DEPTH_PENALTY_PER_BLOCK,
                MiningEconomics.score(5.0, 64, 61));
    }

    @Test
    void lateralStoneBeatsTheShaft() {
        // Stone 4 below my feet (distance 4) vs stone 12 blocks sideways.
        double shaft = MiningEconomics.score(4.0, 64, 60);
        double hillside = MiningEconomics.score(12.0, 64, 64);
        assertTrue(hillside < shaft, "bulk collection must prefer the hillside");
    }

    @Test
    void deepOreStaysViableWhenNothingLateralExists() {
        // Diamond 30 below: penalised but finite — necessity still wins
        // because the alternative is "no target at all".
        double deep = MiningEconomics.score(30.0, 64, 34);
        assertTrue(deep < 1000, "depth bias is a preference, not a wall");
    }
}
