package com.dwinovo.numen.api.agent.provider;

import com.dwinovo.numen.api.agent.catalog.ThinkingLevel;
import com.dwinovo.numen.api.agent.catalog.ThinkingTable;
import com.dwinovo.numen.api.agent.http.LlmStreamException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Responses 线格式:我们发出的条目与请求体,以及对流事件的解析。样本照 OpenAI 文档的事件形状与 pi-mono 的处理写。
 *
 * <p>这条协议我们要的就是"思考能带回下一轮"。item 里有 id 与加密内容,服务端按它校验
 * 思考与后面输出的配对,所以往返必须<b>一字不改</b>——往返那几条用例守的就是这一点。
 */
class OpenAIResponsesProviderTest {

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    private final OpenAIResponsesProvider p = new OpenAIResponsesProvider(new ResponsesCompat(true));

    private static final ModelSpec GPT6 = Wire.reasoning("gpt-6",
            "[{\"type\":\"effort\",\"values\":[\"none\",\"low\",\"medium\",\"high\"]}]");

    private static StreamAccumulator stream(String sample) {
        StreamAccumulator acc = new StreamAccumulator();
        OpenAIResponsesProvider p = new OpenAIResponsesProvider(new ResponsesCompat(true));
        for (JsonObject chunk : GoldenFiles.sseChunks("streams/" + sample + ".sse")) {
            p.accumulateChunk(chunk, acc);
        }
        return acc;
    }

    private StreamAccumulator events(String... frames) {
        StreamAccumulator acc = new StreamAccumulator();
        for (String f : frames) {
            p.accumulateChunk(json(f), acc);
        }
        return acc;
    }

    @Test
    void itPostsToTheResponsesEndpoint() {
        assertEquals("/responses", p.chatPath());
    }

    // ---- 请求侧:条目的形状 ----

    @Test
    void aUserMessageIsAnInputTextList() {
        JsonObject m = p.buildUserMessage("挖点铁");
        assertEquals("user", m.get("role").getAsString());
        JsonObject part = m.getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("input_text", part.get("type").getAsString());
        assertEquals("挖点铁", part.get("text").getAsString());
    }

    @Test
    void aToolResultIsKeyedByCallIdAndNeverEmpty() {
        JsonObject m = p.buildToolResultMessage("call_1", "{\"success\":true}");
        assertEquals("function_call_output", m.get("type").getAsString());
        assertEquals("call_1", m.get("call_id").getAsString());
        assertEquals("{\"success\":true}", m.get("output").getAsString());
        assertEquals("(no tool output)", p.buildToolResultMessage("call_2", "").get("output").getAsString());
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
    void theOutputCapIsNeverBelowTheFloorTheApiAccepts() {
        JsonObject body = Wire.body(p, GPT6, "s", List.of(), new RequestOptions(null, 4, ThinkingLevel.AUTO));
        assertEquals(16, body.get("max_output_tokens").getAsInt());
    }

    @Test
    void temperatureIsSentWhenTheModelTakesItEvenAtZero() {
        ModelSpec takes = new ModelSpec("gpt-4.1", ThinkingTable.AUTO_ONLY, true);
        ModelSpec rejects = new ModelSpec("gpt-5", ThinkingTable.AUTO_ONLY, false);
        RequestOptions zero = new RequestOptions(0.0, 0, ThinkingLevel.AUTO);
        assertEquals(0.0, Wire.body(p, takes, "s", List.of(), zero).get("temperature").getAsDouble());
        assertFalse(Wire.body(p, rejects, "s", List.of(), zero).has("temperature"));
    }

    // ---- 多轮回传 ----

    /** 把一段样本流解成回合,并取出样本里那条思考 item 的原文。 */
    private static JsonObject reasoningItemOf(String sample) {
        for (JsonObject chunk : GoldenFiles.sseChunks("streams/" + sample + ".sse")) {
            if ("response.output_item.done".equals(chunk.get("type").getAsString())
                    && "reasoning".equals(chunk.getAsJsonObject("item").get("type").getAsString())) {
                return chunk.getAsJsonObject("item");
            }
        }
        throw new AssertionError("样本里没有思考 item");
    }

    /** 带思考的一轮:思考、文本、工具调用依次回到 input 里,思考一字不改,文本与调用带回原来的 item id。 */
    @Test
    void aThinkingTurnComesBackWithItsReasoningItemAndItemIds() {
        AssistantTurn turn = p.finalizeStream(stream("responses-reasoning-toolcall"));

        List<JsonObject> out = p.assistantToRequestItems(turn);

        assertEquals(3, out.size());
        assertEquals(reasoningItemOf("responses-reasoning-toolcall"), out.get(0), "思考 item 一字不改,加密内容也在里面");
        JsonObject message = out.get(1);
        assertEquals("message", message.get("type").getAsString());
        assertEquals("assistant", message.get("role").getAsString());
        assertEquals("msg_1", message.get("id").getAsString());
        assertEquals("completed", message.get("status").getAsString());
        JsonObject text = message.getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("output_text", text.get("type").getAsString());
        assertEquals("这就去", text.get("text").getAsString());
        JsonObject call = out.get(2);
        assertEquals("function_call", call.get("type").getAsString());
        assertEquals("fc_1", call.get("id").getAsString());
        assertEquals("call_1", call.get("call_id").getAsString());
        assertEquals("look", call.get("name").getAsString());
        assertEquals("{\"x\":1}", call.get("arguments").getAsString());
    }

    /** 工具调用的一个来回:调用、结果、下一次提问依次进 input,结果靠 call_id 与调用配上。 */
    @Test
    void aToolRoundTripPairsTheResultWithTheCallById() {
        AssistantTurn turn = p.finalizeStream(stream("responses-reasoning-toolcall"));
        List<JsonObject> messages = new ArrayList<>();
        messages.add(p.buildUserMessage("看看周围"));
        messages.addAll(p.assistantToRequestItems(turn));
        messages.add(p.buildToolResultMessage(turn.toolCalls().get(0).id(), "{\"blocks\":3}"));
        messages.add(p.buildUserMessage("继续"));

        JsonArray input = Wire.body(p, GPT6, "你是同伴", messages,
                new RequestOptions(null, 0, ThinkingLevel.HIGH)).getAsJsonArray("input");

        List<String> shape = new ArrayList<>();
        for (var el : input) {
            JsonObject item = el.getAsJsonObject();
            shape.add(item.has("type") ? item.get("type").getAsString() : item.get("role").getAsString());
        }
        assertEquals(List.of("developer", "user", "reasoning", "message", "function_call",
                "function_call_output", "user"), shape);
        assertEquals(input.get(4).getAsJsonObject().get("call_id"), input.get(5).getAsJsonObject().get("call_id"));
    }

    @Test
    void severalReasoningItemsInOneReplyAllComeBack() {
        StreamAccumulator acc = events(
                "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\",\"summary\":[],\"encrypted_content\":\"A\"}}",
                "{\"type\":\"response.output_item.done\",\"output_index\":1,\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"arguments\":\"{}\",\"call_id\":\"call_1\",\"name\":\"look\"}}",
                "{\"type\":\"response.output_item.done\",\"output_index\":2,\"item\":{\"id\":\"rs_2\",\"type\":\"reasoning\",\"summary\":[],\"encrypted_content\":\"B\"}}",
                "{\"type\":\"response.completed\",\"response\":{}}");

        List<JsonObject> out = p.assistantToRequestItems(p.finalizeStream(acc));

        assertEquals(List.of("rs_1", "rs_2"), List.of(
                out.get(0).get("id").getAsString(), out.get(1).get("id").getAsString()));
    }

    /** 换过模型,私货被脱掉:没有思考 item,message 用占位 id,function_call 不带 id,空参数写成 {}。 */
    @Test
    void aTurnWithoutItsPrivateFieldsReplaysAsPlainItems() {
        AssistantTurn stripped = p.finalizeStream(stream("responses-reasoning-toolcall")).withoutProviderPrivateFields();

        List<JsonObject> out = p.assistantToRequestItems(new AssistantTurn(stripped.content(), List.of(
                new LlmToolCall("call_9", "look", "")), stripped.extras()));

        assertEquals(2, out.size());
        assertTrue(out.get(0).get("id").getAsString().startsWith("msg_"), "message 的 id 必填");
        assertEquals("function_call", out.get(1).get("type").getAsString());
        assertFalse(out.get(1).has("id"));
        assertEquals("{}", out.get(1).get("arguments").getAsString());
    }

    // ---- 流式事件 ----

    @Test
    void textOnlyStreamKeepsTheMessageIdAndPhase() {
        StreamAccumulator acc = stream("responses-text");
        AssistantTurn turn = p.finalizeStream(acc);
        assertEquals("挖到了八块铁。", turn.content());
        List<JsonObject> out = p.assistantToRequestItems(turn);
        assertEquals("msg_2", out.get(0).get("id").getAsString());
        assertEquals("final_answer", out.get(0).get("phase").getAsString());
    }

    @Test
    void anArgumentsDoneFrameOrTheFinishedItemSettlesTheFinalArguments() {
        StreamAccumulator acc = events(
                "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"arguments\":\"\",\"call_id\":\"call_1\",\"name\":\"mine\"}}",
                "{\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,\"delta\":\"{\\\"co\"}",
                "{\"type\":\"response.function_call_arguments.done\",\"output_index\":0,\"arguments\":\"{\\\"count\\\":8}\"}",
                "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"arguments\":\"{\\\"count\\\":8}\",\"call_id\":\"call_1\",\"name\":\"mine\"}}",
                "{\"type\":\"response.completed\",\"response\":{}}");
        assertEquals("{\"count\":8}", p.finalizeStream(acc).toolCalls().get(0).arguments());
    }

    @Test
    void aRefusalIsTheReply() {
        StreamAccumulator acc = events(
                "{\"type\":\"response.refusal.delta\",\"output_index\":0,\"delta\":\"这个我不能做\"}",
                "{\"type\":\"response.completed\",\"response\":{}}");
        assertEquals("这个我不能做", p.finalizeStream(acc).content());
    }

    @Test
    void anIncompleteReplyKeepsWhatArrived() {
        StreamAccumulator acc = events(
                "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"说到一半\"}",
                "{\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}}");
        AssistantTurn turn = p.finalizeStream(acc);
        assertEquals("说到一半", turn.content());
        assertEquals("incomplete", acc.finishReason);
    }

    // ---- 失败 ----

    @Test
    void responseFailedSurfacesTheVendorsCodeAndMessage() {
        StreamAccumulator acc = events(
                "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"半截\"}",
                "{\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"The model is overloaded.\"}}}");
        LlmStreamException e = assertThrows(LlmStreamException.class, () -> p.finalizeStream(acc));
        assertEquals("server_error: The model is overloaded.", e.getMessage());
    }

    @Test
    void responseFailedWithoutAnErrorSaysSo() {
        StreamAccumulator withReason = events(
                "{\"type\":\"response.failed\",\"response\":{\"incomplete_details\":{\"reason\":\"content_filter\"}}}");
        assertEquals("incomplete: content_filter",
                assertThrows(LlmStreamException.class, () -> p.finalizeStream(withReason)).getMessage());
        StreamAccumulator bare = events("{\"type\":\"response.failed\",\"response\":{}}");
        assertEquals("Unknown error (no error details in response)",
                assertThrows(LlmStreamException.class, () -> p.finalizeStream(bare)).getMessage());
    }

    @Test
    void anErrorFrameFailsTheStream() {
        StreamAccumulator acc = events(
                "{\"type\":\"error\",\"code\":\"rate_limit_exceeded\",\"message\":\"Slow down.\",\"param\":null}");
        assertEquals("Error Code rate_limit_exceeded: Slow down.",
                assertThrows(LlmStreamException.class, () -> p.finalizeStream(acc)).getMessage());
    }

    /** 流断了、没有收尾事件:前面收到的只是半截,不能当作一次成功的回应。 */
    @Test
    void aStreamThatEndsWithoutATerminalEventFails() {
        StreamAccumulator acc = events("{\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"半截\"}");
        assertThrows(LlmStreamException.class, () -> p.finalizeStream(acc));
    }

    // ---- 用量 ----

    @Test
    void usageSplitsCachedReadAndWriteOutOfInput() {
        Usage u = p.usage(json("""
                {"input_tokens":1200,"output_tokens":9,
                 "input_tokens_details":{"cached_tokens":1024,"cache_write_tokens":64},
                 "output_tokens_details":{"reasoning_tokens":4}}"""));
        assertEquals(new Usage(112, 9, 1024, 64), u);
        assertEquals(1200, u.promptTokens());
    }

    @Test
    void usageWithoutDetailsIsAllFreshInput() {
        assertEquals(new Usage(100, 20, 0, 0), p.usage(json("{\"input_tokens\":100,\"output_tokens\":20}")));
        assertEquals(Usage.ZERO, p.usage(null));
    }

    // ---- 非流式回应 ----

    @Test
    void parsingWalksTheOutputItemsAndKeepsTheIds() {
        AssistantTurn turn = p.parseResponseBody(json("""
                {"output":[
                  {"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"先看背包"},{"type":"summary_text","text":"再选工具"}]},
                  {"type":"message","id":"msg_1","content":[{"type":"output_text","text":"这就去"},{"type":"refusal","refusal":"但不挖基岩"}]},
                  {"type":"function_call","id":"fc_1","call_id":"call_1","name":"mine","arguments":"{}"}
                ]}"""));

        assertEquals("这就去但不挖基岩", turn.content());
        assertEquals("先看背包\n\n再选工具", turn.reasoning());
        assertEquals(1, turn.toolCalls().size());
        assertEquals("call_1", turn.toolCalls().get(0).id());
        assertEquals("rs_1", turn.extras().getAsJsonArray(OpenAIResponsesProvider.REASONING_ITEMS)
                .get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("fc_1", p.assistantToRequestItems(turn).get(2).get("id").getAsString());
    }
}
