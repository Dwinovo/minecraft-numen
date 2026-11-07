package com.dwinovo.numen.api.agent.catalog;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** models.dev 格式的读取与裁剪:协议由 npm 推出、缺省地址、型号字段、裁剪后读得回来。 */
class ModelsDevTest {

    /** 手写的小样本,字段形状与 models.dev 一致(含我们不用的字段,用来验证裁剪)。 */
    static final String SAMPLE = """
            {
              "openai": {"id":"openai","env":["OPENAI_API_KEY"],"npm":"@ai-sdk/openai","name":"OpenAI","doc":"https://platform.openai.com/docs",
                "models":{"gpt-5":{"id":"gpt-5","name":"GPT-5","description":"x","reasoning":true,"tool_call":true,"temperature":false,
                  "reasoning_options":[{"type":"effort","values":["minimal","low","medium","high"]}],
                  "modalities":{"input":["text","image"],"output":["text"]},
                  "limit":{"context":400000,"input":272000,"output":128000},
                  "cost":{"input":1.25,"output":10,"cache_read":0.125}}}},
              "anthropic": {"id":"anthropic","npm":"@ai-sdk/anthropic","name":"Anthropic","doc":"d","models":{}},
              "deepseek": {"id":"deepseek","npm":"@ai-sdk/openai-compatible","api":"https://api.deepseek.com","name":"DeepSeek","doc":"d",
                "models":{"deepseek-v4":{"id":"deepseek-v4","name":"DeepSeek V4","reasoning":true,"tool_call":true,
                  "interleaved":{"field":"reasoning_content"},
                  "reasoning_options":[{"type":"toggle"},{"type":"effort","values":["low","high","max"]}],
                  "release_date":"2026-04-24",
                  "limit":{"context":1000000,"output":393216}},
                  "deepseek-old":{"id":"deepseek-old","name":"DeepSeek Old","status":"deprecated","tool_call":true},
                  "deepseek-beta":{"id":"deepseek-beta","name":"DeepSeek Beta","status":"beta","tool_call":true}}},
              "google": {"id":"google","npm":"@ai-sdk/google","name":"Google","doc":"d","models":{}},
              "xai": {"id":"xai","npm":"@ai-sdk/xai","name":"xAI","doc":"d","models":{}},
              "gateway": {"id":"gateway","npm":"@ai-sdk/openai-compatible","api":"https://gw.example/v1","name":"Gateway","doc":"d",
                "models":{
                  "claude-x":{"id":"claude-x","name":"Claude X","provider":{"npm":"@ai-sdk/anthropic","api":"https://gw.example/anthropic/v1","shape":"x"}},
                  "plain":{"id":"plain","name":"Plain","tool_call":false}}},
              "other-openai": {"id":"other-openai","npm":"@ai-sdk/openai","api":"https://o.example/v1","name":"Other","doc":"d","models":{}},
              "openrouter": {"id":"openrouter","npm":"@openrouter/ai-sdk-provider","api":"https://openrouter.ai/api/v1","name":"OpenRouter","doc":"d","models":{}}
            }
            """;

    @Test
    void protocolFollowsNpmExceptTheOpenAiHold() {
        Map<String, ProviderInfo> p = ModelsDev.parse(SAMPLE);
        assertEquals(Api.ANTHROPIC, p.get("anthropic").api());
        assertEquals(Api.CHAT, p.get("deepseek").api());
        assertEquals(Api.CHAT, p.get("google").api());
        assertEquals(Api.CHAT, p.get("xai").api());
        assertEquals(Api.CHAT, p.get("openrouter").api());
        // 用 @ai-sdk/openai 的厂家(含 OpenAI 官方)按规则走 Responses
        assertEquals(Api.RESPONSES, p.get("other-openai").api());
        assertEquals(Api.RESPONSES, p.get("openai").api());
        assertEquals(Api.RESPONSES, p.get("openai").models().get(0).api());
    }

    @Test
    void sdkBuiltInAddressesAreFilledIn() {
        Map<String, ProviderInfo> p = ModelsDev.parse(SAMPLE);
        assertEquals("https://api.openai.com/v1", p.get("openai").baseUrl());
        assertEquals("https://api.anthropic.com/v1", p.get("anthropic").baseUrl());
        assertEquals("https://api.x.ai/v1", p.get("xai").baseUrl());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", p.get("google").baseUrl());
        assertEquals("https://api.deepseek.com", p.get("deepseek").baseUrl(), "目录自带的地址不动");
    }

    @Test
    void modelFields() {
        ModelInfo gpt = ModelsDev.parse(SAMPLE).get("openai").model("gpt-5");
        assertEquals("GPT-5", gpt.name());
        assertEquals(400_000, gpt.contextWindow());
        assertEquals(128_000, gpt.maxOutput());
        assertEquals(java.util.List.of("image"), gpt.inputs());
        assertTrue(gpt.toolCall());
        assertFalse(gpt.temperature());
        assertEquals(new ModelInfo.Cost(1.25, 10, 0.125, 0), gpt.cost());
        assertNull(gpt.interleaved());
        assertEquals("low", gpt.thinking().effort(ThinkingLevel.LOW));

        ModelInfo ds = ModelsDev.parse(SAMPLE).get("deepseek").model("deepseek-v4");
        assertEquals("reasoning_content", ds.interleaved());
        assertTrue(ds.temperature(), "目录没写温度就按能调处理");
        assertTrue(ds.inputs().isEmpty());
        assertNull(ds.cost());
    }

    @Test
    void deprecatedModelsAreDroppedOnRead() {
        ProviderInfo ds = ModelsDev.parse(SAMPLE).get("deepseek");
        assertNull(ds.model("deepseek-old"));
        assertTrue(ds.model("deepseek-beta") != null, "只有 deprecated 被丢,其它状态照常列");
        assertEquals("2026-04-24", ds.model("deepseek-v4").releaseDate());
        assertEquals("", ds.model("deepseek-beta").releaseDate());
        // 裁剪后的快照/缓存要留着 status,读的时候才丢得掉
        assertTrue(ModelsDev.slim(SAMPLE).contains("deprecated"));
        assertNull(ModelsDev.parse(ModelsDev.slim(SAMPLE)).get("deepseek").model("deepseek-old"));
    }

    @Test
    void aModelCanCarryItsOwnRoute() {
        ProviderInfo gw = ModelsDev.parse(SAMPLE).get("gateway");
        assertEquals(Api.ANTHROPIC, gw.model("claude-x").api());
        assertEquals("https://gw.example/anthropic/v1", gw.model("claude-x").baseUrl());
        assertEquals(Api.CHAT, gw.model("plain").api());
        assertEquals("https://gw.example/v1", gw.model("plain").baseUrl());
        assertFalse(gw.model("plain").toolCall());
    }

    @Test
    void hostIsTheAddressWithoutSchemeAndPath() {
        Map<String, ProviderInfo> p = ModelsDev.parse(SAMPLE);
        assertEquals("api.deepseek.com", p.get("deepseek").host());
        assertEquals("openrouter.ai", p.get("openrouter").host());
        assertEquals("", new ProviderInfo("x", "X", "", Api.CHAT, "", Map.of(), java.util.List.of()).host());
        assertEquals("${RES}.example.com", new ProviderInfo("x", "X", "https://${RES}.example.com/v1", Api.CHAT, "",
                Map.of(), java.util.List.of()).host());
    }

    @Test
    void openRouterCarriesItsAttributionHeaders() {
        assertTrue(ModelsDev.parse(SAMPLE).get("openrouter").headers().containsKey("X-Title"));
        assertTrue(ModelsDev.parse(SAMPLE).get("deepseek").headers().isEmpty());
    }

    @Test
    void slimKeepsOnlyUsedFieldsAndReadsBackTheSame() {
        String slim = ModelsDev.slim(SAMPLE);
        assertFalse(slim.contains("description"));
        assertFalse(slim.contains("OPENAI_API_KEY"));
        assertFalse(slim.contains("\"input\":272000"), "limit.input 不用");
        assertFalse(slim.contains("shape"));
        assertEquals(ModelsDev.parse(SAMPLE), ModelsDev.parse(slim));
        assertEquals(8, slim.lines().filter(l -> l.startsWith("\"")).count(), "一个厂家一行");
    }
}
