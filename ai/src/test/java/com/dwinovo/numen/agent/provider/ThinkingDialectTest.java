package com.dwinovo.numen.agent.provider;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 思考开关出向:统一力度 → 各家方言的翻译。每种方言形态 × 开/关都要有形。
 */
class ThinkingDialectTest {

    private static JsonObject apply(String format, String effort) {
        JsonObject body = new JsonObject();
        new OpenAIProvider("site", "https://x/v1", format).applyReasoning(body, effort);
        return body;
    }

    @Test
    void effortFormatSendsReasoningEffort() {
        assertEquals("high", apply(LlmProvider.THINKING_EFFORT, "high")
                .get("reasoning_effort").getAsString());
    }

    @Test
    void effortFormatStaysSilentOnOff() {
        // effort 方言没有"关"的线格式:off 不发任何参数,而不是发个怪值。
        assertEquals(0, apply(LlmProvider.THINKING_EFFORT, "off").size());
    }

    @Test
    void effortNestedFormatWrapsInReasoningObject() {
        JsonObject body = apply(LlmProvider.THINKING_EFFORT_NESTED, "low");
        assertEquals("low", body.getAsJsonObject("reasoning").get("effort").getAsString());
        assertFalse(body.has("reasoning_effort"));
        assertEquals(0, apply(LlmProvider.THINKING_EFFORT_NESTED, "off").size());
    }

    @Test
    void thinkingTypeFormatTogglesEnabledDisabled() {
        assertEquals("enabled", apply(LlmProvider.THINKING_TYPE, "medium")
                .getAsJsonObject("thinking").get("type").getAsString());
        assertEquals("disabled", apply(LlmProvider.THINKING_TYPE, "off")
                .getAsJsonObject("thinking").get("type").getAsString());
    }

    @Test
    void enableBoolFormatTogglesBoolean() {
        assertTrue(apply(LlmProvider.THINKING_ENABLE_BOOL, "high")
                .get("enable_thinking").getAsBoolean());
        assertFalse(apply(LlmProvider.THINKING_ENABLE_BOOL, "off")
                .get("enable_thinking").getAsBoolean());
    }

    @Test
    void noneFormatNeverSendsAnything() {
        for (String effort : new String[]{"low", "medium", "high", "off"}) {
            assertEquals(0, apply(LlmProvider.THINKING_NONE, effort).size(), effort);
        }
    }

    @Test
    void subclassDialectsAreFixed() {
        JsonObject ds = new JsonObject();
        new DeepSeekProvider().applyReasoning(ds, "high");
        assertEquals("enabled", ds.getAsJsonObject("thinking").get("type").getAsString());

        JsonObject ms = new JsonObject();
        new MoonshotProvider().applyReasoning(ms, "high");
        assertEquals(0, ms.size());
    }

    @Test
    void blankFormatFallsBackToEffort() {
        JsonObject body = new JsonObject();
        new OpenAIProvider("site", "https://x/v1", "").applyReasoning(body, "low");
        assertEquals("low", body.get("reasoning_effort").getAsString());
    }
}
