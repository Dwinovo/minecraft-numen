package com.dwinovo.numen.api.agent.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anthropic Messages 协议适配的全面卷:请求形状(顶层 system/角色归并/工具
 * 平铺)、流式累积(块级事件/思考签名/usage 归一)、思考预算方言、鉴权与端点。
 */
class AnthropicProviderTest {

    private static final AnthropicProvider P = new AnthropicProvider(new AnthropicCompat(false, true));
    private static final ModelSpec M = Wire.PLAIN;
    private static final RequestOptions NONE = RequestOptions.NONE;

    private static JsonObject build(String system, List<JsonObject> messages, JsonArray tools) {
        return P.buildRequestBody(M, system, messages, tools, NONE, null);
    }

    private static JsonObject j(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ---- 请求形状 ----

    @Test
    void systemIsTopLevelNotAMessage() {
        JsonObject body = build("你是同伴", List.of(P.buildUserMessage("你好")), new JsonArray());
        JsonObject system = body.getAsJsonArray("system").get(0).getAsJsonObject();
        assertEquals("你是同伴", system.get("text").getAsString());
        JsonArray msgs = body.getAsJsonArray("messages");
        assertEquals(1, msgs.size());
        assertEquals("user", msgs.get(0).getAsJsonObject().get("role").getAsString());
    }

    @Test
    void cacheBreakpointsOnLastToolSystemAndLastTwoBlocks() {
        // 不打断点就一个 token 都不缓存:工具、system、历史末尾与最后一块各一个,共 4 个(协议上限)。
        IToolSpec look = tool("look");
        IToolSpec dig = tool("dig");
        JsonObject extras = new JsonObject();
        extras.addProperty(AnthropicProvider.SIGNATURE_KEY, "sig");
        List<JsonObject> messages = List.of(
                P.buildUserMessage("去挖矿"),
                P.assistantToRequestItems(new AssistantTurn("",
                        List.of(new LlmToolCall("tu1", "dig", "{}")), extras, "想一想")).get(0),
                P.buildToolResultMessage("tu1", "挖到了"),
                P.buildUserMessage("<runtime_state/>"));
        JsonObject body = build("你是同伴", messages, P.buildToolList(List.of(look, dig)));

        assertTrue(body.getAsJsonArray("system").get(0).getAsJsonObject().has("cache_control"));
        JsonArray tools = body.getAsJsonArray("tools");
        assertFalse(tools.get(0).getAsJsonObject().has("cache_control"));
        assertEquals("ephemeral", tools.get(1).getAsJsonObject()
                .getAsJsonObject("cache_control").get("type").getAsString());

        JsonArray msgs = body.getAsJsonArray("messages");
        JsonArray last = msgs.get(2).getAsJsonObject().getAsJsonArray("content");
        assertTrue(last.get(0).getAsJsonObject().has("cache_control"));   // tool_result:历史末尾
        assertTrue(last.get(1).getAsJsonObject().has("cache_control"));   // runtime_state
        JsonArray assistant = msgs.get(1).getAsJsonObject().getAsJsonArray("content");
        assertFalse(assistant.get(0).getAsJsonObject().has("cache_control"));
        assertFalse(msgs.get(0).getAsJsonObject().getAsJsonArray("content")
                .get(0).getAsJsonObject().has("cache_control"));
    }

    @Test
    void thinkingBlocksAreNeverMarked() {
        // 思考块不能打断点:最后两块落在思考块上时跳过它往前找。
        JsonObject extras = new JsonObject();
        extras.addProperty(AnthropicProvider.SIGNATURE_KEY, "sig");
        JsonObject assistant = P.assistantToRequestItems(
                new AssistantTurn("好", List.of(), extras, "想一想")).get(0);
        JsonObject body = build(null, List.of(P.buildUserMessage("你好"), assistant), new JsonArray());
        JsonArray msgs = body.getAsJsonArray("messages");
        JsonArray blocks = msgs.get(1).getAsJsonObject().getAsJsonArray("content");
        assertFalse(blocks.get(0).getAsJsonObject().has("cache_control"));
        assertTrue(blocks.get(1).getAsJsonObject().has("cache_control"));
        assertTrue(msgs.get(0).getAsJsonObject().getAsJsonArray("content")
                .get(0).getAsJsonObject().has("cache_control"));
    }

    @Test
    void markingDoesNotTouchCallerObjects() {
        JsonArray tools = P.buildToolList(List.of(tool("look")));
        JsonObject user = P.buildToolResultMessage("t1", "结果");
        build(null, List.of(user), tools);
        assertFalse(tools.get(0).getAsJsonObject().has("cache_control"));
        assertFalse(user.getAsJsonArray("content").get(0).getAsJsonObject().has("cache_control"));
    }

    private static IToolSpec tool(String name) {
        return new IToolSpec() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> parameterSchema() { return Map.of("type", "object"); }
        };
    }

    @Test
    void maxTokensAlwaysPresent() {
        JsonObject body = build(null, List.of(), new JsonArray());
        assertTrue(body.get("max_tokens").getAsInt() > 0);
    }

    @Test
    void consecutiveUserMessagesMergeIntoOne() {
        // 并行工具结果 = 连续多条 user 消息,必须并成一条多块消息,否则 400。
        JsonObject body = build(null, List.of(
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
        JsonArray blocks = P.assistantToRequestItems(turn).get(0).getAsJsonArray("content");
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
        JsonArray blocks = P.assistantToRequestItems(turn).get(0).getAsJsonArray("content");
        assertEquals(1, blocks.size());
        assertEquals("text", blocks.get(0).getAsJsonObject().get("type").getAsString());
    }

    // ---- 思考:预算写法与自适应写法 ----

    private static final String EFFORT_AND_BUDGET =
            "[{\"type\":\"effort\",\"values\":[\"low\",\"medium\",\"high\"]},{\"type\":\"budget_tokens\",\"min\":1024}]";
    private static final String EFFORT_ONLY =
            "[{\"type\":\"effort\",\"values\":[\"low\",\"medium\",\"high\",\"xhigh\"]}]";

    private static RequestOptions think(com.dwinovo.numen.api.agent.catalog.ThinkingLevel level) {
        return new RequestOptions(null, 0, level);
    }

    @Test
    void budgetThinkingMapsTheLevelAndRaisesMaxTokens() {
        ModelSpec m = Wire.reasoning("claude-opus-4-5", EFFORT_AND_BUDGET);
        JsonObject body = P.buildRequestBody(m, null, List.of(), new JsonArray(),
                think(com.dwinovo.numen.api.agent.catalog.ThinkingLevel.HIGH), null);
        JsonObject thinking = body.getAsJsonObject("thinking");
        assertEquals("enabled", thinking.get("type").getAsString());
        assertEquals(16_384, thinking.get("budget_tokens").getAsInt());
        // 预算必须小于输出上限:开思考后 max_tokens 抬到预算之上。
        assertEquals(8192 + 16_384, body.get("max_tokens").getAsInt());
        assertFalse(body.has("output_config"));
    }

    @Test
    void thinkingBudgetStacksOnTopOfThePlayersMaxTokens() {
        ModelSpec m = Wire.reasoning("claude-opus-4-5", EFFORT_AND_BUDGET);
        JsonObject body = P.buildRequestBody(m, null, List.of(), new JsonArray(),
                new RequestOptions(null, 4096, com.dwinovo.numen.api.agent.catalog.ThinkingLevel.MEDIUM), null);
        assertEquals(4096 + 8_192, body.get("max_tokens").getAsInt(), "玩家设的值仍然是正文的上限");
    }

    @Test
    void adaptiveThinkingSendsEffortAndNoBudget() {
        AnthropicProvider adaptive = new AnthropicProvider(new AnthropicCompat(true, true));
        ModelSpec m = Wire.reasoning("claude-opus-5", EFFORT_ONLY);
        JsonObject body = adaptive.buildRequestBody(m, null, List.of(), new JsonArray(),
                think(com.dwinovo.numen.api.agent.catalog.ThinkingLevel.MAX), null);
        assertEquals("adaptive", body.getAsJsonObject("thinking").get("type").getAsString());
        assertFalse(body.getAsJsonObject("thinking").has("budget_tokens"));
        assertEquals("xhigh", body.getAsJsonObject("output_config").get("effort").getAsString(),
                "最高档在这个型号上写成它认的字");
        assertEquals(8192, body.get("max_tokens").getAsInt());
    }

    @Test
    void autoAndUnsupportedLevelsSendNoThinkingAtAll() {
        ModelSpec m = Wire.reasoning("claude-opus-4-5", EFFORT_AND_BUDGET);
        for (var level : new com.dwinovo.numen.api.agent.catalog.ThinkingLevel[]{
                com.dwinovo.numen.api.agent.catalog.ThinkingLevel.AUTO,
                com.dwinovo.numen.api.agent.catalog.ThinkingLevel.MAX}) {   // 这个型号没有最高档
            JsonObject body = P.buildRequestBody(m, null, List.of(), new JsonArray(), think(level), null);
            assertFalse(body.has("thinking"), level.name());
            assertFalse(body.has("output_config"), level.name());
            assertEquals(8192, body.get("max_tokens").getAsInt(), level.name());
        }
        // 关显式写 disabled(自动才是什么都不发),不带预算,max_tokens 不变
        JsonObject off = P.buildRequestBody(m, null, List.of(), new JsonArray(),
                think(com.dwinovo.numen.api.agent.catalog.ThinkingLevel.OFF), null);
        assertEquals("disabled", off.getAsJsonObject("thinking").get("type").getAsString());
        assertFalse(off.getAsJsonObject("thinking").has("budget_tokens"));
        assertFalse(off.has("output_config"));
        assertEquals(8192, off.get("max_tokens").getAsInt());
        // 目录里查不到的型号:任何档位都不发
        JsonObject unknown = P.buildRequestBody(M, null, List.of(), new JsonArray(),
                think(com.dwinovo.numen.api.agent.catalog.ThinkingLevel.HIGH), null);
        assertFalse(unknown.has("thinking"));
    }

    @Test
    void temperatureIsDroppedWhileThinkingOrWhenEitherSideRefusesIt() {
        ModelSpec m = Wire.reasoning("claude-opus-4-5", EFFORT_AND_BUDGET);
        var level = com.dwinovo.numen.api.agent.catalog.ThinkingLevel.MEDIUM;
        assertFalse(P.buildRequestBody(m, null, List.of(), new JsonArray(),
                new RequestOptions(0.7, 0, level), null).has("temperature"), "开思考时协议只允许默认温度");
        assertEquals(0.7, P.buildRequestBody(m, null, List.of(), new JsonArray(),
                new RequestOptions(0.7, 0, com.dwinovo.numen.api.agent.catalog.ThinkingLevel.AUTO), null)
                .get("temperature").getAsDouble());
        // 端点不收温度(兼容项),或型号不收温度(目录)
        assertFalse(new AnthropicProvider(new AnthropicCompat(false, false)).buildRequestBody(m, null, List.of(),
                new JsonArray(), new RequestOptions(0.7, 0, com.dwinovo.numen.api.agent.catalog.ThinkingLevel.AUTO), null)
                .has("temperature"));
        ModelSpec noTemp = new ModelSpec("m", m.thinking(), false);
        assertFalse(P.buildRequestBody(noTemp, null, List.of(), new JsonArray(),
                new RequestOptions(0.7, 0, com.dwinovo.numen.api.agent.catalog.ThinkingLevel.AUTO), null)
                .has("temperature"));
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
        JsonObject body = build(null, List.of(), new JsonArray());
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
        P.accumulateChunk(j("{\"type\":\"message_stop\"}"), acc);
        AssistantTurn turn = P.finalizeStream(acc);

        assertEquals("答", turn.content());
        assertEquals("想", turn.reasoning());
        assertEquals("sigX", turn.extras().get(AnthropicProvider.SIGNATURE_KEY).getAsString());
        assertEquals("look", turn.toolCalls().get(0).name());
        assertEquals("{\"a\":2}", turn.toolCalls().get(0).arguments());
        assertEquals("tool_calls", acc.finishReason);
    }

    @Test
    void usageIsMergedFromBothHalvesAndKeepsCacheReadAndWriteApart() {
        StreamAccumulator acc = new StreamAccumulator();
        P.accumulateChunk(j("{\"type\":\"message_start\",\"message\":{\"usage\":"
                + "{\"input_tokens\":50,\"cache_read_input_tokens\":200,\"cache_creation_input_tokens\":10}}}"), acc);
        P.accumulateChunk(j("{\"type\":\"message_delta\",\"delta\":{},\"usage\":{\"output_tokens\":40}}"), acc);
        // 本协议的 input_tokens 不含缓存:输入 50、命中 200、写入 10、输出 40;新处理量 = 50 + 10 + 40。
        Usage u = P.usage(acc.usage);
        assertEquals(50, u.input());
        assertEquals(200, u.cacheRead());
        assertEquals(10, u.cacheWrite());
        assertEquals(40, u.output());
        assertEquals(100, u.fresh());
        assertEquals(260, u.promptTokens());
    }

    /** 代理在 message_delta 里把输入与缓存量写成 null 或省掉:message_start 里的值不能被冲掉。 */
    @Test
    void aDeltaThatOmitsOrNullsTheInputFieldsKeepsTheStartValues() {
        StreamAccumulator acc = new StreamAccumulator();
        P.accumulateChunk(j("{\"type\":\"message_start\",\"message\":{\"usage\":"
                + "{\"input_tokens\":50,\"cache_read_input_tokens\":200}}}"), acc);
        P.accumulateChunk(j("{\"type\":\"message_delta\",\"delta\":{},\"usage\":"
                + "{\"input_tokens\":null,\"output_tokens\":40}}"), acc);
        assertEquals(new Usage(50, 40, 200, 0), P.usage(acc.usage));
        // 带了值就以 delta 为准(累计量)
        P.accumulateChunk(j("{\"type\":\"message_delta\",\"delta\":{},\"usage\":"
                + "{\"input_tokens\":60,\"cache_read_input_tokens\":210,\"output_tokens\":45}}"), acc);
        assertEquals(new Usage(60, 45, 210, 0), P.usage(acc.usage));
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
    void pingAndUnknownEventsAreIgnored() {
        AssistantTurn turn = stream("{\"type\":\"ping\"}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}",
                "{\"type\":\"message_stop\"}");
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
