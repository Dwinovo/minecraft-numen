package com.dwinovo.numen.agent.provider;

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

    // ---- 思考开关的方言家族(站点数据 thinkingFormat 字段的取值域) ----
    //
    // 按"开关长什么样"命名而非按厂商命名:同形方言共用一个 id,新站点只是
    // 数据里选一个既有形态,不新增代码。

    /** 顶层 {@code reasoning_effort: "low"|"medium"|"high"}(默认形态)。 */
    String THINKING_EFFORT = "effort";
    /** 嵌套 {@code reasoning: {effort: ...}}(聚合网关常用)。 */
    String THINKING_EFFORT_NESTED = "effort-nested";
    /** 对象开关 {@code thinking: {type: "enabled"|"disabled"}}。 */
    String THINKING_TYPE = "thinking-type";
    /** 布尔开关 {@code enable_thinking: true|false}。 */
    String THINKING_ENABLE_BOOL = "enable-bool";
    /** token 预算型 {@code thinking: {type:"enabled", budget_tokens:N}}(力度映射为预算)。 */
    String THINKING_BUDGET = "budget";
    /** 无开关:思考型号常开/不可控,任何力度都不发参数。 */
    String THINKING_NONE = "none";

    /** Stable id used in config (e.g. {@code "openai"}, {@code "deepseek"}). */
    String name();

    /**
     * Default API base URL for this provider, used when the user leaves
     * {@code config.baseUrl} empty. Includes the version / mode prefix
     * ({@code /v1}, {@code /beta}, {@code /compatible-mode/v1},
     * {@code /api/v3}, ...) but **excludes** the trailing
     * {@code /chat/completions} path — that's appended by
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

    /** Build the {@code tools} array from the registered {@link IToolSpec}s. */
    JsonArray buildToolList(Collection<? extends IToolSpec> tools);

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
     * object) can override this to translate — see the {@code THINKING_*}
     * dialect ids and {@code OpenAIProvider}'s data-driven translation.
     *
     * @param body   the request body to mutate in place
     * @param effort {@code "low"}/{@code "medium"}/{@code "high"},或
     *               {@code "off"}(明确关闭思考——只有带开关的方言能表达,
     *               effort 形态的方言对 off 不发参数)
     */
    default void applyReasoning(JsonObject body, String effort) {
        if ("off".equals(effort)) return;
        body.addProperty("reasoning_effort", effort);
    }

    /**
     * 玩家配置的生成参数上盘。两大协议恰好同名({@code temperature} /
     * {@code max_tokens}),缺省实现两家通吃;null/0 = 不发,吃服务器或协议
     * 默认——参数只在玩家明确设置时才出现在线上(未知参数会被部分后端 400,
     * 与思考开关同一条 opt-in 纪律)。调用时序在 {@code applyReasoning} 之前,
     * 思考预算类方言可以在其上加码。
     */
    default void applyGenerationParams(JsonObject body, Double temperature, int maxTokens) {
        if (temperature != null) body.addProperty("temperature", temperature);
        if (maxTokens > 0) body.addProperty("max_tokens", maxTokens);
    }

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

    /**
     * 从一个 SSE chunk 里抽出思考文本增量(方言字段解码),没有则 null。
     * 供 UI 的实时思考流显示;累积后的权威去重版本在
     * {@link AssistantTurn#reasoning}(同流双字段的场合以累积侧的
     * 字段锁定为准)。
     */
    default String extractReasoningDelta(JsonObject chunk) {
        return null;
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
     * 在请求体上开启流式。缺省是 OpenAI 方言({@code stream:true} +
     * {@code stream_options.include_usage});别的协议覆写成自己的形状——
     * 对严格校验未知字段的后端,多发一个别家的字段就是 400。
     */
    default void applyStreaming(JsonObject body) {
        body.addProperty("stream", true);
        JsonObject streamOpts = new JsonObject();
        streamOpts.addProperty("include_usage", true);
        body.add("stream_options", streamOpts);
    }

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
