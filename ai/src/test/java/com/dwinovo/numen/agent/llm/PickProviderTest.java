package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.provider.AnthropicProvider;
import com.dwinovo.numen.agent.provider.DeepSeekProvider;
import com.dwinovo.numen.agent.provider.LlmProvider;
import com.dwinovo.numen.agent.provider.MoonshotProvider;
import com.dwinovo.numen.agent.provider.OpenAIProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 站点名 → provider 实例的装配:别名解析、子类挑选、数据驱动站点的
 * 方言注入。跑在未注入用户文件的 headless 环境(内置 numen_models.json 兜底)。
 */
class PickProviderTest {

    private static OpenAIProvider pick(String name) {
        return (OpenAIProvider) NumenLlmClient.pickProvider(name);
    }

    @Test
    void subclassesForBehaviourDivergentBackends() {
        assertInstanceOf(DeepSeekProvider.class, NumenLlmClient.pickProvider("deepseek"));
        assertInstanceOf(MoonshotProvider.class, NumenLlmClient.pickProvider("kimi"));
        assertInstanceOf(MoonshotProvider.class, NumenLlmClient.pickProvider("moonshot"));
    }

    @Test
    void dataDrivenSitesCarryDialectFromRegistry() {
        // numen_models.json 里配的 thinkingFormat 必须流到 provider 实例。
        assertEquals(LlmProvider.THINKING_TYPE, pick("glm").thinkingFormat());
        assertEquals(LlmProvider.THINKING_ENABLE_BOOL, pick("qwen").thinkingFormat());
        assertEquals(LlmProvider.THINKING_ENABLE_BOOL, pick("siliconflow").thinkingFormat());
        assertEquals(LlmProvider.THINKING_TYPE, pick("doubao").thinkingFormat());
        assertEquals(LlmProvider.THINKING_EFFORT_NESTED, pick("openrouter").thinkingFormat());
        assertEquals(LlmProvider.THINKING_NONE, pick("minimax").thinkingFormat());
        assertEquals(LlmProvider.THINKING_EFFORT, pick("gemini").thinkingFormat());
    }

    @Test
    void aliasesResolveToRegisteredSites() {
        assertEquals("zhipu", pick("glm").name());
        assertEquals("dashscope", pick("tongyi").name());
        assertEquals("volcengine", pick("ark").name());
    }

    @Test
    void anthropicProtocolRowsGetTheMessagesAdapter() {
        // protocol:"anthropic" 的站点行装配第二协议;方言/端点随之切换。
        var p = NumenLlmClient.pickProvider("anthropic");
        assertInstanceOf(AnthropicProvider.class, p);
        assertEquals("/messages", p.chatPath());
        assertEquals(LlmProvider.THINKING_BUDGET, ((AnthropicProvider) p).thinkingFormat());
    }

    @Test
    void aliasTableIsSingleSourced() {
        // 别名唯一真源在 ModelRegistry.canonicalId;装配走同一张表。
        assertEquals("openai",
                com.dwinovo.numen.agent.model.ModelRegistry.canonicalId("openai-compatible"));
        assertEquals("moonshot",
                com.dwinovo.numen.agent.model.ModelRegistry.canonicalId("KIMI"));
        assertEquals("openai",
                com.dwinovo.numen.agent.model.ModelRegistry.canonicalId(null));
    }

    @Test
    void unknownFallsBackToPlainOpenAi() {
        OpenAIProvider p = pick("no-such-site");
        assertEquals("openai", p.name());
        assertEquals(LlmProvider.THINKING_EFFORT, p.thinkingFormat());
    }
}
