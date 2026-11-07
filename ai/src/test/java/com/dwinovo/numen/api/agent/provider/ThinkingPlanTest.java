package com.dwinovo.numen.api.agent.provider;

import com.dwinovo.numen.api.agent.catalog.ThinkingLevel;
import com.dwinovo.numen.api.agent.catalog.ThinkingTable;
import com.dwinovo.numen.api.agent.provider.ChatCompat.ThinkingFormat;
import com.dwinovo.numen.api.agent.provider.ThinkingPlan.Wire.Kind;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dwinovo.numen.api.agent.catalog.ThinkingLevel.AUTO;
import static com.dwinovo.numen.api.agent.catalog.ThinkingLevel.HIGH;
import static com.dwinovo.numen.api.agent.catalog.ThinkingLevel.LOW;
import static com.dwinovo.numen.api.agent.catalog.ThinkingLevel.MAX;
import static com.dwinovo.numen.api.agent.catalog.ThinkingLevel.MEDIUM;
import static com.dwinovo.numen.api.agent.catalog.ThinkingLevel.OFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 每一档在这个型号、这个协议、这套兼容项下实际写成什么;请求体与界面问的是同一张表。 */
class ThinkingPlanTest {

    private static final String EFFORT = "{\"type\":\"effort\",\"values\":[\"low\",\"medium\",\"high\",\"max\"]}";
    private static final String BUDGET = "{\"type\":\"budget_tokens\",\"min\":1024}";
    private static final String TOGGLE = "{\"type\":\"toggle\"}";

    private static ThinkingTable table(int maxOutput, String... options) {
        JsonArray a = JsonParser.parseString("[" + String.join(",", options) + "]").getAsJsonArray();
        return ThinkingTable.parse(true, a, maxOutput);
    }

    private static ChatCompat chat(ThinkingFormat f) {
        return new ChatCompat(f, ChatCompat.MaxTokensField.MAX_TOKENS, false, true, false);
    }

    private static List<ThinkingLevel> levels(ThinkingPlan p) {
        return p.steps().stream().map(ThinkingPlan.Step::level).toList();
    }

    @Test
    void mixedAnthropicModelWritesBudgetsNotEffortWordsWhenNotAdaptive() {
        ThinkingTable t = table(64_000, EFFORT, BUDGET);   // claude-opus-4-5 的形状
        ThinkingPlan p = ThinkingPlan.of(t, new AnthropicCompat(false, true));
        assertEquals(List.of(AUTO, OFF, LOW, MEDIUM, HIGH, MAX), levels(p));
        assertEquals(new ThinkingPlan.Wire(Kind.BUDGET, null, 2_048), p.wireFor(LOW));
        assertEquals(new ThinkingPlan.Wire(Kind.BUDGET, null, 32_768), p.wireFor(MAX));
        assertEquals(Kind.OFF, p.wireFor(OFF).kind(), "Anthropic 的关显式写 disabled");
    }

    @Test
    void adaptiveAnthropicModelWritesEffortWords() {
        ThinkingPlan p = ThinkingPlan.of(table(0, EFFORT), new AnthropicCompat(true, true));
        assertEquals(List.of(AUTO, OFF, LOW, MEDIUM, HIGH, MAX), levels(p), "推理型号都有关");
        assertEquals(new ThinkingPlan.Wire(Kind.EFFORT, "high", 0), p.wireFor(HIGH));
    }

    @Test
    void budgetsNotBelowTheOutputLimitAreNotOffered() {
        ThinkingPlan p = ThinkingPlan.of(table(16_384, BUDGET), new AnthropicCompat(false, true));
        assertEquals(List.of(AUTO, OFF, LOW, MEDIUM), levels(p), "高=16384 已不小于上限");
    }

    @Test
    void switchOnlyEndpointsOfferOnlyOffAndOn() {
        for (ThinkingFormat f : List.of(ThinkingFormat.QWEN, ThinkingFormat.ZAI)) {
            ThinkingPlan withEffort = ThinkingPlan.of(table(0, EFFORT), chat(f));
            assertEquals(List.of(AUTO, MEDIUM), levels(withEffort), f + ":effort 发不出去,只有开");
            assertEquals(Kind.ON, withEffort.wireFor(MEDIUM).kind());
            ThinkingPlan withToggle = ThinkingPlan.of(table(0, TOGGLE), chat(f));
            assertEquals(List.of(AUTO, OFF, MEDIUM), levels(withToggle));
        }
    }

    @Test
    void effortOnlyEndpointsDropModelsThatOnlyHaveBudgets() {
        ThinkingPlan p = ThinkingPlan.of(table(64_000, BUDGET), chat(ThinkingFormat.OPENAI));
        assertTrue(p.autoOnly(), "预算发不出去,整栏不出");
        assertEquals(Kind.AUTO, p.wireFor(LOW).kind(), "存档里残留的档位按自动,不会假装生效");
        assertTrue(ThinkingPlan.of(table(0, TOGGLE), chat(ThinkingFormat.OPENAI)).autoOnly());
    }

    @Test
    void openRouterConvertsEverythingSoReasoningModelsAlwaysGetEffortLevels() {
        // 目录只给预算或开关:按缺省给低、中、高;关写 none
        for (String option : List.of(BUDGET, TOGGLE)) {
            ThinkingPlan p = ThinkingPlan.of(table(64_000, option), chat(ThinkingFormat.OPENROUTER));
            assertEquals(List.of(AUTO, OFF, LOW, MEDIUM, HIGH), levels(p), option);
            assertEquals(new ThinkingPlan.Wire(Kind.EFFORT, "medium", 0), p.wireFor(MEDIUM));
            assertEquals(new ThinkingPlan.Wire(Kind.OFF, "none", 0), p.wireFor(OFF));
        }
        // 目录给了 effort 取值就用那些
        ThinkingPlan given = ThinkingPlan.of(table(0, "{\"type\":\"effort\",\"values\":[\"low\",\"high\",\"xhigh\"]}"),
                chat(ThinkingFormat.OPENROUTER));
        assertEquals(List.of(AUTO, OFF, LOW, HIGH, MAX), levels(given));
        assertEquals("xhigh", given.wireFor(MAX).effort());
        // 不是推理型号:什么都没有
        assertTrue(ThinkingPlan.of(ThinkingTable.AUTO_ONLY, chat(ThinkingFormat.OPENROUTER)).autoOnly());
    }

    @Test
    void effortEndpointsListNoneAsOff() {
        ThinkingTable t = table(0, "{\"type\":\"effort\",\"values\":[\"none\",\"low\",\"high\"]}");
        ThinkingPlan p = ThinkingPlan.of(t, chat(ThinkingFormat.OPENAI));
        assertEquals(List.of(AUTO, OFF, LOW, HIGH), levels(p));
        assertEquals(new ThinkingPlan.Wire(Kind.OFF, "none", 0), p.wireFor(OFF));
        assertEquals(List.of(AUTO, OFF, LOW, HIGH), levels(ThinkingPlan.of(t, new ResponsesCompat(true))));
    }

    @Test
    void deepseekWritesTheSwitchAndEffortWhenTheModelHasThem() {
        ThinkingPlan both = ThinkingPlan.of(
                table(0, TOGGLE, "{\"type\":\"effort\",\"values\":[\"low\",\"high\",\"max\"]}"),
                chat(ThinkingFormat.DEEPSEEK));
        assertEquals(List.of(AUTO, OFF, LOW, HIGH, MAX), levels(both));
        assertEquals("max", both.wireFor(MAX).effort());
        ThinkingPlan toggleOnly = ThinkingPlan.of(table(0, TOGGLE), chat(ThinkingFormat.DEEPSEEK));
        assertEquals(List.of(AUTO, OFF, MEDIUM), levels(toggleOnly));
    }

    @Test
    void noThinkingFormatOrNoControlMeansAutoOnly() {
        assertTrue(ThinkingPlan.of(table(0, EFFORT), chat(ThinkingFormat.NONE)).autoOnly());
        assertTrue(ThinkingPlan.of(ThinkingTable.AUTO_ONLY, chat(ThinkingFormat.OPENAI)).autoOnly());
    }

    @Test
    void aLevelNotInThePlanFallsBackToAuto() {
        ThinkingPlan p = ThinkingPlan.of(table(0, "{\"type\":\"effort\",\"values\":[\"low\",\"high\"]}"),
                chat(ThinkingFormat.OPENAI));
        assertEquals(HIGH, p.effective(HIGH));
        assertEquals(AUTO, p.effective(MEDIUM));
        assertEquals(AUTO, p.effective(OFF));
        assertEquals(AUTO, p.effective(null));
    }

    @Test
    void requestBodiesCarryExactlyWhatThePlanSays() {
        // 只写开关的端点:开着时请求里只有一个开关,没有 effort
        ModelSpec model = new ModelSpec("m", table(0, EFFORT), true);
        JsonObject on = body(new OpenAIProvider(chat(ThinkingFormat.QWEN)), model, MEDIUM);
        assertTrue(on.get("enable_thinking").getAsBoolean() && !on.has("reasoning_effort"));
        // 档位不在计划里(存档残留)等于自动:什么都不发
        JsonObject stale = body(new OpenAIProvider(chat(ThinkingFormat.QWEN)), model, HIGH);
        assertTrue(!stale.has("enable_thinking"));
        // 混合型号走预算
        ModelSpec mixed = new ModelSpec("claude", table(64_000, EFFORT, BUDGET), true);
        JsonObject anthropic = body(new AnthropicProvider(new AnthropicCompat(false, true)), mixed, MEDIUM);
        assertEquals(8_192, anthropic.getAsJsonObject("thinking").get("budget_tokens").getAsInt());
        // OpenRouter 对只有预算的型号也写 reasoning.effort,关写 none
        ModelSpec budgetOnly = new ModelSpec("or", table(64_000, BUDGET), true);
        OpenAIProvider openRouter = new OpenAIProvider(chat(ThinkingFormat.OPENROUTER));
        assertEquals("low", body(openRouter, budgetOnly, LOW).getAsJsonObject("reasoning").get("effort").getAsString());
        assertEquals("none", body(openRouter, budgetOnly, OFF).getAsJsonObject("reasoning").get("effort").getAsString());
        assertTrue(!body(openRouter, budgetOnly, AUTO).has("reasoning"));
        // Anthropic:关显式写 disabled,自动不发
        JsonObject disabled = body(new AnthropicProvider(new AnthropicCompat(false, true)), mixed, OFF);
        assertEquals("disabled", disabled.getAsJsonObject("thinking").get("type").getAsString());
        assertTrue(!body(new AnthropicProvider(new AnthropicCompat(false, true)), mixed, AUTO).has("thinking"));
        // 只认 effort 的端点遇到只有预算的型号:什么都不发
        JsonObject none = body(Wire.chat(), new ModelSpec("m", table(64_000, BUDGET), true), LOW);
        assertTrue(!none.has("reasoning_effort") && !none.has("thinking"));
    }

    private static JsonObject body(LlmProvider p, ModelSpec model, ThinkingLevel level) {
        return p.buildRequestBody(model, "sys", List.of(), new JsonArray(), new RequestOptions(null, 0, level), null);
    }
}
