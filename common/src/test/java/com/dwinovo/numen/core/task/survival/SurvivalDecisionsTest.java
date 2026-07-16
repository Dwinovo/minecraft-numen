package com.dwinovo.numen.core.task.survival;

import com.dwinovo.numen.task.TaskChain;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions.ThreatResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the survival decision math — no Minecraft. */
class SurvivalDecisionsTest {

    private static final float NEG = Float.NEGATIVE_INFINITY;

    // ---- food ----

    @Test
    void noFoodIsAlwaysDormant() {
        // Even starving with no food on hand, the chain stays dormant (nothing to do).
        assertEquals(NEG, SurvivalDecisions.foodPriority(0, 20.0f, false));
        assertEquals(NEG, SurvivalDecisions.foodPriority(0, 1.0f, false));
    }

    @Test
    void fullAndHealthyIsDormantEvenWithFood() {
        assertEquals(NEG, SurvivalDecisions.foodPriority(20, 20.0f, true));
        assertEquals(NEG, SurvivalDecisions.foodPriority(18, 20.0f, true));
    }

    @Test
    void hungryWithFoodFires() {
        assertEquals(SurvivalDecisions.FOOD_HUNGER_PRIORITY,
                SurvivalDecisions.foodPriority(6, 20.0f, true));
        assertEquals(SurvivalDecisions.FOOD_HUNGER_PRIORITY,
                SurvivalDecisions.foodPriority(0, 20.0f, true));
    }

    @Test
    void hurtAndNotFullFiresHigherThanPlainHunger() {
        // Low health + slightly hungry: eat to re-enable regen, and it should outrank
        // ordinary hunger so a hurt body prioritizes healing food.
        float regen = SurvivalDecisions.foodPriority(16, 6.0f, true);
        assertEquals(SurvivalDecisions.FOOD_REGEN_PRIORITY, regen);
        assertTrue(regen > SurvivalDecisions.FOOD_HUNGER_PRIORITY);
    }

    @Test
    void hurtButFullFoodDoesNotFireForRegen() {
        // Regen already runs at food >= 18; nothing to gain by eating.
        assertEquals(NEG, SurvivalDecisions.foodPriority(20, 4.0f, true));
    }

    // ---- threat fight/flee ----

    @Test
    void noThreatIsNone() {
        assertEquals(ThreatResponse.NONE,
                SurvivalDecisions.decideThreatResponse(false, 20.0f, true));
        assertEquals(NEG, SurvivalDecisions.mobDefensePriority(false));
    }

    @Test
    void healthyArmedFightsBack() {
        assertEquals(ThreatResponse.FIGHT,
                SurvivalDecisions.decideThreatResponse(true, 20.0f, true));
        assertEquals(SurvivalDecisions.MOB_DEFENSE_PRIORITY,
                SurvivalDecisions.mobDefensePriority(true));
    }

    @Test
    void lowHealthFleesEvenWhenArmed() {
        assertEquals(ThreatResponse.FLEE,
                SurvivalDecisions.decideThreatResponse(true, 4.0f, true));
    }

    @Test
    void unarmedFleesEvenWhenHealthy() {
        assertEquals(ThreatResponse.FLEE,
                SurvivalDecisions.decideThreatResponse(true, 20.0f, false));
    }

    // ---- MLG ----

    @Test
    void onGroundNeverFires() {
        assertEquals(NEG, SurvivalDecisions.mlgPriority(true, 100.0, true));
    }

    @Test
    void shortFallDoesNotFire() {
        assertEquals(NEG, SurvivalDecisions.mlgPriority(false, 2.0, true));
    }

    @Test
    void lethalFallWithSaveFires() {
        assertEquals(SurvivalDecisions.MLG_PRIORITY,
                SurvivalDecisions.mlgPriority(false, 10.0, true));
    }

    @Test
    void lethalFallWithoutSaveIsDormant() {
        // No bucket / soft block → nothing to do, don't grab the body pointlessly.
        assertEquals(NEG, SurvivalDecisions.mlgPriority(false, 50.0, false));
    }

    // ---- breath (surface-for-air) ----

    @Test
    void submergedWithLowAirFires() {
        assertEquals(SurvivalDecisions.BREATH_PRIORITY,
                SurvivalDecisions.breathPriority(true, SurvivalDecisions.LOW_AIR_TICKS));
    }

    @Test
    void submergedWithFreshAirIsDormant() {
        // A normal head-bob while swimming must not grab the body.
        assertEquals(NEG, SurvivalDecisions.breathPriority(true, 300));
    }

    @Test
    void headAboveWaterIsDormantEvenWithNoAir() {
        // Air refills on its own once the head is out — nothing to do.
        assertEquals(NEG, SurvivalDecisions.breathPriority(false, 0));
    }

    // ---- ranking invariant (the documented hierarchy) ----

    @Test
    void breathOutranksMobDefenseButNotMlg() {
        // Drowning is a hard timer: surface first, fight after. A falling body
        // still beats it (you can't swim in mid-air).
        assertTrue(SurvivalDecisions.BREATH_PRIORITY > SurvivalDecisions.MOB_DEFENSE_PRIORITY);
        assertTrue(SurvivalDecisions.MLG_PRIORITY > SurvivalDecisions.BREATH_PRIORITY);
    }

    @Test
    void priorityRankingIsMlgOverMobOverFoodOverUnstuckOverLlm() {
        assertTrue(SurvivalDecisions.MLG_PRIORITY > SurvivalDecisions.MOB_DEFENSE_PRIORITY);
        assertTrue(SurvivalDecisions.MOB_DEFENSE_PRIORITY > SurvivalDecisions.FOOD_REGEN_PRIORITY);
        assertTrue(SurvivalDecisions.FOOD_REGEN_PRIORITY > SurvivalDecisions.FOOD_HUNGER_PRIORITY);
        assertTrue(SurvivalDecisions.FOOD_HUNGER_PRIORITY > SurvivalDecisions.UNSTUCK_PRIORITY);
        assertTrue(SurvivalDecisions.UNSTUCK_PRIORITY > TaskChain.LLM_BASE_PRIORITY);
    }
}
