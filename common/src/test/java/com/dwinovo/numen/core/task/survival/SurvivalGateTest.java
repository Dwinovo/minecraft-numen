package com.dwinovo.numen.core.task.survival;

import com.dwinovo.numen.task.ChainScheduler;

import com.dwinovo.numen.core.task.SurvivalConfig;
import com.dwinovo.numen.task.TaskChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate is the whole safety story: with {@link SurvivalConfig} OFF (the default),
 * every survival chain is a strict no-op because the literal first line of each
 * survival {@code getPriority} is
 * {@snippet : if (!SurvivalConfig.enabled()) return Float.NEGATIVE_INFINITY; }
 * so it returns {@link SurvivalDecisions#DORMANT} before touching the body — the
 * scheduler can never pick a survival chain over the LLM chain and runtime behavior
 * is identical to a build without this layer.
 *
 * <p>The chains themselves can't be instantiated in a headless test (their fields
 * reference {@code NumenPlayer} / Minecraft types that are {@code compileOnly}), so
 * this asserts the gate itself and the dormant-value contract; the "getPriority
 * short-circuits on the gate" step is guaranteed structurally by that first line.
 */
class SurvivalGateTest {

    @AfterEach
    void resetGate() {
        SurvivalConfig.setEnabled(false);   // never leak an enabled gate into another test
    }

    @Test
    void gateDefaultsOff() {
        assertFalse(SurvivalConfig.enabled());
    }

    @Test
    void setEnabledTogglesTheFlag() {
        SurvivalConfig.setEnabled(true);
        assertTrue(SurvivalConfig.enabled());
        SurvivalConfig.setEnabled(false);
        assertFalse(SurvivalConfig.enabled());
    }

    @Test
    void dormantValueIsBelowTheLlmBase() {
        // The value each survival getPriority returns while the gate is off is strictly
        // below the LLM base priority, so ChainScheduler.select can never pick it.
        assertTrue(SurvivalDecisions.DORMANT < TaskChain.LLM_BASE_PRIORITY);
        assertEquals(Float.NEGATIVE_INFINITY, SurvivalDecisions.DORMANT);
    }
}
