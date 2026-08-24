package com.dwinovo.numen.agent.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 思考流入向:方言字段识别、字段锁定去重、extras 回传与展示面的分流。
 */
class ReasoningStreamTest {

    private static JsonObject chunk(String deltaJson) {
        return JsonParser.parseString(
                "{\"choices\":[{\"delta\":" + deltaJson + "}]}").getAsJsonObject();
    }

    private static AssistantTurn stream(OpenAIProvider p, String... deltas) {
        StreamAccumulator acc = new StreamAccumulator();
        for (String d : deltas) p.accumulateChunk(chunk(d), acc);
        return p.finalizeStream(acc);
    }

    @Test
    void reasoningContentFlowsToTurnReasoning() {
        AssistantTurn turn = stream(new OpenAIProvider(),
                "{\"reasoning_content\":\"先看\"}",
                "{\"reasoning_content\":\"看背包\"}",
                "{\"content\":\"好的\"}");
        assertEquals("先看看背包", turn.reasoning());
        assertEquals("好的", turn.content());
    }

    @Test
    void reasoningStaysOutOfContentAndContentOutOfReasoning() {
        AssistantTurn turn = stream(new OpenAIProvider(),
                "{\"reasoning_content\":\"思\"}", "{\"content\":\"答\"}");
        assertEquals("思", turn.reasoning());
        assertEquals("答", turn.content());
    }

    @Test
    void reasoningContentStillRoundTripsViaExtras() {
        // 展示面分流不得动摇回传保命:reasoning_content 必须照旧进 extras 回注。
        AssistantTurn turn = stream(new OpenAIProvider(),
                "{\"reasoning_content\":\"a\"}", "{\"reasoning_content\":\"b\"}");
        assertEquals("ab", turn.extras().get("reasoning_content").getAsString());
        JsonObject wire = new OpenAIProvider().assistantToRequestMessage(turn);
        assertEquals("ab", wire.get("reasoning_content").getAsString());
        // 展示字段 reasoning 不是线格式的一部分,不得出现在请求消息里。
        assertFalse(wire.has("reasoning") && !turn.extras().has("reasoning"));
    }

    @Test
    void openRouterStyleReasoningFieldRecognised() {
        AssistantTurn turn = stream(new OpenAIProvider(),
                "{\"reasoning\":\"think\"}", "{\"reasoning\":\"ing\"}");
        assertEquals("thinking", turn.reasoning());
    }

    @Test
    void reasoningTextFieldRecognised() {
        AssistantTurn turn = stream(new OpenAIProvider(), "{\"reasoning_text\":\"t\"}");
        assertEquals("t", turn.reasoning());
    }

    @Test
    void firstNonEmptyFieldLocksAndDuplicateDialectIgnored() {
        // 某些站同流双字段同文:锁定首个非空字段后,另一字段不得重复计入展示面。
        AssistantTurn turn = stream(new OpenAIProvider(),
                "{\"reasoning_content\":\"x\",\"reasoning\":\"x\"}",
                "{\"reasoning_content\":\"y\",\"reasoning\":\"y\"}");
        assertEquals("xy", turn.reasoning());
    }

    @Test
    void noReasoningMeansEmptyNotNull() {
        AssistantTurn turn = stream(new OpenAIProvider(), "{\"content\":\"hi\"}");
        assertEquals("", turn.reasoning());
        assertFalse(turn.hasReasoning());
    }

    @Test
    void nonStringExtrasArraysMergeAcrossChunks() {
        // 聚合网关的 reasoning_details 是数组增量:必须并入 extras,不得静默丢弃。
        AssistantTurn turn = stream(new OpenAIProvider(),
                "{\"reasoning_details\":[{\"type\":\"a\"}]}",
                "{\"reasoning_details\":[{\"type\":\"b\"}]}");
        JsonArray details = turn.extras().getAsJsonArray("reasoning_details");
        assertEquals(2, details.size());
        assertEquals("a", details.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("b", details.get(1).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void extractReasoningDeltaUnwrapsChunk() {
        OpenAIProvider p = new OpenAIProvider();
        assertEquals("思", p.extractReasoningDelta(chunk("{\"reasoning_content\":\"思\"}")));
        assertNull(p.extractReasoningDelta(chunk("{\"content\":\"正文\"}")));
        assertNull(p.extractReasoningDelta(JsonParser.parseString("{}").getAsJsonObject()));
    }

    @Test
    void parseResponseBodyExtractsReasoningNonStreaming() {
        JsonObject body = JsonParser.parseString(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"c\","
                + "\"reasoning_content\":\"r\"}}]}").getAsJsonObject();
        AssistantTurn turn = new OpenAIProvider().parseResponseBody(body);
        assertEquals("c", turn.content());
        assertEquals("r", turn.reasoning());
        assertTrue(turn.extras().has("reasoning_content"));
    }
}
