package com.dwinovo.numen.agent.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * token 记账方言:各家的缓存字段名不同,都要归一到四元用量。口径错了,
 * 命中率和"缓存前缀碎了"的信号一起失真。
 */
class UsageAccountingTest {

    private static JsonObject usage(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ---- 归一 ----

    @Test
    void openAiStyleReadsCachedFromDetails() {
        Usage u = new OpenAIProvider().usage(usage(
                "{\"prompt_tokens\":100,\"completion_tokens\":20,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":80}}"));
        assertEquals(20, u.input());      // 100 全量 - 80 命中
        assertEquals(80, u.cacheRead());
        assertEquals(0, u.cacheWrite());
        assertEquals(20, u.output());
    }

    @Test
    void openAiStyleWithoutDetailsIsAllFreshInput() {
        Usage u = new OpenAIProvider().usage(usage(
                "{\"prompt_tokens\":100,\"completion_tokens\":20}"));
        assertEquals(100, u.input());
        assertEquals(0, u.cacheRead());
        assertFalse(u.reportsCache());    // 没报过缓存 → 界面不该显示命中率
    }

    @Test
    void anthropicStyleKeepsCacheWriteSeparate() {
        // Anthropic 在 mergeUsage 里归一;缓存写单独留着,否则下游再也拆不出来
        Usage u = new OpenAIProvider().usage(usage(
                "{\"prompt_tokens\":100,\"completion_tokens\":20,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":70,\"cache_creation_tokens\":10}}"));
        assertEquals(20, u.input());
        assertEquals(70, u.cacheRead());
        assertEquals(10, u.cacheWrite());
    }

    @Test
    void deepSeekStyleUsesItsOwnHitMissFields() {
        Usage u = new DeepSeekProvider().usage(usage(
                "{\"prompt_tokens\":100,\"completion_tokens\":20,"
                + "\"prompt_cache_miss_tokens\":30,\"prompt_cache_hit_tokens\":70}"));
        assertEquals(30, u.input());
        assertEquals(70, u.cacheRead());
        assertEquals(0, u.cacheWrite());
    }

    @Test
    void deepSeekWithoutItsFieldsFallsBackToParent() {
        Usage u = new DeepSeekProvider().usage(usage(
                "{\"prompt_tokens\":100,\"completion_tokens\":20,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":80}}"));
        assertEquals(20, u.input());
        assertEquals(80, u.cacheRead());
    }

    @Test
    void missingUsageIsZeroNotCrash() {
        assertEquals(Usage.ZERO, new OpenAIProvider().usage(null));
    }

    // ---- 推导 ----

    @Test
    void freshExcludesCacheReadsButCountsWrites() {
        // 缓存读近乎免费;缓存写是实打实处理过的
        Usage u = new Usage(20, 20, 80, 10);
        assertEquals(50, u.fresh());
        assertEquals(110, u.promptTokens());
        assertEquals(130, u.total());
    }

    @Test
    void hitRateIsOverTheWholePrompt() {
        assertEquals(0.8, new Usage(20, 5, 80, 0).cacheHitRate(), 1e-9);
    }

    @Test
    void noPromptMeansNoHitRateNotZeroPercent() {
        // "没数据"和"命中率 0%"是两回事,后者会让人以为缓存坏了
        assertTrue(Usage.ZERO.cacheHitRate() < 0);
    }

    @Test
    void totalsAddUpFieldByField() {
        Usage a = new Usage(1, 2, 3, 4);
        assertEquals(new Usage(2, 4, 6, 8), a.plus(a));
        assertEquals(a, a.plus(null));
    }
}
