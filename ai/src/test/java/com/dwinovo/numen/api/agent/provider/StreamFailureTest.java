package com.dwinovo.numen.api.agent.provider;

import com.dwinovo.numen.api.agent.http.LlmStreamException;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 流怎样算失败:三种协议共用 {@link StreamAccumulator#requireEnded} 这一处判据。
 * 契约用例对每种协议跑同一套(正常样本能收尾、收尾前断流失败、带错误帧失败并带出厂家原话),
 * 新加一种协议只要把它的样本和错误帧加进 {@link #protocols} 就受同样的约束。
 */
class StreamFailureTest {

    private static Stream<Arguments> protocols() {
        return Stream.of(
                Arguments.of("chat", "chat-openai-toolcall",
                        "{\"error\":{\"message\":\"Provider returned error\",\"code\":502}}",
                        "Provider returned error"),
                Arguments.of("responses", "responses-reasoning-toolcall",
                        "{\"type\":\"error\",\"code\":\"rate_limit_exceeded\",\"message\":\"Slow down.\"}",
                        "Error Code rate_limit_exceeded: Slow down."),
                Arguments.of("anthropic", "anthropic-thinking-toolcall",
                        "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}",
                        "Overloaded"));
    }

    private static LlmProvider provider(String kind) {
        return switch (kind) {
            case "chat" -> Wire.chat();
            case "responses" -> new OpenAIResponsesProvider(new ResponsesCompat(true));
            default -> new AnthropicProvider(new AnthropicCompat(false, true));
        };
    }

    /** 样本里让协议认为"收尾到了"的那一帧的位置。 */
    private static int terminalIndex(LlmProvider p, List<JsonObject> chunks) {
        StreamAccumulator acc = new StreamAccumulator();
        for (int i = 0; i < chunks.size(); i++) {
            p.accumulateChunk(chunks.get(i), acc);
            if (acc.ended) return i;
        }
        throw new AssertionError("样本里没有收尾");
    }

    private static StreamAccumulator feed(LlmProvider p, List<JsonObject> chunks) {
        StreamAccumulator acc = new StreamAccumulator();
        chunks.forEach(c -> p.accumulateChunk(c, acc));
        return acc;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protocols")
    void aCompleteStreamFinishes(String kind, String sample, String errorFrame, String vendorWords) {
        LlmProvider p = provider(kind);
        StreamAccumulator acc = feed(p, GoldenFiles.sseChunks("streams/" + sample + ".sse"));
        assertDoesNotThrow(() -> p.finalizeStream(acc));
    }

    /** 连接断了、收尾还没来:前面收到的只是半截回应。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protocols")
    void aStreamCutBeforeItsTerminalEventFails(String kind, String sample, String errorFrame, String vendorWords) {
        LlmProvider p = provider(kind);
        List<JsonObject> chunks = GoldenFiles.sseChunks("streams/" + sample + ".sse");
        StreamAccumulator acc = feed(p, chunks.subList(0, terminalIndex(p, chunks)));
        assertThrows(LlmStreamException.class, () -> p.finalizeStream(acc));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protocols")
    void anErrorFrameFailsTheStreamWithTheVendorsWords(String kind, String sample, String errorFrame,
                                                       String vendorWords) {
        LlmProvider p = provider(kind);
        List<JsonObject> chunks = GoldenFiles.sseChunks("streams/" + sample + ".sse");
        StreamAccumulator acc = feed(p, chunks.subList(0, terminalIndex(p, chunks)));
        p.accumulateChunk(Wire.json(errorFrame), acc);
        assertEquals(vendorWords, assertThrows(LlmStreamException.class, () -> p.finalizeStream(acc)).getMessage());
    }

    // ---- Anthropic 的异常收尾(照 pi 的 mapStopReason) ----

    private static StreamAccumulator anthropicEndingWith(String delta) {
        AnthropicProvider p = new AnthropicProvider(new AnthropicCompat(false, true));
        StreamAccumulator acc = new StreamAccumulator();
        p.accumulateChunk(Wire.json("{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1}}}"), acc);
        p.accumulateChunk(Wire.json("{\"type\":\"message_delta\",\"delta\":" + delta + ",\"usage\":{\"output_tokens\":1}}"), acc);
        p.accumulateChunk(Wire.json("{\"type\":\"message_stop\"}"), acc);
        return acc;
    }

    private static String anthropicFailure(String delta) {
        AnthropicProvider p = new AnthropicProvider(new AnthropicCompat(false, true));
        StreamAccumulator acc = anthropicEndingWith(delta);
        return assertThrows(LlmStreamException.class, () -> p.finalizeStream(acc)).getMessage();
    }

    @Test
    void anthropicRefusalFailsWithItsExplanation() {
        assertEquals("No bioweapons.", anthropicFailure(
                "{\"stop_reason\":\"refusal\",\"stop_details\":{\"explanation\":\"No bioweapons.\"}}"));
        assertEquals("The model refused to complete the request", anthropicFailure("{\"stop_reason\":\"refusal\"}"));
    }

    @Test
    void anthropicSafetyFilterAndUnknownStopReasonsFail() {
        assertEquals("Provider stopped with: sensitive", anthropicFailure("{\"stop_reason\":\"sensitive\"}"));
        assertEquals("Unhandled stop reason: brand_new", anthropicFailure("{\"stop_reason\":\"brand_new\"}"));
    }

    @Test
    void anthropicPauseTurnAndMaxTokensAreOrdinaryEndings() {
        AnthropicProvider p = new AnthropicProvider(new AnthropicCompat(false, true));
        for (String reason : List.of("pause_turn", "max_tokens", "end_turn", "stop_sequence")) {
            StreamAccumulator acc = anthropicEndingWith("{\"stop_reason\":\"" + reason + "\"}");
            assertDoesNotThrow(() -> p.finalizeStream(acc), reason);
        }
    }

    @Test
    void anthropicMessageStopWithoutAStopReasonIsNotAnEnding() {
        assertEquals("stream ended before its terminal event", anthropicFailure("{}"));
    }

    // ---- Chat 的异常收尾(照 pi 的 mapStopReason) ----

    private static StreamAccumulator chatFinishing(String reason) {
        StreamAccumulator acc = new StreamAccumulator();
        Wire.chat().accumulateChunk(Wire.json(
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"半\"},\"finish_reason\":\"" + reason + "\"}]}"), acc);
        return acc;
    }

    @Test
    void chatFinishReasonsOtherThanTheOrdinaryOnesFail() {
        for (String reason : List.of("content_filter", "network_error", "insufficient_system_resource")) {
            StreamAccumulator acc = chatFinishing(reason);
            assertEquals("Provider finish_reason: " + reason,
                    assertThrows(LlmStreamException.class, () -> Wire.chat().finalizeStream(acc)).getMessage());
        }
    }

    @Test
    void chatOrdinaryFinishReasonsFinish() {
        for (String reason : List.of("stop", "end", "length", "tool_calls", "function_call")) {
            StreamAccumulator acc = chatFinishing(reason);
            assertDoesNotThrow(() -> Wire.chat().finalizeStream(acc), reason);
        }
    }
}
