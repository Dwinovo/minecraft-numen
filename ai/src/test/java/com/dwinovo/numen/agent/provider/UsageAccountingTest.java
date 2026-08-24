package com.dwinovo.numen.agent.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * token 记账方言:新处理量 = 未命中输入 + 输出。缓存字段各家不同,
 * 记账口径错了会让"缓存前缀碎了"的告警信号失真。
 */
class UsageAccountingTest {

    private static JsonObject usage(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void openAiStyleSubtractsCachedTokens() {
        long fresh = new OpenAIProvider().freshTokens(usage(
                "{\"prompt_tokens\":100,\"completion_tokens\":20,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":80}}"));
        assertEquals(40, fresh);
    }

    @Test
    void openAiStyleWithoutDetailsCountsAll() {
        long fresh = new OpenAIProvider().freshTokens(usage(
                "{\"prompt_tokens\":100,\"completion_tokens\":20}"));
        assertEquals(120, fresh);
    }

    @Test
    void deepSeekStyleUsesCacheMissTokens() {
        long fresh = new DeepSeekProvider().freshTokens(usage(
                "{\"prompt_tokens\":100,\"completion_tokens\":20,"
                + "\"prompt_cache_miss_tokens\":30}"));
        assertEquals(50, fresh);
    }

    @Test
    void deepSeekWithoutMissFieldFallsBackToParent() {
        long fresh = new DeepSeekProvider().freshTokens(usage(
                "{\"prompt_tokens\":100,\"completion_tokens\":20,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":80}}"));
        assertEquals(40, fresh);
    }

    @Test
    void missingUsageIsZeroNotCrash() {
        assertEquals(0, new OpenAIProvider().freshTokens(null));
    }
}
