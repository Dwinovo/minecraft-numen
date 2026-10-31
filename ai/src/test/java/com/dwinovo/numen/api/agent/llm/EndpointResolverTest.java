package com.dwinovo.numen.api.agent.llm;

import com.dwinovo.numen.api.agent.catalog.Api;
import com.dwinovo.numen.api.agent.catalog.Catalog;
import com.dwinovo.numen.api.agent.catalog.ModelsDev;
import com.dwinovo.numen.api.agent.catalog.ThinkingLevel;
import com.dwinovo.numen.api.agent.provider.ChatCompat;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 档案 + 目录 → 端点:玩家填的盖过目录、目录查不到的型号不猜能力、密钥去空白、出处戳。 */
class EndpointResolverTest {

    private static final Catalog CATALOG = new Catalog(() -> ModelsDev.parse("""
            {"deepseek":{"name":"DeepSeek","npm":"@ai-sdk/openai-compatible","api":"https://api.deepseek.com",
              "models":{"deepseek-v4-flash":{"name":"V4 Flash","reasoning":true,"tool_call":true,
                "reasoning_options":[{"type":"toggle"},{"type":"effort","values":["low","high","max"]}],
                "limit":{"context":1000000,"output":393216}}}},
             "anthropic":{"name":"Anthropic","npm":"@ai-sdk/anthropic","models":{
                "claude-opus-5":{"name":"Opus 5","reasoning":true,"temperature":false,
                  "reasoning_options":[{"type":"effort","values":["low","medium","high","xhigh","max"]}],
                  "limit":{"context":1000000}}}},
             "openrouter":{"name":"OpenRouter","npm":"@openrouter/ai-sdk-provider","api":"https://openrouter.ai/api/v1","models":{}}}
            """));

    private static ConnectionProfile profile(String provider, String model) {
        return new ConnectionProfile(provider, null, model, "sk-1", null, null, null, 0, null, 0, null, 0, 0);
    }

    private static LlmEndpoint resolve(ConnectionProfile p) {
        return EndpointResolver.resolve(p, CATALOG, "10.0.0.1:7890");
    }

    @Test
    void aCatalogModelBringsItsRouteWindowAndThinkingLevels() {
        LlmEndpoint e = resolve(profile("deepseek", "deepseek-v4-flash"));
        assertEquals("https://api.deepseek.com", e.baseUrl());
        assertEquals(Api.CHAT, e.api());
        assertEquals(1_000_000, e.contextWindow());
        assertEquals(java.util.List.of(ThinkingLevel.AUTO, ThinkingLevel.OFF, ThinkingLevel.LOW,
                ThinkingLevel.HIGH, ThinkingLevel.MAX), e.model().thinking().levels());
        assertEquals(ChatCompat.ThinkingFormat.DEEPSEEK, ((ChatCompat) e.compat()).thinkingFormat());
        assertTrue(e.hasUrl());
    }

    @Test
    void theCompactionNumbersDefaultToPiAndTheProfileCanOverrideThem() {
        LlmEndpoint byDefault = resolve(profile("deepseek", "deepseek-v4-flash"));
        assertEquals(16_384, byDefault.reserveTokens());
        assertEquals(20_000, byDefault.keepRecentTokens());
        assertEquals(1_000_000 - 16_384, byDefault.compactionThreshold());

        LlmEndpoint overridden = resolve(new ConnectionProfile("deepseek", null, "deepseek-v4-flash", "k", null, null,
                null, 0, null, 0, null, 30_000, 5_000));
        assertEquals(30_000, overridden.reserveTokens());
        assertEquals(5_000, overridden.keepRecentTokens());
        assertEquals(1_000_000 - 30_000, overridden.compactionThreshold());
    }

    /** 输出上限:玩家设的,没设就是型号的输出上限,再收到窗口里剩下的;两者都不知道就不发。普通一轮与摘要请求共用。 */
    @Test
    void theOutputLimitIsSizedFromWhatIsLeftOfTheWindow() {
        LlmEndpoint e = resolve(profile("deepseek", "deepseek-v4-flash"));
        assertEquals(393_216, e.optionsFor(100_000).maxTokens(), "窗口够:型号自己的输出上限");
        assertEquals(1_000_000 - 800_000 - 4_096, e.optionsFor(800_000).maxTokens(), "窗口剩得少:收到剩下的量");
        assertEquals(1, e.optionsFor(2_000_000).maxTokens(), "再大也至少 1");

        LlmEndpoint capped = resolve(new ConnectionProfile("deepseek", null, "deepseek-v4-flash", "k", null, null,
                null, 0, null, 2048, null, 0, 0));
        assertEquals(2048, capped.optionsFor(100_000).maxTokens(), "玩家设的更小就照玩家的");

        LlmEndpoint unknown = resolve(profile("anthropic", "claude-opus-5"));
        assertEquals(0, unknown.optionsFor(100_000).maxTokens(), "型号的输出上限不知道也没设:不发");
    }

    @Test
    void whatThePlayerFilledInBeatsTheCatalog() {
        LlmEndpoint e = resolve(new ConnectionProfile("deepseek", "anthropic", "deepseek-v4-flash", "k",
                "https://proxy.example/v1", "127.0.0.1:1", "high", 32_000, 0.3, 2048, null, 0, 0));
        assertEquals("https://proxy.example/v1", e.baseUrl());
        assertEquals(Api.ANTHROPIC, e.api());
        assertEquals(32_000, e.contextWindow());
        assertEquals("127.0.0.1:1", e.proxy());
        assertEquals(new com.dwinovo.numen.api.agent.provider.RequestOptions(0.3, 2048, ThinkingLevel.HIGH),
                e.options());
    }

    @Test
    void anUnknownModelOnAKnownVendorGetsNoGuessedAbilities() {
        LlmEndpoint e = resolve(profile("deepseek", "my-finetune"));
        assertEquals("https://api.deepseek.com", e.baseUrl(), "厂家的地址照用");
        assertEquals(EndpointResolver.DEFAULT_CTX, e.contextWindow());
        assertEquals(java.util.List.of(ThinkingLevel.AUTO), e.model().thinking().levels());
        assertTrue(e.model().temperature());
    }

    @Test
    void aCustomVendorTakesAddressAndProtocolFromTheProfile() {
        LlmEndpoint e = resolve(new ConnectionProfile(ConnectionProfile.CUSTOM, "chat", "qwen3", "",
                "http://localhost:11434/v1", null, null, 0, null, 0, null, 0, 0));
        assertEquals("http://localhost:11434/v1", e.baseUrl());
        assertEquals(Api.CHAT, e.api());
        assertEquals("10.0.0.1:7890", e.proxy(), "没填代理就跟随全局");
        assertFalse(e.hasApiKey());
    }

    @Test
    void aCustomVendorWithNoAddressIsNotUsable() {
        assertFalse(resolve(profile(ConnectionProfile.CUSTOM, "m")).hasUrl());
        assertFalse(resolve(profile("no-such-vendor", "m")).hasUrl());
        assertFalse(LlmEndpoint.unresolved("").hasUrl());
    }

    @Test
    void theModelCanRefuseTemperatureAndAdaptiveThinkingFollowsItsShape() {
        LlmEndpoint e = resolve(profile("anthropic", "claude-opus-5"));
        assertFalse(e.model().temperature());
        assertEquals(new com.dwinovo.numen.api.agent.provider.AnthropicCompat(true, true), e.compat());
        assertEquals("https://api.anthropic.com/v1", e.baseUrl(), "目录没写地址的官方厂家用缺省地址表");
    }

    @Test
    void vendorHeadersComeFromTheCatalog() {
        assertTrue(resolve(profile("openrouter", "x/y")).headers().containsKey("X-Title"));
        assertTrue(resolve(profile("deepseek", "deepseek-v4-flash")).headers().isEmpty());
    }

    @Test
    void compatOverridesFromTheProfileReachTheEndpoint() {
        LlmEndpoint e = resolve(new ConnectionProfile("deepseek", null, "deepseek-v4-flash", "k", null, null,
                null, 0, null, 0, JsonParser.parseString("{\"thinking_format\":\"none\"}").getAsJsonObject(), 0, 0));
        assertEquals(ChatCompat.ThinkingFormat.NONE, ((ChatCompat) e.compat()).thinkingFormat());
    }

    @Test
    void theApiKeyIsStrippedOnceAtTheEndpoint() {
        // 复制来的 key 常带空格或换行,发出去就是一个错的 key
        assertEquals("sk-or-abc", resolve(new ConnectionProfile("openrouter", null, "x/y", " sk-or-abc \r\n",
                null, null, null, 0, null, 0, null, 0, 0)).apiKey());
        assertFalse(resolve(new ConnectionProfile("openrouter", null, "x/y", "  \n", null, null, null, 0, null, 0,
                null, 0, 0)).hasApiKey());
        assertEquals("", resolve(new ConnectionProfile("openrouter", null, "x/y", null, null, null, null, 0, null, 0,
                null, 0, 0)).apiKey());
    }

    @Test
    void theOriginStampNamesProtocolVendorAndModel() {
        assertEquals("chat/deepseek/deepseek-v4-flash", resolve(profile("deepseek", "deepseek-v4-flash")).origin());
        assertEquals("anthropic/anthropic/claude-opus-5", resolve(profile("anthropic", "claude-opus-5")).origin());
    }
}
