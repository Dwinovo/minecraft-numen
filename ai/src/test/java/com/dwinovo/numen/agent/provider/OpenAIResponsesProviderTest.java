package com.dwinovo.numen.agent.provider;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Responses 线格式:消息 → 条目流,以及思考 item 的往返。
 *
 * <p>这条协议我们要的就是"思考能带回下一轮"。item 里有 id 与加密内容,服务端按它校验
 * 工具调用与思考的配对,所以往返必须<b>一字不改</b>——这几条用例守的就是这一点。
 */
class OpenAIResponsesProviderTest {

    private static final Gson GSON = new Gson();

    private static JsonObject json(String raw) {
        return GSON.fromJson(raw, JsonObject.class);
    }

    private final OpenAIResponsesProvider p = new OpenAIResponsesProvider();

    @Test
    void itPostsToTheResponsesEndpoint() {
        assertEquals("/responses", p.chatPath());
    }

    // ---- 请求侧 ----

    @Test
    void aUserMessageBecomesAnInputTextItem() {
        JsonObject m = p.buildUserMessage("挖点铁");
        assertEquals("message", m.get("type").getAsString());
        assertEquals("user", m.get("role").getAsString());
        JsonObject part = m.getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("input_text", part.get("type").getAsString());
        assertEquals("挖点铁", part.get("text").getAsString());
    }

    @Test
    void aToolResultBecomesItsOwnItemKeyedByCallId() {
        JsonObject m = p.buildToolResultMessage("call_1", "{\"success\":true}");
        assertEquals("function_call_output", m.get("type").getAsString());
        assertEquals("call_1", m.get("call_id").getAsString());
        assertEquals("{\"success\":true}", m.get("output").getAsString());
    }

    /** 一条助手回合拆成多条:思考在最前(配对靠顺序),然后文本,然后每个工具调用一条。 */
    @Test
    void oneAssistantTurnBecomesReasoningThenTextThenCalls() {
        JsonObject extras = new JsonObject();
        JsonArray items = new JsonArray();
        items.add(json("{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"abc\"}"));
        extras.add(OpenAIResponsesProvider.REASONING_ITEMS, items);
        AssistantTurn turn = new AssistantTurn("这就去", List.of(
                new LlmToolCall("call_1", "mine", "{\"count\":8}")), extras, "想了想");

        List<JsonObject> out = p.assistantToRequestItems(turn);

        assertEquals(3, out.size());
        assertEquals("reasoning", out.get(0).get("type").getAsString());
        assertEquals("abc", out.get(0).get("encrypted_content").getAsString(), "加密内容要一字不改地回去");
        assertEquals("message", out.get(1).get("type").getAsString());
        assertEquals("function_call", out.get(2).get("type").getAsString());
        assertEquals("call_1", out.get(2).get("call_id").getAsString());
    }

    @Test
    void aTurnWithoutReasoningJustCarriesItsText() {
        AssistantTurn turn = new AssistantTurn("好", List.of(), new JsonObject(), "");
        List<JsonObject> out = p.assistantToRequestItems(turn);
        assertEquals(1, out.size());
        assertEquals("message", out.get(0).get("type").getAsString());
    }

    /** Responses 的工具是平的:没有 chat/completions 那层 function 包装。 */
    @Test
    void toolsAreFlatNotWrapped() {
        JsonArray tools = p.buildToolList(List.of(new IToolSpec() {
            @Override public String name() { return "mine"; }
            @Override public String description() { return "Mine blocks."; }
            @Override public Map<String, Object> parameterSchema() { return Map.of("type", "object"); }
        }));
        JsonObject t = tools.get(0).getAsJsonObject();
        assertEquals("function", t.get("type").getAsString());
        assertEquals("mine", t.get("name").getAsString());
        assertFalse(t.has("function"), "不该再套一层");
    }

    @Test
    void theBodyCarriesInputAndAsksForEncryptedReasoningWithoutServerSideStorage() {
        JsonObject body = p.buildRequestBody("gpt-6-astra", "你是同伴",
                List.of(p.buildUserMessage("嗨")), new JsonArray());
        assertEquals("gpt-6-astra", body.get("model").getAsString());
        JsonArray input = body.getAsJsonArray("input");
        assertEquals("developer", input.get(0).getAsJsonObject().get("role").getAsString(), "系统话走 developer");
        assertEquals("user", input.get(1).getAsJsonObject().get("role").getAsString());
        assertFalse(body.get("store").getAsBoolean(), "历史是我们自己的账本,不让服务端另存一份");
        assertEquals("reasoning.encrypted_content", body.getAsJsonArray("include").get(0).getAsString());
    }

    // ---- 响应侧 ----

    @Test
    void parsingWalksTheOutputItems() {
        AssistantTurn turn = p.parseResponseBody(json("""
                {"output":[
                  {"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"先看背包"}]},
                  {"type":"message","content":[{"type":"output_text","text":"这就去"}]},
                  {"type":"function_call","call_id":"call_1","name":"mine","arguments":"{}"}
                ]}"""));

        assertEquals("这就去", turn.content());
        assertEquals("先看背包", turn.reasoning());
        assertEquals(1, turn.toolCalls().size());
        assertEquals("mine", turn.toolCalls().get(0).name());
        assertTrue(turn.extras().has(OpenAIResponsesProvider.REASONING_ITEMS), "思考原文要收着,下一轮还要还回去");
    }

    // ---- 流式 ----

    @Test
    void theStreamAssemblesText_reasoning_callsAndUsage() {
        StreamAccumulator acc = new StreamAccumulator();
        p.accumulateChunk(json("{\"type\":\"response.reasoning_text.delta\",\"delta\":\"先看\"}"), acc);
        p.accumulateChunk(json("{\"type\":\"response.reasoning_text.delta\",\"delta\":\"背包\"}"), acc);
        p.accumulateChunk(json("{\"type\":\"response.output_text.delta\",\"delta\":\"这就\"}"), acc);
        p.accumulateChunk(json("{\"type\":\"response.output_text.delta\",\"delta\":\"去\"}"), acc);
        p.accumulateChunk(json("""
                {"type":"response.output_item.added","output_index":0,
                 "item":{"type":"function_call","call_id":"call_1","name":"mine"}}"""), acc);
        p.accumulateChunk(json("""
                {"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\\"count\\":8}"}"""), acc);
        p.accumulateChunk(json("""
                {"type":"response.output_item.done",
                 "item":{"type":"reasoning","id":"rs_1","encrypted_content":"abc"}}"""), acc);
        p.accumulateChunk(json("""
                {"type":"response.completed","response":{"usage":{"input_tokens":100,"output_tokens":20,
                 "input_tokens_details":{"cached_tokens":80},
                 "output_tokens_details":{"reasoning_tokens":12}}}}"""), acc);

        AssistantTurn turn = p.finalizeStream(acc);
        assertEquals("这就去", turn.content());
        assertEquals("先看背包", turn.reasoning());
        assertEquals(1, turn.toolCalls().size());
        assertEquals("{\"count\":8}", turn.toolCalls().get(0).arguments());
        assertTrue(turn.extras().has(OpenAIResponsesProvider.REASONING_ITEMS),
                "完整的思考 item 只在 done 事件里出现,错过就再也带不回去了");

        // 四元口径照旧:input 只记这轮真正新处理的,缓存单列,总账仍是 promptTokens()
        Usage usage = p.usage(acc.usage);
        assertEquals(100, usage.promptTokens());
        assertEquals(80, usage.cacheRead());
        assertEquals(20, usage.input());
        assertEquals(20, usage.output());
    }

    /** 思考增量要能喂给面板的思考块,和 chat/completions 那条同一个出口。 */
    @Test
    void reasoningDeltasAreVisibleToTheUi() {
        assertEquals("先看", p.extractReasoningDelta(
                json("{\"type\":\"response.reasoning_text.delta\",\"delta\":\"先看\"}")));
        assertEquals("", p.extractReasoningDelta(
                json("{\"type\":\"response.output_text.delta\",\"delta\":\"这就去\"}")));
    }
}
