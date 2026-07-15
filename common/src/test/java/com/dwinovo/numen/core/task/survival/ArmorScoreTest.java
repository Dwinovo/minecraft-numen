package com.dwinovo.numen.core.task.survival;

import com.dwinovo.numen.core.task.TaskChain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the {@link ArmorScore} enchantment arithmetic — no Minecraft.
 * The {@code ItemStack}-facing entry points (attribute read, curse check, slot
 * resolution) can't run headless (their argument types don't load without a
 * bootstrapped game), so these exercise the int core that
 * {@link ArmorScore#score} composes, plus the chain's priority placement.
 */
class ArmorScoreTest {

    // ---- main protection line ----

    @Test
    void mainProtectionLineIsWeighted() {
        // Protection IV on a non-legs piece: 3 × 4 = 12.
        assertEquals(12, ArmorScore.enchantScore(false, 4, 0, 0, 0, 0, 0));
        // A secondary line counts once per level.
        assertEquals(3, ArmorScore.enchantScore(false, 0, 1, 1, 1, 0, 0));
    }

    @Test
    void protectionOutranksSpecializedLinesOffLegs() {
        int protectionIV = ArmorScore.enchantScore(false, 4, 0, 0, 0, 0, 0);
        int blastIV = ArmorScore.enchantScore(false, 0, 4, 0, 0, 0, 0);
        assertTrue(protectionIV > blastIV);
    }

    // ---- leggings prefer blast protection ----

    @Test
    void legsSwapTheMainLineToBlast() {
        // Blast Protection IV on legs takes the 3× weight …
        assertEquals(12, ArmorScore.enchantScore(true, 0, 4, 0, 0, 0, 0));
        // … and plain Protection drops to the 1× secondary weight there.
        assertEquals(4, ArmorScore.enchantScore(true, 4, 0, 0, 0, 0, 0));
    }

    @Test
    void blastLeggingsBeatProtectionLeggings() {
        int blastLegs = ArmorScore.enchantScore(true, 0, 4, 0, 0, 0, 0);
        int protLegs = ArmorScore.enchantScore(true, 4, 0, 0, 0, 0, 0);
        assertTrue(blastLegs > protLegs);
    }

    // ---- longevity ----

    @Test
    void longevityWeights() {
        // Unbreaking counts 1× per level, Mending 2× per level.
        assertEquals(3, ArmorScore.enchantScore(false, 0, 0, 0, 0, 3, 0));
        assertEquals(2, ArmorScore.enchantScore(false, 0, 0, 0, 0, 0, 1));
    }

    @Test
    void fullKitCompositeSum() {
        // Protection IV + Fire Prot II + Unbreaking III + Mending I helmet:
        // 3*4 + 2 + 3 + 2*1 = 19.
        assertEquals(19, ArmorScore.enchantScore(false, 4, 0, 2, 0, 3, 1));
    }

    @Test
    void unenchantedScoresZero() {
        assertEquals(0, ArmorScore.enchantScore(false, 0, 0, 0, 0, 0, 0));
        assertEquals(0, ArmorScore.enchantScore(true, 0, 0, 0, 0, 0, 0));
    }

    // ---- priority placement ----

    @Test
    void armorPrioritySitsBetweenLlmAndUnstuck() {
        // Above the LLM base (an idle body does gear up) but below EVERY genuine
        // survival response, unstuck included — armor is never time-critical.
        assertTrue(SurvivalDecisions.ARMOR_PRIORITY > TaskChain.LLM_BASE_PRIORITY);
        assertTrue(SurvivalDecisions.ARMOR_PRIORITY < SurvivalDecisions.UNSTUCK_PRIORITY);
    }
}
