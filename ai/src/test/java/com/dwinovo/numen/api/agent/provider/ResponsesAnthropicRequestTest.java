package com.dwinovo.numen.api.agent.provider;

import com.dwinovo.numen.api.agent.catalog.ThinkingLevel;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Responses 与 Anthropic Messages 的请求体黄金对照({@code golden/responses-requests.json}、{@code golden/anthropic-requests.json})。 */
class ResponsesAnthropicRequestTest {

    private static final IToolSpec LOOK = new IToolSpec() {
        @Override public String name() { return "look"; }
        @Override public String description() { return "look"; }
        @Override public Map<String, Object> parameterSchema() { return Map.of("type", "object"); }
    };

    private static JsonObject build(LlmProvider p, ModelSpec model, RequestOptions options) {
        return p.buildRequestBody(model, "你是同伴", List.of(p.buildUserMessage("你好")),
                p.buildToolList(List.of(LOOK)), options, null);
    }

    private static JsonObject responses(String name) {
        return GoldenFiles.object("responses-requests.json", name);
    }

    private static JsonObject anthropic(String name) {
        return GoldenFiles.object("anthropic-requests.json", name);
    }

    // ---- Responses ----

    private static final ModelSpec GPT6 = Wire.reasoning("gpt-6",
            "[{\"type\":\"effort\",\"values\":[\"none\",\"low\",\"medium\",\"high\"]}]");

    @Test
    void responsesWritesTheNestedEffortAndTheGenerationParams() {
        assertEquals(responses("reasoning-high"), build(new OpenAIResponsesProvider(new ResponsesCompat(true)),
                GPT6, new RequestOptions(0.7, 2048, ThinkingLevel.HIGH)));
    }

    @Test
    void responsesOffIsTheModelsNoneWord() {
        assertEquals(responses("off-as-none"), build(new OpenAIResponsesProvider(new ResponsesCompat(true)),
                GPT6, new RequestOptions(null, 0, ThinkingLevel.OFF)));
    }

    /** 自动:不写 reasoning,但会思考的型号照样要加密内容,否则回传的思考 item 站不住;系统话的角色由兼容项定。 */
    @Test
    void responsesAutoOnAThinkingModelStillAsksForEncryptedReasoning() {
        assertEquals(responses("auto-on-a-thinking-model"), build(
                new OpenAIResponsesProvider(new ResponsesCompat(false)), GPT6, RequestOptions.NONE));
    }

    /** 不思考的型号:系统话走 system,不收 include。 */
    @Test
    void responsesPlainModelGetsNeitherDeveloperRoleNorInclude() {
        assertEquals(responses("plain-model"), build(new OpenAIResponsesProvider(new ResponsesCompat(true)),
                new ModelSpec("gpt-4.1", com.dwinovo.numen.api.agent.catalog.ThinkingTable.AUTO_ONLY, true),
                RequestOptions.NONE));
    }

    // ---- Anthropic ----

    private static final String EFFORT_AND_BUDGET =
            "[{\"type\":\"effort\",\"values\":[\"low\",\"medium\",\"high\"]},{\"type\":\"budget_tokens\",\"min\":1024}]";
    private static final String EFFORT_ONLY =
            "[{\"type\":\"effort\",\"values\":[\"low\",\"medium\",\"high\",\"xhigh\",\"max\"]}]";

    @Test
    void anthropicBudgetThinking() {
        assertEquals(anthropic("budget-high"), build(new AnthropicProvider(new AnthropicCompat(false, true)),
                Wire.reasoning("claude-opus-4-5", EFFORT_AND_BUDGET),
                new RequestOptions(0.4, 0, ThinkingLevel.HIGH)));
    }

    @Test
    void anthropicAdaptiveThinking() {
        assertEquals(anthropic("adaptive-max"), build(new AnthropicProvider(new AnthropicCompat(true, true)),
                Wire.reasoning("claude-opus-5", EFFORT_ONLY), new RequestOptions(null, 0, ThinkingLevel.MAX)));
    }

    @Test
    void anthropicAutoSendsOnlyTheGenerationParams() {
        assertEquals(anthropic("auto"), build(new AnthropicProvider(new AnthropicCompat(false, true)),
                Wire.reasoning("claude-opus-4-5", EFFORT_AND_BUDGET),
                new RequestOptions(0.4, 0, ThinkingLevel.AUTO)));
    }
}
