package com.dwinovo.numen.agent.provider;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 生成参数上盘:opt-in 纪律(不设不发)、两协议同名字段、与思考预算的加码时序。
 */
class GenerationParamsTest {

    @Test
    void unsetParamsSendNothing() {
        JsonObject body = new JsonObject();
        new OpenAIProvider().applyGenerationParams(body, null, 0);
        assertEquals(0, body.size());
    }

    @Test
    void setParamsAppearVerbatim() {
        JsonObject body = new JsonObject();
        new OpenAIProvider().applyGenerationParams(body, 1.3, 2048);
        assertEquals(1.3, body.get("temperature").getAsDouble());
        assertEquals(2048, body.get("max_tokens").getAsInt());
    }

    @Test
    void anthropicSharesTheSameFieldNames() {
        JsonObject body = new JsonObject();
        new AnthropicProvider().applyGenerationParams(body, 0.7, 4096);
        assertEquals(0.7, body.get("temperature").getAsDouble());
        assertEquals(4096, body.get("max_tokens").getAsInt());
    }

    @Test
    void thinkingBudgetStacksOnTopOfPlayerMaxTokens() {
        // 时序契约:先生成参数后思考开关——预算加在玩家设的输出上限之上,
        // 玩家的值仍然是"正文的上限",不会被预算挤占。
        AnthropicProvider p = new AnthropicProvider();
        JsonObject body = p.buildRequestBody("m", null, java.util.List.of(), new com.google.gson.JsonArray());
        p.applyGenerationParams(body, null, 4096);
        p.applyReasoning(body, "high");
        int budget = body.getAsJsonObject("thinking").get("budget_tokens").getAsInt();
        assertEquals(4096 + budget, body.get("max_tokens").getAsInt());
    }

    @Test
    void openAiFamilyIgnoresNothingWhenOnlyTemperatureSet() {
        JsonObject body = new JsonObject();
        new OpenAIProvider().applyGenerationParams(body, 0.9, 0);
        assertEquals(0.9, body.get("temperature").getAsDouble());
        assertFalse(body.has("max_tokens"));
    }
}
