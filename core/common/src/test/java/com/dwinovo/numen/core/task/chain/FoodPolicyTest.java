package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.task.chain.FoodPolicy.Tier;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the {@link FoodPolicy} tier arithmetic — no Minecraft. The
 * Item/FoodProperties wrappers can't run headless (their argument types don't
 * load without a bootstrapped game), so these exercise the boolean/int core that
 * {@link FoodChain#bestEdibleSlot} feeds and resolves.
 */
class FoodPolicyTest {

    // ---- hard exclusion ----

    @Test
    void hardExcludedIsNeverRegardlessOfEffects() {
        // The unconditional exclusion wins whether or not the item also carries a
        // likely harmful effect (pufferfish: both; chorus fruit: neither).
        assertEquals(Tier.NEVER, FoodPolicy.classify(true, true));
        assertEquals(Tier.NEVER, FoodPolicy.classify(true, false));
    }

    @Test
    void hardExclusionHoldsEvenInFamine() {
        // A NEVER item enters no tier, so an inventory holding only hard-excluded
        // food resolves to "nothing to eat" even with the famine gate open.
        assertEquals(-1, FoodPolicy.resolveSlot(-1, -1, true));
    }

    // ---- regular tier: likely-harmful foods excluded ----

    @Test
    void likelyHarmfulIsFamineOnly() {
        assertEquals(Tier.FAMINE_ONLY, FoodPolicy.classify(false, true));
    }

    @Test
    void harmfulFoodIsNotPickedWhenNotStarving() {
        // Only likely-harmful food carried, famine gate closed: no pick.
        assertEquals(-1, FoodPolicy.resolveSlot(-1, 5, false));
    }

    @Test
    void cleanFoodIsRegularAndAlwaysWins() {
        assertEquals(Tier.REGULAR, FoodPolicy.classify(false, false));
        // A regular pick beats the famine fallback even when the gate is open.
        assertEquals(2, FoodPolicy.resolveSlot(2, 5, true));
        assertEquals(2, FoodPolicy.resolveSlot(2, 5, false));
    }

    // ---- famine relaxation ----

    @Test
    void starvingUnlocksTheFamineTier() {
        // Nothing regular carried + genuinely starving: the harmful food may be eaten.
        assertEquals(5, FoodPolicy.resolveSlot(-1, 5, true));
    }

    @Test
    void famineGateFollowsTheHungryLine() {
        assertTrue(FoodPolicy.famineUnlocked(SurvivalDecisions.HUNGRY_LEVEL));
        assertTrue(FoodPolicy.famineUnlocked(0));
        assertFalse(FoodPolicy.famineUnlocked(SurvivalDecisions.HUNGRY_LEVEL + 1));
    }

    @Test
    void nothingCarriedResolvesToNothing() {
        assertEquals(-1, FoodPolicy.resolveSlot(-1, -1, false));
    }
}
