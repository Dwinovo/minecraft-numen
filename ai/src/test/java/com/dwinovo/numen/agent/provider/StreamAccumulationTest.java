package com.dwinovo.numen.agent.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式累积的既有行为钉死:正文碎片、工具调用参数分块、usage 帧、finish_reason、
 * Moonshot 回传兜底。这些是全家族共用的底盘,回归即事故。
 */
class StreamAccumulationTest {

    private static JsonObject chunk(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void contentFragmentsConcatenate() {
        OpenAIProvider p = new OpenAIProvider();
        StreamAccumulator acc = new StreamAccumulator();
        p.accumulateChunk(chunk("{\"choices\":[{\"delta\":{\"content\":\"你\"}}]}"), acc);
        p.accumulateChunk(chunk("{\"choices\":[{\"delta\":{\"content\":\"好\"}}]}"), acc);
        assertEquals("你好", p.finalizeStream(acc).content());
        assertEquals(2, acc.chunkCount);
    }

    @Test
    void toolCallArgumentsAssembleAcrossChunks() {
        OpenAIProvider p = new OpenAIProvider();
        StreamAccumulator acc = new StreamAccumulator();
        p.accumulateChunk(chunk("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"c1\",\"function\":{\"name\":\"move_to\",\"arguments\":\"{\\\"x\\\":\"}}]}}]}"), acc);
        p.accumulateChunk(chunk("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"1}\"}}]}}]}"), acc);
        AssistantTurn turn = p.finalizeStream(acc);
        assertTrue(turn.hasToolCalls());
        LlmToolCall tc = turn.toolCalls().get(0);
        assertEquals("c1", tc.id());
        assertEquals("move_to", tc.name());
        assertEquals("{\"x\":1}", tc.arguments());
    }

    @Test
    void usageAndFinishReasonCaptured() {
        OpenAIProvider p = new OpenAIProvider();
        StreamAccumulator acc = new StreamAccumulator();
        p.accumulateChunk(chunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"), acc);
        p.accumulateChunk(chunk("{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"total_tokens\":15}}"), acc);
        assertEquals("stop", acc.finishReason);
        assertEquals(10, acc.usage.get("prompt_tokens").getAsInt());
    }

    @Test
    void moonshotBackstopInjectsReasoningContentOnToolCalls() {
        // 带 tool_calls 却缺 reasoning_content 的 assistant 消息会被 Moonshot 拒收:
        // 兜底注入单个空格;字段已在时不得覆盖。
        MoonshotProvider p = new MoonshotProvider();
        AssistantTurn bare = new AssistantTurn("", java.util.List.of(
                new LlmToolCall("id1", "look", "{}")), null);
        assertEquals(" ", p.assistantToRequestMessage(bare).get("reasoning_content").getAsString());

        JsonObject extras = new JsonObject();
        extras.addProperty("reasoning_content", "已有");
        AssistantTurn kept = new AssistantTurn("", java.util.List.of(
                new LlmToolCall("id1", "look", "{}")), extras);
        assertEquals("已有", p.assistantToRequestMessage(kept).get("reasoning_content").getAsString());
    }

    @Test
    void threeArgConstructorDefaultsReasoningEmpty() {
        AssistantTurn turn = new AssistantTurn("c", java.util.List.of(), null);
        assertEquals("", turn.reasoning());
    }
}
