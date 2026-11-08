package com.dwinovo.numen.api.agent.provider;

import com.dwinovo.numen.api.agent.catalog.ThinkingTable;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;

import static com.dwinovo.numen.api.agent.provider.ChatCompat.MaxTokensField.MAX_TOKENS;
import static com.dwinovo.numen.api.agent.provider.ChatCompat.ThinkingFormat.OPENAI;

/** 协议测试共用的零件:缺省兼容项的协议实现、各种型号、读 JSON。 */
final class Wire {

    private Wire() {}

    /** 最普通的 OpenAI 兼容写法。 */
    static final ChatCompat CHAT = new ChatCompat(OPENAI, MAX_TOKENS, false, true, false);

    /** 目录里查不到的型号:只有自动思考。 */
    static final ModelSpec PLAIN = new ModelSpec("m", ThinkingTable.AUTO_ONLY, true);

    static OpenAIProvider chat() {
        return new OpenAIProvider(CHAT);
    }

    /** 推理型号,思考档位表由 models.dev 的 {@code reasoning_options} 写法给出。 */
    static ModelSpec reasoning(String id, String reasoningOptions) {
        return new ModelSpec(id,
                ThinkingTable.parse(true, JsonParser.parseString(reasoningOptions).getAsJsonArray()), true);
    }

    static JsonObject body(LlmProvider p, ModelSpec model, String system, List<JsonObject> messages,
                           RequestOptions options) {
        return p.buildRequestBody(model, system, messages, new JsonArray(), options, null);
    }

    static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }
}
