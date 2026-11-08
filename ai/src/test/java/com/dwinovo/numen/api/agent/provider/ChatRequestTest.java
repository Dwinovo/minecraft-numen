package com.dwinovo.numen.api.agent.provider;

import com.dwinovo.numen.api.agent.catalog.ThinkingLevel;
import com.dwinovo.numen.api.agent.provider.ChatCompat.MaxTokensField;
import com.dwinovo.numen.api.agent.provider.ChatCompat.ThinkingFormat;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Chat Completions 的请求体黄金对照({@code golden/chat-requests.json}):每种思考写法 × 开/关/档位,
 * 加生成参数、输出上限字段名、流式用量。期望值是按各家 API 文档手写的整份请求体。
 */
class ChatRequestTest {

    private static final String EFFORT_FOUR = "[{\"type\":\"effort\",\"values\":[\"minimal\",\"low\",\"medium\",\"high\"]}]";
    private static final String TOGGLE = "[{\"type\":\"toggle\"}]";

    private static final IToolSpec LOOK = new IToolSpec() {
        @Override public String name() { return "look"; }
        @Override public String description() { return "look"; }
        @Override public Map<String, Object> parameterSchema() { return Map.of("type", "object"); }
    };

    private static JsonObject golden(String name) {
        return GoldenFiles.object("chat-requests.json", name);
    }

    private static JsonObject build(ChatCompat compat, ModelSpec model, RequestOptions options) {
        OpenAIProvider p = new OpenAIProvider(compat);
        return p.buildRequestBody(model, "你是同伴", List.of(p.buildUserMessage("你好")),
                p.buildToolList(List.of(LOOK)), options, null);
    }

    private static ModelSpec noTemperature(ModelSpec m) {
        return new ModelSpec(m.id(), m.thinking(), false);
    }

    private static ChatCompat compat(ThinkingFormat f, MaxTokensField m) {
        return new ChatCompat(f, m, false, true, false);
    }

    @Test
    void openAiEffortAndCompletionTokensField() {
        // gpt-5 不收温度:玩家设了也不发
        assertEquals(golden("openai-high"), build(
                compat(ThinkingFormat.OPENAI, MaxTokensField.MAX_COMPLETION_TOKENS),
                noTemperature(Wire.reasoning("gpt-5", EFFORT_FOUR)),
                new RequestOptions(0.2, 4096, ThinkingLevel.HIGH)));
    }

    @Test
    void openAiOffIsTheModelsNoneWord() {
        assertEquals(golden("openai-off-as-none"), build(
                compat(ThinkingFormat.OPENAI, MaxTokensField.MAX_TOKENS),
                Wire.reasoning("gpt-5.1", "[{\"type\":\"effort\",\"values\":[\"none\",\"low\",\"high\"]}]"),
                new RequestOptions(null, 0, ThinkingLevel.OFF)));
    }

    @Test
    void openRouterNestsTheEffort() {
        assertEquals(golden("openrouter-medium"), build(
                compat(ThinkingFormat.OPENROUTER, MaxTokensField.MAX_TOKENS),
                Wire.reasoning("anthropic/claude-sonnet-4.6",
                        "[{\"type\":\"toggle\"},{\"type\":\"effort\",\"values\":[\"low\",\"medium\",\"high\",\"max\"]}]"),
                new RequestOptions(0.5, 2048, ThinkingLevel.MEDIUM)));
    }

    @Test
    void deepSeekSwitchesThinkingAndAddsTheEffortWord() {
        ModelSpec m = Wire.reasoning("deepseek-v4-flash",
                "[{\"type\":\"toggle\"},{\"type\":\"effort\",\"values\":[\"low\",\"high\",\"max\"]}]");
        ChatCompat c = compat(ThinkingFormat.DEEPSEEK, MaxTokensField.MAX_TOKENS);
        assertEquals(golden("deepseek-high"), build(c, m, new RequestOptions(null, 0, ThinkingLevel.HIGH)));
        assertEquals(golden("deepseek-off"), build(c, m, new RequestOptions(null, 0, ThinkingLevel.OFF)));
    }

    @Test
    void zaiOnlyWritesTheSwitch() {
        assertEquals(golden("zai-on"), build(
                compat(ThinkingFormat.ZAI, MaxTokensField.MAX_TOKENS),
                Wire.reasoning("glm-5", TOGGLE),
                new RequestOptions(null, 1024, ThinkingLevel.MEDIUM)));
    }

    @Test
    void qwenWritesTheBoolean() {
        assertEquals(golden("qwen-off"), build(
                compat(ThinkingFormat.QWEN, MaxTokensField.MAX_TOKENS),
                Wire.reasoning("qwen3-max", TOGGLE),
                new RequestOptions(null, 0, ThinkingLevel.OFF)));
    }

    @Test
    void noneFormatNeverSendsThinking() {
        assertEquals(golden("none-format"), build(
                compat(ThinkingFormat.NONE, MaxTokensField.MAX_TOKENS),
                Wire.reasoning("kimi-k2", TOGGLE),
                new RequestOptions(null, 0, ThinkingLevel.MEDIUM)));
    }

    @Test
    void autoSendsNoThinkingParameterWhateverTheModelCanDo() {
        assertEquals(golden("auto-sends-nothing"), build(
                compat(ThinkingFormat.OPENAI, MaxTokensField.MAX_TOKENS),
                Wire.reasoning("gpt-5", EFFORT_FOUR),
                new RequestOptions(null, 0, ThinkingLevel.AUTO)));
    }

    @Test
    void aLevelTheModelLacksOrAnUnknownModelSendsNothing() {
        JsonObject expected = golden("auto-sends-nothing");
        // gpt-5 没有 max 档
        assertEquals(expected, build(compat(ThinkingFormat.OPENAI, MaxTokensField.MAX_TOKENS),
                Wire.reasoning("gpt-5", EFFORT_FOUR), new RequestOptions(null, 0, ThinkingLevel.MAX)));
        // 目录里查不到的型号:只有自动
        JsonObject unknown = build(compat(ThinkingFormat.OPENAI, MaxTokensField.MAX_TOKENS),
                new ModelSpec("gpt-5", Wire.PLAIN.thinking(), true), new RequestOptions(null, 0, ThinkingLevel.HIGH));
        assertEquals(expected, unknown);
    }

    @Test
    void streamUsageOffAndPlayersTemperature() {
        assertEquals(golden("no-stream-usage"), build(
                new ChatCompat(ThinkingFormat.OPENAI, MaxTokensField.MAX_TOKENS, false, false, false),
                new ModelSpec("local-model", Wire.PLAIN.thinking(), true),
                new RequestOptions(1.3, 0, ThinkingLevel.AUTO)));
    }
}
