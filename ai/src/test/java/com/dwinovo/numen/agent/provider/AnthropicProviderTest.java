package com.dwinovo.numen.agent.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anthropic Messages 协议适配的全面卷:请求形状(顶层 system/角色归并/工具
 * 平铺)、流式累积(块级事件/思考签名/usage 归一)、思考预算方言、鉴权与端点。
 */
class AnthropicProviderTest {

    private static final AnthropicProvider P = new AnthropicProvider();

    private static JsonObject j(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ---- 请求形状 ----

    @Test
    void systemIsTopLevelNotAMessage() {
        JsonObject body = P.buildRequestBody("claude-sonnet-4-5", "你是同伴",
                List.of(P.buildUserMessage("你好")), new JsonArray());
        assertEquals("你是同伴", body.get("system").getAsString());
        JsonArray msgs = body.getAsJsonArray("messages");
        assertEquals(1, msgs.size());
        assertEquals("user", msgs.get(0).getAsJsonObject().get("role").getAsString());
    }

    @Test
    void maxTokensAlwaysPresent() {
        JsonObject body = P.buildRequestBody("m", null, List.of(), new JsonArray());
        assertTrue(body.get("max_tokens").getAsInt() > 0);
    }

    @Test
    void consecutiveUserMessagesMergeIntoOne() {
        // 并行工具结果 = 连续多条 user 消息,必须并成一条多块消息,否则 400。
        JsonObject body = P.buildRequestBody("m", null, List.of(
                P.buildToolResultMessage("t1", "结果一"),
                P.buildToolResultMessage("t2", "结果二"),
                P.buildUserMessage("继续")), new JsonArray());
        JsonArray msgs = body.getAsJsonArray("messages");
        assertEquals(1, msgs.size());
        JsonArray blocks = msgs.get(0).getAsJsonObject().getAsJsonArray("content");
        assertEquals(3, blocks.size());
        assertEquals("tool_result", blocks.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("t2", blocks.get(1).getAsJsonObject().get("tool_use_id").getAsString());
        assertEquals("text", blocks.get(2).getAsJsonObject().get("type").getAsString());
        assertEquals("继续", blocks.get(2).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void toolsAreFlatWithInputSchema() {
        IToolSpec tool = new IToolSpec() {
            @Override public String name() { return "look"; }
            @Override public String description() { return "看一眼"; }
            @Override public Map<String, Object> parameterSchema() {
                return Map.of("type", "object");
            }
        };
        JsonArray arr = P.buildToolList(List.of(tool));
        JsonObject t = arr.get(0).getAsJsonObject();
        assertEquals("look", t.get("name").getAsString());
        assertEquals("object", t.getAsJsonObject("input_schema").get("type").getAsString());
        assertFalse(t.has("function"));
    }

    @Test
    void assistantRebuildsThinkingTextAndToolUseBlocks() {
        JsonObject extras = new JsonObject();
        extras.addProperty(AnthropicProvider.SIGNATURE_KEY, "sig123");
        AssistantTurn turn = new AssistantTurn("好的",
                List.of(new LlmToolCall("tu1", "move_to", "{\"x\":1}")), extras, "先想想");
        JsonArray blocks = P.assistantToRequestMessage(turn).getAsJsonArray("content");
        assertEquals("thinking", blocks.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("先想想", blocks.get(0).getAsJsonObject().get("thinking").getAsString());
        assertEquals("sig123", blocks.get(0).getAsJsonObject().get("signature").getAsString());
        assertEquals("text", blocks.get(1).getAsJsonObject().get("type").getAsString());
        JsonObject use = blocks.get(2).getAsJsonObject();
        assertEquals("tool_use", use.get("type").getAsString());
        assertEquals(1, use.getAsJsonObject("input").get("x").getAsInt());
    }

    @Test
    void thinkingBlockWithoutSignatureIsDropped() {
        // 缺签名的思考块会被拒收:宁可不发思考块,不能发残块。
        AssistantTurn turn = new AssistantTurn("好的", List.of(), null, "无签名的思考");
        JsonArray blocks = P.assistantToRequestMessage(turn).getAsJsonArray("content");
        assertEquals(1, blocks.size());
        assertEquals("text", blocks.get(0).getAsJsonObject().get("type").getAsString());
    }

    // ---- 思考预算方言 ----

    @Test
    void budgetDialectMapsEffortAndRaisesMaxTokens() {
        JsonObject body = P.buildRequestBody("m", null, List.of(), new JsonArray());
        int base = body.get("max_tokens").getAsInt();
        P.applyReasoning(body, "high");
        JsonObject thinking = body.getAsJsonObject("thinking");
        assertEquals("enabled", thinking.get("type").getAsString());
        int budget = thinking.get("budget_tokens").getAsInt();
        assertTrue(budget > 0);
        // 预算必须小于输出上限:开思考后 max_tokens 抬到预算之上。
        assertTrue(body.get("max_tokens").getAsInt() > budget);
        assertTrue(body.get("max_tokens").getAsInt() >= base);
    }

    @Test
    void offAndNoneSendNothing() {
        JsonObject body = new JsonObject();
        P.applyReasoning(body, "off");
        assertEquals(0, body.size());

        AnthropicProvider none = new AnthropicProvider("site", "https://x/v1", LlmProvider.THINKING_NONE);
        JsonObject body2 = new JsonObject();
        none.applyReasoning(body2, "high");
        assertEquals(0, body2.size());
    }

    // ---- 鉴权/端点/流式开关 ----

    @Test
    void authIsApiKeyHeaderNotBearer() {
        Map<String, String> h = P.authHeaders("sk-ant-xxx");
        assertEquals("sk-ant-xxx", h.get("x-api-key"));
        assertTrue(h.containsKey("anthropic-version"));
        assertFalse(h.containsKey("Authorization"));
    }

    @Test
    void chatPathIsMessages() {
        assertEquals("/messages", P.chatPath());
    }

    @Test
    void streamingFlagHasNoStreamOptions() {
        JsonObject body = new JsonObject();
        P.applyStreaming(body);
        assertTrue(body.get("stream").getAsBoolean());
        assertFalse(body.has("stream_options"));
    }

    // ---- 流式累积 ----

    private static AssistantTurn stream(String... events) {
        StreamAccumulator acc = new StreamAccumulator();
        for (String e : events) P.accumulateChunk(j(e), acc);
        return P.finalizeStream(acc);
    }

    @Test
    void textThinkingAndToolUseAccumulate() {
        StreamAccumulator acc = new StreamAccumulator();
        P.accumulateChunk(j("{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":100}}}"), acc);
        P.accumulateChunk(j("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"想\"}}"), acc);
        P.accumulateChunk(j("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sigX\"}}"), acc);
        P.accumulateChunk(j("{\"type\":\"content_block_delta\",\"index\":1,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"答\"}}"), acc);
        P.accumulateChunk(j("{\"type\":\"content_block_start\",\"index\":2,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"tu9\",\"name\":\"look\"}}"), acc);
        P.accumulateChunk(j("{\"type\":\"content_block_delta\",\"index\":2,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"a\\\":\"}}"), acc);
        P.accumulateChunk(j("{\"type\":\"content_block_delta\",\"index\":2,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"2}\"}}"), acc);
        P.accumulateChunk(j("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},"
                + "\"usage\":{\"output_tokens\":30}}"), acc);
        AssistantTurn turn = P.finalizeStream(acc);

        assertEquals("答", turn.content());
        assertEquals("想", turn.reasoning());
        assertEquals("sigX", turn.extras().get(AnthropicProvider.SIGNATURE_KEY).getAsString());
        assertEquals("look", turn.toolCalls().get(0).name());
        assertEquals("{\"a\":2}", turn.toolCalls().get(0).arguments());
        assertEquals("tool_calls", acc.finishReason);
    }

    @Test
    void usageNormalisesToOpenAiFieldNames() {
        StreamAccumulator acc = new StreamAccumulator();
        P.accumulateChunk(j("{\"type\":\"message_start\",\"message\":{\"usage\":"
                + "{\"input_tokens\":50,\"cache_read_input_tokens\":200,\"cache_creation_input_tokens\":10}}}"), acc);
        P.accumulateChunk(j("{\"type\":\"message_delta\",\"delta\":{},\"usage\":{\"output_tokens\":40}}"), acc);
        // prompt 全量口径 = 50 + 200 + 10;total = prompt + completion。
        assertEquals(260, acc.usage.get("prompt_tokens").getAsInt());
        assertEquals(40, acc.usage.get("completion_tokens").getAsInt());
        assertEquals(300, acc.usage.get("total_tokens").getAsInt());
        // 四元拆开:输入 50、命中 200、写入 10、输出 40;新处理量 = 50 + 10 + 40。
        Usage u = P.usage(acc.usage);
        assertEquals(50, u.input());
        assertEquals(200, u.cacheRead());
        assertEquals(10, u.cacheWrite());
        assertEquals(100, u.fresh());
    }

    @Test
    void stopReasonsMapToUniformVocabulary() {
        StreamAccumulator acc = new StreamAccumulator();
        P.accumulateChunk(j("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}"), acc);
        assertEquals("stop", acc.finishReason);
        P.accumulateChunk(j("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}"), acc);
        assertEquals("length", acc.finishReason);
    }

    @Test
    void extractReasoningDeltaReadsThinkingDelta() {
        assertEquals("想", P.extractReasoningDelta(j(
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"想\"}}")));
        assertNull(P.extractReasoningDelta(j(
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"答\"}}")));
    }

    @Test
    void pingAndUnknownEventsAreIgnored() {
        AssistantTurn turn = stream("{\"type\":\"ping\"}", "{\"type\":\"message_stop\"}");
        assertEquals("", turn.content());
        assertFalse(turn.hasToolCalls());
    }

    // ---- 非流式 ----

    @Test
    void parseResponseBodyReadsContentBlocks() {
        AssistantTurn turn = P.parseResponseBody(j("{\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"想\",\"signature\":\"s\"},"
                + "{\"type\":\"text\",\"text\":\"答\"},"
                + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"look\",\"input\":{\"a\":1}}]}"));
        assertEquals("答", turn.content());
        assertEquals("想", turn.reasoning());
        assertEquals("s", turn.extras().get(AnthropicProvider.SIGNATURE_KEY).getAsString());
        assertEquals("look", turn.toolCalls().get(0).name());
    }
}
