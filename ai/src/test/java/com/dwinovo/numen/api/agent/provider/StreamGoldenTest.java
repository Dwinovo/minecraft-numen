package com.dwinovo.numen.api.agent.provider;

import com.google.gson.JsonObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 每种协议的流式返回黄金对照:一份 SSE 流({@code golden/streams/*.sse})喂进协议实现,
 * 解出的文字、思考、工具调用、私货、结束原因、用量四元要和 {@code expected.json} 一致。
 * Chat 协议上几家的缓存字段口径(OpenAI 的 details、DeepSeek 的命中/未命中、OpenRouter 的缓存写、
 * Kimi 的顶层 cached_tokens)各有一份。
 */
class StreamGoldenTest {

    private static LlmProvider provider(String kind) {
        return switch (kind) {
            case "chat" -> Wire.chat();
            case "responses" -> new OpenAIResponsesProvider(new ResponsesCompat(true));
            case "anthropic" -> new AnthropicProvider(new AnthropicCompat(false, true));
            default -> throw new IllegalArgumentException(kind);
        };
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "chat-openai-toolcall,chat",
            "chat-deepseek-reasoning,chat",
            "chat-openrouter-reasoning,chat",
            "chat-moonshot-cached,chat",
            "responses-reasoning-toolcall,responses",
            "responses-text,responses",
            "anthropic-thinking-toolcall,anthropic"})
    void streamDecodesToTheExpectedTurn(String name, String kind) {
        LlmProvider p = provider(kind);
        StreamAccumulator acc = new StreamAccumulator();
        for (JsonObject chunk : GoldenFiles.sseChunks("streams/" + name + ".sse")) {
            p.accumulateChunk(chunk, acc);
        }
        AssistantTurn turn = p.finalizeStream(acc);
        Usage usage = p.usage(acc.usage);
        JsonObject expected = GoldenFiles.object("streams/expected.json", name);

        assertEquals(expected.get("content").getAsString(), turn.content());
        assertEquals(expected.get("reasoning").getAsString(), turn.reasoning());
        assertEquals(expected.getAsJsonArray("toolCalls").size(), turn.toolCalls().size());
        for (int i = 0; i < turn.toolCalls().size(); i++) {
            JsonObject call = expected.getAsJsonArray("toolCalls").get(i).getAsJsonObject();
            assertEquals(call.get("id").getAsString(), turn.toolCalls().get(i).id());
            assertEquals(call.get("name").getAsString(), turn.toolCalls().get(i).name());
            assertEquals(call.get("arguments").getAsString(), turn.toolCalls().get(i).arguments());
        }
        assertEquals(expected.getAsJsonObject("extras"), turn.extras());
        assertEquals(expected.get("finish").getAsString(), acc.finishReason);
        JsonObject u = expected.getAsJsonObject("usage");
        assertEquals(new Usage(u.get("input").getAsLong(), u.get("output").getAsLong(),
                u.get("cacheRead").getAsLong(), u.get("cacheWrite").getAsLong()), usage);
    }
}
