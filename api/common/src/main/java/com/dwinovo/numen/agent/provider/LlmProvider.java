package com.dwinovo.numen.agent.provider;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.List;

/**
 * Wire-format adapter for one LLM backend family.
 *
 * <h2>Why this interface exists</h2>
 * Most "OpenAI-compatible" backends are 95% compatible and 5% proprietary
 * extensions — DeepSeek adds {@code reasoning_content}, Azure renames
 * a few fields, Anthropic via OpenAI-compat proxy doesn't quite return the
 * same shape. Centralising the wire-format translation here keeps the rest
 * of the agent layer (ConvoState, AgentLoop, ToolAdapter) provider-agnostic
 * — they speak our internal types ({@link AssistantTurn}, {@link LlmToolCall})
 * and the provider does the JSON in/out.
 *
 * <p>For genuinely non-OpenAI protocols (Anthropic Messages API, Gemini)
 * you'd implement this interface from scratch with a different request /
 * response shape. The interface deliberately avoids assuming OpenAI fields
 * leak through to the caller.
 *
 * <h2>What providers DO</h2>
 * Build wire messages, build request bodies, parse response bodies, and
 * handle the round-trip of non-standard fields ({@link AssistantTurn#extras}).
 *
 * <h2>What providers DON'T do</h2>
 * No HTTP I/O (that's {@link com.dwinovo.numen.agent.http.HttpLlmTransport}),
 * no convo state (that's {@code ConvoState}), no LLM-side loop control
 * (that's {@code EntityAgentLoop}).
 */
public interface LlmProvider {

    /** Stable id used in config (e.g. {@code "openai"}, {@code "deepseek"}). */
    String name();

    /**
     * Default API base URL for this provider, used when the user leaves
     * {@code config.baseUrl} empty. Matches LiteLLM's
     * {@code _get_openai_compatible_provider_info} return value: includes the
     * version / mode prefix ({@code /v1}, {@code /beta},
     * {@code /compatible-mode/v1}, {@code /api/v3}, ...) but **excludes**
     * the trailing {@code /chat/completions} path — that's appended by
     * {@code NumenLlmClient} during URL composition.
     *
     * <p>Examples per provider:
     * <ul>
     *   <li>OpenAI → {@code https://api.openai.com/v1}</li>
     *   <li>DeepSeek → {@code https://api.deepseek.com/beta}</li>
     *   <li>Moonshot (Kimi) → {@code https://api.moonshot.ai/v1}</li>
     *   <li>MiniMax → {@code https://api.minimax.io/v1}</li>
     *   <li>Volcengine (Doubao) → {@code https://ark.cn-beijing.volces.com/api/v3}</li>
     *   <li>DashScope (Qwen) → {@code https://dashscope.aliyuncs.com/compatible-mode/v1}</li>
     * </ul>
     */
    String defaultBaseUrl();

    /** Build the user-role wire message for {@code content}. */
    JsonObject buildUserMessage(String content);

    /** Build the system-role wire message for {@code content}. */
    JsonObject buildSystemMessage(String content);

    /** Build the tool-role wire message echoing {@code toolCallId}'s result. */
    JsonObject buildToolResultMessage(String toolCallId, String content);

    /**
     * Convert a stored {@link AssistantTurn} back into the wire-format
     * assistant message for the next request. Must re-inject {@code extras}
     * so backends with proprietary required-echo fields stay happy.
     */
    JsonObject assistantToRequestMessage(AssistantTurn turn);

    /** Build the {@code tools} array from our internal {@link NumenTool} registry. */
    JsonArray buildToolList(Collection<NumenTool> tools);

    /**
     * Assemble the full request body for one chat completion.
     *
     * @param model         model id string ({@code "deepseek-chat"}, {@code "gpt-4o"}, ...)
     * @param systemPrompt  may be empty — provider should skip if so
     * @param messages      already-built wire-format message objects in order
     * @param tools         already-built wire-format tools array (may be empty)
     */
    JsonObject buildRequestBody(String model, String systemPrompt,
                                List<JsonObject> messages, JsonArray tools);

    /** Decode the response body into our internal {@link AssistantTurn}. */
    AssistantTurn parseResponseBody(JsonObject body);

    /**
     * Apply the user's reasoning / "deep thinking" preference to an already-built
     * request body. Only called when the user picked a concrete effort — the
     * client skips this call for the {@code "auto"} default, so a body is never
     * touched unless the user opted in (protecting non-reasoning models that
     * would 400 on an unexpected parameter).
     *
     * <p>Default maps to the OpenAI-dialect {@code reasoning_effort} field
     * ({@code low}/{@code medium}/{@code high}), which OpenAI's o-series / GPT-5
     * and a growing set of OpenAI-compatible backends honour. Providers whose
     * backend uses a different knob (e.g. a {@code thinking} / {@code enable_thinking}
     * object) can override this to translate.
     *
     * @param body   the request body to mutate in place
     * @param effort one of {@code "low"}, {@code "medium"}, {@code "high"}
     */
    default void applyReasoning(JsonObject body, String effort) {
        body.addProperty("reasoning_effort", effort);
    }

    // ---- usage accounting ----

    /**
     * 本次请求真正新处理的 token:缓存命中的输入不计,只算未命中的输入加输出。
     * 缓存正常工作时这个数很小,暴涨说明缓存前缀碎了。缓存字段是各家方言,
     * 由实现自理;默认全量 total——没有缓存机制(或方言未知)的服务商,所有
     * 输入都算新处理。
     */
    default long freshTokens(JsonObject usage) {
        return usageInt(usage, "total_tokens");
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
