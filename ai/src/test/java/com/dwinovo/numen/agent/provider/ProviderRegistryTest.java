package com.dwinovo.numen.agent.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站点注册表（只读内置 numen_providers.json）:
 * ctx 查询、生成参数缺省、别名规范化、本地部署站点在册。
 */
class ProviderRegistryTest {

    @Test
    void knownModelCtxAndUnknownFallback() {
        assertEquals(1000000, ProviderRegistry.contextWindow("deepseek", "deepseek-chat"));
        assertEquals(ProviderRegistry.DEFAULT_CTX, ProviderRegistry.contextWindow("deepseek", "no-such-model"));
    }

    @Test
    void generationParamsDefaultToUnset() {
        ProviderRegistry.Model m = ProviderRegistry.model("deepseek", "deepseek-chat");
        assertNotNull(m);
        assertNull(m.temperature());
        assertEquals(0, m.maxTokens());
    }

    @Test
    void unknownModelHasNoRegistryEntry() {
        assertNull(ProviderRegistry.model("deepseek", "custom-model-id"));
    }

    @Test
    void aliasesResolveThroughCanonicalId() {
        assertEquals(ProviderRegistry.baseUrl("moonshot"), ProviderRegistry.baseUrl("kimi"));
        assertEquals("thinking-type", ProviderRegistry.thinkingFormat("doubao"));
    }

    @Test
    void localDeploymentSitesAreRegistered() {
        for (String id : new String[]{"ollama", "lmstudio", "vllm"}) {
            assertTrue(ProviderRegistry.has(id), id);
            assertTrue(ProviderRegistry.baseUrl(id).startsWith("http://localhost:"), id);
        }
    }

    @Test
    void anthropicRowCarriesProtocol() {
        assertEquals("anthropic", ProviderRegistry.protocol("anthropic"));
        assertEquals("", ProviderRegistry.protocol("deepseek"));
    }
}
