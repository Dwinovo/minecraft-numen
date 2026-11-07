package com.dwinovo.numen.api.agent.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.List;

/**
 * One wire protocol (Chat Completions, Responses or Anthropic Messages). An
 * implementation does exactly two things: compile our neutral request — model,
 * system prompt, history, tools, options — into one HTTP request body, and decode
 * the streamed reply back into our neutral types ({@link AssistantTurn},
 * {@link LlmToolCall}, {@link Usage}).
 *
 * <p>What differs between vendors speaking the same protocol is not subclassed away:
 * an implementation is built from its protocol's {@link Compat} (see {@link #of}) and
 * receives the {@link ModelSpec} with every request. No implementation knows a vendor's name.
 *
 * <h2>What providers DON'T do</h2>
 * No HTTP I/O (that's {@link com.dwinovo.numen.api.agent.http.HttpLlmTransport}),
 * no convo state (that's {@code ConvoState}), no LLM-side loop control
 * (that's {@code AgentLoop}).
 */
public interface LlmProvider {

    /** The protocol implementation for a set of compat items. */
    static LlmProvider of(Compat compat) {
        return switch (compat) {
            case ChatCompat c -> new OpenAIProvider(c);
            case ResponsesCompat c -> new OpenAIResponsesProvider(c);
            case AnthropicCompat c -> new AnthropicProvider(c);
        };
    }

    /** Build the user-role wire message for {@code content}. */
    JsonObject buildUserMessage(String content);

    /** Build the tool-role wire message echoing {@code toolCallId}'s result. */
    JsonObject buildToolResultMessage(String toolCallId, String content);

    /**
     * Convert a stored {@link AssistantTurn} back into the wire-format
     * assistant message for the next request. Must re-inject {@code extras}
     * so backends with proprietary required-echo fields stay happy.
     */
    List<JsonObject> assistantToRequestItems(AssistantTurn turn);

    /** Build the {@code tools} array from the registered {@link IToolSpec}s. */
    JsonArray buildToolList(Collection<? extends IToolSpec> tools);

    /**
     * Assemble the full streaming request body for one call.
     *
     * @param model        the model and its capabilities
     * @param systemPrompt may be empty — provider should skip if so
     * @param messages     already-built wire-format message objects in order
     * @param tools        already-built wire-format tools array (may be empty)
     * @param options      the player's generation parameters; each is only sent when set and the model takes it
     * @param sessionId    stable id of the conversation this request continues (the companion's uuid), or null.
     *                     Only protocols whose server routes cache lookups by a client key use it (Responses'
     *                     {@code prompt_cache_key}); the others cache by prefix alone and ignore it
     */
    JsonObject buildRequestBody(ModelSpec model, String systemPrompt, List<JsonObject> messages,
                                JsonArray tools, RequestOptions options, String sessionId);

    /** Decode the response body into our internal {@link AssistantTurn}. */
    AssistantTurn parseResponseBody(JsonObject body);

    /**
     * 鉴权头方言。缺省是 OpenAI 系的 {@code Authorization: Bearer};别的协议
     * 覆写(如 {@code x-api-key} + 版本头)。per-endpoint 常量,传输层构造时取用。
     */
    default java.util.Map<String, String> authHeaders(String apiKey) {
        return java.util.Map.of("Authorization", "Bearer " + (apiKey == null ? "" : apiKey));
    }

    /**
     * 聊天端点在基址之后的路径段。URL 组合规则:基址若已以本路径结尾则不重复
     * 追加,详见 {@code NumenLlmClient} 的组合逻辑。
     */
    default String chatPath() {
        return "/chat/completions";
    }

    // ---- usage accounting ----

    /**
     * usage 帧 → 四元用量。缺省按 OpenAI 形读:{@code prompt_tokens} 是全量提示词,
     * 缓存量在 {@code prompt_tokens_details} 里,余下即新处理的输入。Chat 协议上几家的缓存字段
     * 在这里都认一遍:DeepSeek 直接报命中与未命中({@code prompt_cache_hit_tokens} /
     * {@code prompt_cache_miss_tokens},不报缓存写),Moonshot 报在顶层 {@code cached_tokens},
     * OpenRouter 的缓存写叫 {@code cache_write_tokens}。Responses、Anthropic 字段不同,各自覆写本方法。
     *
     * <p>"这一轮真正新处理了多少"是 {@link Usage#fresh()},从四元推导,不需要每家
     * 各写一遍减法。
     */
    default Usage usage(JsonObject usage) {
        if (usage != null && usage.has("prompt_cache_miss_tokens")) {
            return new Usage(
                    usageInt(usage, "prompt_cache_miss_tokens"),
                    usageInt(usage, "completion_tokens"),
                    usageInt(usage, "prompt_cache_hit_tokens"),
                    0);
        }
        long prompt = usageInt(usage, "prompt_tokens");
        long output = usageInt(usage, "completion_tokens");
        long cacheRead = usageInt(usage, "cached_tokens");
        long cacheWrite = 0;
        if (usage != null && usage.has("prompt_tokens_details")
                && usage.get("prompt_tokens_details").isJsonObject()) {
            JsonObject d = usage.getAsJsonObject("prompt_tokens_details");
            cacheRead = Math.max(cacheRead, usageInt(d, "cached_tokens"));
            cacheWrite = usageInt(d, "cache_write_tokens");
        }
        long input = Math.max(0, prompt - cacheRead - cacheWrite);
        return new Usage(input, output, cacheRead, cacheWrite);
    }

    /** usage 帧安全取整(帧缺失/字段缺失 → 0)。 */
    static int usageInt(JsonObject usage, String key) {
        return usage != null && usage.has(key) && usage.get(key).isJsonPrimitive()
                ? usage.get(key).getAsInt() : 0;
    }

    // ---- streaming ----

    /**
     * Whether this provider supports SSE streaming. Default true for the
     * OpenAI-compat family; backends that only support buffered responses
     * (rare) can override to false.
     */
    default boolean supportsStreaming() { return true; }

    /**
     * Apply one SSE chunk's JSON to the running {@link StreamAccumulator}.
     * Called from the HTTP layer's chunk handler in order. Implementations
     * must be tolerant of missing fields — backends send sparse deltas.
     */
    void accumulateChunk(JsonObject chunk, StreamAccumulator acc);

    /**
     * Convert a fully-accumulated streaming response into our internal
     * {@link AssistantTurn}. Called once after the stream terminates
     * normally.
     */
    AssistantTurn finalizeStream(StreamAccumulator acc);
}
