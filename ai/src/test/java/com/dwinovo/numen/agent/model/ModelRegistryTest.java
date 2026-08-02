package com.dwinovo.numen.agent.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站点注册表(headless:未注入用户文件,内置 numen_models.json 兜底):
 * ctx 查询、生成参数缺省、别名规范化、本地部署站点在册。
 */
class ModelRegistryTest {

    @Test
    void knownModelCtxAndUnknownFallback() {
        assertEquals(1000000, ModelRegistry.contextWindow("deepseek", "deepseek-chat"));
        assertEquals(ModelRegistry.DEFAULT_CTX, ModelRegistry.contextWindow("deepseek", "no-such-model"));
    }

    @Test
    void generationParamsDefaultToUnset() {
        ModelRegistry.Model m = ModelRegistry.model("deepseek", "deepseek-chat");
        assertNotNull(m);
        assertNull(m.temperature());
        assertEquals(0, m.maxTokens());
    }

    @Test
    void unknownModelHasNoRegistryEntry() {
        assertNull(ModelRegistry.model("deepseek", "custom-model-id"));
    }

    @Test
    void aliasesResolveThroughCanonicalId() {
        assertEquals(ModelRegistry.baseUrl("moonshot"), ModelRegistry.baseUrl("kimi"));
        assertEquals("thinking-type", ModelRegistry.thinkingFormat("doubao"));
    }

    @Test
    void localDeploymentSitesAreRegistered() {
        for (String id : new String[]{"ollama", "lmstudio", "vllm"}) {
            assertTrue(ModelRegistry.has(id), id);
            assertTrue(ModelRegistry.baseUrl(id).startsWith("http://localhost:"), id);
        }
    }

    @Test
    void anthropicRowCarriesProtocol() {
        assertEquals("anthropic", ModelRegistry.protocol("anthropic"));
        assertEquals("", ModelRegistry.protocol("deepseek"));
    }
}
