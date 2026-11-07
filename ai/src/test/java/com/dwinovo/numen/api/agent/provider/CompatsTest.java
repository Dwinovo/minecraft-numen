package com.dwinovo.numen.api.agent.provider;

import com.dwinovo.numen.api.agent.catalog.Api;
import com.dwinovo.numen.api.agent.catalog.ThinkingTable;
import com.dwinovo.numen.api.agent.provider.ChatCompat.MaxTokensField;
import com.dwinovo.numen.api.agent.provider.ChatCompat.ThinkingFormat;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 兼容项的缺省值按厂家 id 与接口主机认出(唯一一处),档案里的覆盖项盖在上面。 */
class CompatsTest {

    private static ChatCompat chat(String vendor, String url) {
        return (ChatCompat) Compats.resolve(Api.CHAT, vendor, "m", url, ThinkingTable.AUTO_ONLY, null);
    }

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "deepseek,https://api.deepseek.com,DEEPSEEK",
            "custom,https://api.deepseek.com/beta,DEEPSEEK",
            "zhipuai,https://open.bigmodel.cn/api/paas/v4,ZAI",
            "zai,https://api.z.ai/api/paas/v4,ZAI",
            "moonshotai,https://api.moonshot.ai/v1,ZAI",
            "custom,https://api.moonshot.cn/v1,ZAI",
            "volcengine,https://ark.cn-beijing.volces.com/api/v3,ZAI",
            "openrouter,https://openrouter.ai/api/v1,OPENROUTER",
            "custom,https://openrouter.ai/api/v1,OPENROUTER",
            "alibaba-cn,https://dashscope.aliyuncs.com/compatible-mode/v1,QWEN",
            "siliconflow-cn,https://api.siliconflow.cn/v1,QWEN",
            "openai,https://api.openai.com/v1,OPENAI",
            "xai,https://api.x.ai/v1,OPENAI",
            "google,https://generativelanguage.googleapis.com/v1beta/openai,OPENAI",
            "custom,http://localhost:11434/v1,OPENAI"})
    void thinkingFormatIsRecognisedFromVendorOrHost(String vendor, String url, ThinkingFormat expected) {
        assertEquals(expected, chat(vendor, url).thinkingFormat());
    }

    @Test
    void onlyTheOfficialOpenAiHostGetsCompletionTokens() {
        assertEquals(MaxTokensField.MAX_COMPLETION_TOKENS, chat("openai", "https://api.openai.com/v1").maxTokensField());
        assertEquals(MaxTokensField.MAX_TOKENS, chat("deepseek", "https://api.deepseek.com").maxTokensField());
        assertEquals(MaxTokensField.MAX_TOKENS, chat("custom", "http://localhost:1234/v1").maxTokensField());
    }

    @Test
    void deepSeekAndKimiReplayReasoningContentOthersDoNot() {
        assertTrue(chat("deepseek", "https://api.deepseek.com").replayReasoningContent());
        assertTrue(chat("moonshotai-cn", "https://api.moonshot.cn/v1").replayReasoningContent());
        assertFalse(chat("openai", "https://api.openai.com/v1").replayReasoningContent());
        assertFalse(chat("custom", "http://localhost:1234/v1").replayReasoningContent());
        assertTrue(chat("custom", "http://localhost:1234/v1").streamUsage());
    }

    @Test
    void anUnparseableAddressJustMeansNoHostMatch() {
        assertEquals(ThinkingFormat.OPENAI, chat("custom", "not a url").thinkingFormat());
        assertEquals(ThinkingFormat.OPENAI, chat("custom", "").thinkingFormat());
        assertEquals(ThinkingFormat.OPENAI, chat(null, null).thinkingFormat());
    }

    @Test
    void chatOverridesWinOverTheRecognisedDefaults() {
        ChatCompat c = (ChatCompat) Compats.resolve(Api.CHAT, "deepseek", "m", "https://api.deepseek.com",
                ThinkingTable.AUTO_ONLY, json("""
                        {"thinking_format":"none","max_tokens_field":"max_completion_tokens",
                         "replay_reasoning_content":false,"stream_usage":false}"""));
        assertEquals(new ChatCompat(ThinkingFormat.NONE, MaxTokensField.MAX_COMPLETION_TOKENS, false, false, false), c);
    }

    @Test
    void aMisspelledOverrideIsRejectedNotIgnored() {
        assertThrows(IllegalArgumentException.class, () -> Compats.resolve(Api.CHAT, "custom", "m", "http://x/v1",
                ThinkingTable.AUTO_ONLY, json("{\"thinking_format\":\"deepseeek\"}")));
    }

    @Test
    void openRouterRoutingToAnAnthropicModelAsksForExplicitCacheBreakpoints() {
        ThinkingTable none = ThinkingTable.AUTO_ONLY;
        assertTrue(((ChatCompat) Compats.resolve(Api.CHAT, "openrouter", "anthropic/claude-opus-4.5",
                "https://openrouter.ai/api/v1", none, null)).anthropicCacheControl());
        assertFalse(((ChatCompat) Compats.resolve(Api.CHAT, "openrouter", "openai/gpt-5",
                "https://openrouter.ai/api/v1", none, null)).anthropicCacheControl());
        // 别家的 anthropic/ 前缀型号不算;玩家可以覆盖
        assertFalse(((ChatCompat) Compats.resolve(Api.CHAT, "custom", "anthropic/x", "http://x/v1", none, null))
                .anthropicCacheControl());
        assertTrue(((ChatCompat) Compats.resolve(Api.CHAT, "custom", "m", "http://x/v1", none,
                json("{\"anthropic_cache_control\":true}"))).anthropicCacheControl());
    }

    @Test
    void responsesDeveloperRoleDefaultsOnAndCanBeSwitchedOff() {
        assertEquals(new ResponsesCompat(true),
                Compats.resolve(Api.RESPONSES, "openai", "m", "https://api.openai.com/v1", ThinkingTable.AUTO_ONLY, null));
        assertEquals(new ResponsesCompat(false), Compats.resolve(Api.RESPONSES, "custom", "m", "http://x/v1",
                ThinkingTable.AUTO_ONLY, json("{\"developer_role\":false}")));
    }

    @Test
    void anthropicAdaptiveThinkingFollowsTheModelsControlShapeUnlessOverridden() {
        ThinkingTable effortOnly = ThinkingTable.parse(true, JsonParser.parseString(
                "[{\"type\":\"effort\",\"values\":[\"low\",\"high\"]}]").getAsJsonArray());
        ThinkingTable withBudget = ThinkingTable.parse(true, JsonParser.parseString(
                "[{\"type\":\"effort\",\"values\":[\"low\",\"high\"]},{\"type\":\"budget_tokens\"}]").getAsJsonArray());
        assertEquals(new AnthropicCompat(true, true),
                Compats.resolve(Api.ANTHROPIC, "anthropic", "m", "https://api.anthropic.com/v1", effortOnly, null));
        assertEquals(new AnthropicCompat(false, true),
                Compats.resolve(Api.ANTHROPIC, "anthropic", "m", "https://api.anthropic.com/v1", withBudget, null));
        assertEquals(new AnthropicCompat(false, true),
                Compats.resolve(Api.ANTHROPIC, "anthropic", "m", "https://api.anthropic.com/v1",
                        ThinkingTable.AUTO_ONLY, null), "查不到能力的型号不走自适应");
        assertEquals(new AnthropicCompat(false, false),
                Compats.resolve(Api.ANTHROPIC, "custom", "m", "http://x/v1", effortOnly,
                        json("{\"adaptive_thinking\":false,\"temperature\":false}")));
    }
}
