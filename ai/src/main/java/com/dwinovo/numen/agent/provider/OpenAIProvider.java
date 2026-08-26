package com.dwinovo.numen.agent.provider;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reference implementation of the OpenAI chat completions wire protocol.
 * Used directly for OpenAI itself and inherited from by lightly-extending
 * subclasses ({@link DeepSeekProvider}) that only need to override
 * response parsing / assistant-message reconstruction.
 *
 * <h2>Schema reference</h2>
 * Targets the {@code POST /v1/chat/completions} shape from OpenAI's API
 * docs as of GPT-4o / GPT-4.1 / GPT-5 family (the same shape DeepSeek,
 * Together, Groq, Mistral La Plateforme, Moonshot, etc. all conform to).
 *
 * <h2>Tool-call argument string vs object</h2>
 * The OpenAI spec ships tool_call.arguments as a JSON-string-typed string
 * (i.e. the JSON object encoded as a string). We preserve that on both
 * read ({@link AssistantTurn#toolCalls} field is also string) and write
 * (re-emit verbatim). Backends differ here and we trust the LLM's own
 * output verbatim.
 */
public class OpenAIProvider implements LlmProvider {

    public static final String NAME = "openai";
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private static final Gson GSON = new Gson();

    private final String name;
    private final String defaultBaseUrl;
    private final String thinkingFormat;

    public OpenAIProvider() {
        this(NAME, DEFAULT_BASE_URL);
    }

    public OpenAIProvider(String name, String defaultBaseUrl) {
        this(name, defaultBaseUrl, LlmProvider.THINKING_EFFORT);
    }

    /**
     * 纯 OpenAI 兼容后端只差站点名、缺省端点与思考开关方言——用这个构造
     * 按站点参数化,不必再为每个站点新建子类。三者的真源是
     * {@code numen_providers.json}({@code ProviderRegistry});只有真有行为差异
     * 的后端(DeepSeek 的缓存计费、Moonshot 的 reasoning_content 兜底)
     * 才配一个子类。
     */
    public OpenAIProvider(String name, String defaultBaseUrl, String thinkingFormat) {
        this.name = name;
        this.defaultBaseUrl = defaultBaseUrl;
        this.thinkingFormat = thinkingFormat == null || thinkingFormat.isBlank()
                ? LlmProvider.THINKING_EFFORT : thinkingFormat;
    }

    @Override public String name() { return name; }
    @Override public String defaultBaseUrl() { return defaultBaseUrl; }

    /** 本站思考开关的方言家族(见 {@code LlmProvider.THINKING_*})。 */
    public String thinkingFormat() { return thinkingFormat; }

    /**
     * 力度 → 本站方言的翻译。{@code off} 只有带开关的方言能表达;effort
     * 形态对 off 静默(没有"关"的线格式)。{@code none} 家族永不发参数——
     * 思考型号常开/不可控的站点,多发只会 400 或被无视。
     */
    @Override
    public void applyReasoning(JsonObject body, String effort) {
        boolean off = "off".equals(effort);
        switch (thinkingFormat) {
            case LlmProvider.THINKING_NONE -> { }
            case LlmProvider.THINKING_TYPE -> {
                JsonObject t = new JsonObject();
                t.addProperty("type", off ? "disabled" : "enabled");
                body.add("thinking", t);
            }
            case LlmProvider.THINKING_ENABLE_BOOL -> body.addProperty("enable_thinking", !off);
            case LlmProvider.THINKING_EFFORT_NESTED -> {
                if (off) return;
                JsonObject r = new JsonObject();
                r.addProperty("effort", effort);
                body.add("reasoning", r);
            }
            default -> {
                if (off) return;
                body.addProperty("reasoning_effort", effort);
            }
        }
    }

    @Override
    public JsonObject buildUserMessage(String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        m.addProperty("content", content == null ? "" : content);
        return m;
    }

    @Override
    public JsonObject buildSystemMessage(String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "system");
        m.addProperty("content", content == null ? "" : content);
        return m;
    }

    @Override
    public JsonObject buildToolResultMessage(String toolCallId, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "tool");
        m.addProperty("tool_call_id", toolCallId);
        m.addProperty("content", content == null ? "" : content);
        return m;
    }

    @Override
    public JsonObject assistantToRequestMessage(AssistantTurn turn) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "assistant");
        // Content can be null in OpenAI assistant turns when there are only
        // tool calls. We emit empty string instead — universally accepted.
        m.addProperty("content", turn.content());
        if (turn.hasToolCalls()) {
            JsonArray tcArr = new JsonArray();
            for (LlmToolCall tc : turn.toolCalls()) {
                tcArr.add(tc.toOpenAIJson());
            }
            m.add("tool_calls", tcArr);
        }
        // Re-inject any provider-specific extras (reasoning_content, ...).
        // The extras live at the message level (sibling to role/content)
        // so we copy each top-level entry onto m.
        for (var e : turn.extras().entrySet()) {
            m.add(e.getKey(), e.getValue());
        }
        return m;
    }

    @Override
    public JsonArray buildToolList(Collection<? extends IToolSpec> tools) {
        JsonArray arr = new JsonArray();
        for (IToolSpec t : tools) {
            JsonObject fn = new JsonObject();
            fn.addProperty("name", t.name());
            fn.addProperty("description", t.description());
            fn.add("parameters", mapToJson(t.parameterSchema()));
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "function");
            wrapper.add("function", fn);
            arr.add(wrapper);
        }
        return arr;
    }

    @Override
    public JsonObject buildRequestBody(String model, String systemPrompt,
                                        List<JsonObject> messages, JsonArray tools) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);

        JsonArray msgs = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgs.add(buildSystemMessage(systemPrompt));
        }
        for (JsonObject m : messages) msgs.add(m);
        body.add("messages", msgs);

        if (tools != null && !tools.isEmpty()) {
            body.add("tools", tools);
            body.addProperty("parallel_tool_calls", true);
        }
        return body;
    }

    @Override
    public AssistantTurn parseResponseBody(JsonObject body) {
        JsonObject msg = extractMessage(body);
        String content = stringOrEmpty(msg.get("content"));
        List<LlmToolCall> toolCalls = parseToolCalls(msg);
        JsonObject extras = extractExtras(msg);
        String reasoning = "";
        for (String f : REASONING_FIELDS) {
            String v = stringField(msg, f);
            if (v != null && !v.isEmpty()) { reasoning = v; break; }
        }
        return new AssistantTurn(content, toolCalls, extras, reasoning);
    }

    /** Pull {@code choices[0].message} out of the response body. */
    protected JsonObject extractMessage(JsonObject body) {
        if (!body.has("choices")) {
            throw new IllegalArgumentException("response has no 'choices' field: " + body);
        }
        JsonArray choices = body.getAsJsonArray("choices");
        if (choices.isEmpty()) {
            throw new IllegalArgumentException("response 'choices' is empty: " + body);
        }
        JsonElement first = choices.get(0);
        if (!first.isJsonObject() || !first.getAsJsonObject().has("message")) {
            throw new IllegalArgumentException("response choices[0] has no 'message': " + body);
        }
        return first.getAsJsonObject().getAsJsonObject("message");
    }

    /** Pull and decode tool_calls from an assistant message. */
    protected List<LlmToolCall> parseToolCalls(JsonObject msg) {
        if (!msg.has("tool_calls") || !msg.get("tool_calls").isJsonArray()) {
            return List.of();
        }
        JsonArray arr = msg.getAsJsonArray("tool_calls");
        List<LlmToolCall> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (el.isJsonObject()) {
                out.add(LlmToolCall.fromOpenAIJson(el.getAsJsonObject()));
            }
        }
        return out;
    }

    /**
     * Capture every non-standard top-level field on the assistant message
     * into the extras bag, so it round-trips back on the next request.
     *
     * <p>Every unknown field on a response gets preserved automatically. With
     * this baseline behaviour, OpenAI-compatible backend variants
     * ({@link DeepSeekProvider} for {@code reasoning_content}, future
     * providers for whatever they invent) don't need to override anything
     * to keep their extensions intact across turns.
     *
     * <p>Pure OpenAI responses contain only the standard fields, so this
     * captures nothing and the extras bag stays empty — no behaviour change
     * for standard OpenAI.
     */
    protected JsonObject extractExtras(JsonObject msg) {
        JsonObject extras = new JsonObject();
        for (var e : msg.entrySet()) {
            if (!STANDARD_MESSAGE_FIELDS.contains(e.getKey())) {
                extras.add(e.getKey(), e.getValue());
            }
        }
        return extras;
    }

    /** Standard fields on an OpenAI assistant response message. */
    private static final java.util.Set<String> STANDARD_MESSAGE_FIELDS = java.util.Set.of(
            "role", "content", "tool_calls", "refusal", "audio", "function_call");

    // ---- streaming ----

    /** Standard fields inside an OpenAI streaming {@code delta} object. */
    private static final java.util.Set<String> STANDARD_DELTA_FIELDS = java.util.Set.of(
            "role", "content", "tool_calls", "refusal");


    @Override
    public void accumulateChunk(JsonObject chunk, StreamAccumulator acc) {
        acc.chunkCount++;

        // Usage typically arrives in the FINAL chunk (when stream_options.include_usage:true).
        if (chunk.has("usage") && chunk.get("usage").isJsonObject()) {
            acc.usage = chunk.getAsJsonObject("usage");
        }

        if (!chunk.has("choices")) return;
        JsonArray choices = chunk.getAsJsonArray("choices");
        if (choices.isEmpty()) return;
        JsonElement first = choices.get(0);
        if (!first.isJsonObject()) return;
        JsonObject choice = first.getAsJsonObject();

        // finish_reason
        if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
            acc.finishReason = choice.get("finish_reason").getAsString();
        }

        // delta — body of the chunk's payload
        if (!choice.has("delta") || !choice.get("delta").isJsonObject()) return;
        JsonObject delta = choice.getAsJsonObject("delta");

        // content fragment
        if (delta.has("content") && delta.get("content").isJsonPrimitive()) {
            acc.content.append(delta.get("content").getAsString());
        }

        // 思考文本增量(方言字段,首个非空者锁定)——展示面累积;
        // 同一字段照旧被 captureChunkExtras 收进 extras 走回传,两线并行。
        String reasoningDelta = reasoningDelta(delta, acc);
        if (reasoningDelta != null) {
            acc.reasoning.append(reasoningDelta);
        }

        // tool_calls fragments (sparse — each delta only carries what changed)
        if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
            for (JsonElement el : delta.getAsJsonArray("tool_calls")) {
                if (!el.isJsonObject()) continue;
                accumulateToolCallDelta(el.getAsJsonObject(), acc);
            }
        }

        // Subclass hook for backend-specific non-standard fields (reasoning_content, etc.)
        captureChunkExtras(delta, acc);
    }

    /**
     * Streaming counterpart to {@link #extractExtras}: capture every
     * non-standard string-typed field on the chunk's {@code delta} into
     * the accumulator's extras buffers, so the finalised
     * {@link AssistantTurn} round-trips them on the next request.
     *
     * <p>Aligned with the framework-level non-standard-field preservation
     * we put in {@link #extractExtras}. Same rationale: pure OpenAI
     * streams contain only standard fields, so this is a no-op for OpenAI
     * itself; OpenAI-compat backends get their extensions
     * (e.g. {@code reasoning_content}) preserved automatically.
     */
    protected void captureChunkExtras(JsonObject delta, StreamAccumulator acc) {
        for (var e : delta.entrySet()) {
            if (STANDARD_DELTA_FIELDS.contains(e.getKey())) continue;
            var el = e.getValue();
            if (el == null || el.isJsonNull()) continue;
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                acc.appendExtra(e.getKey(), el.getAsString());
            } else {
                // 非字符串型(如聚合网关的 reasoning_details 数组)也要保留:
                // 数组逐块并入,其他末值胜。否则回传残缺,方言后端可能拒收。
                acc.mergeExtraJson(e.getKey(), el);
            }
        }
    }

    /** 思考方言字段,按优先序;首个非空者在流内锁定(有的后端双字段同文,防重复计入)。 */
    private static final List<String> REASONING_FIELDS =
            List.of("reasoning_content", "reasoning", "reasoning_text");

    /** 从 delta 抽思考增量;负责字段锁定语义。无思考返回 null。 */
    protected String reasoningDelta(JsonObject delta, StreamAccumulator acc) {
        if (acc.reasoningField != null) {
            return stringField(delta, acc.reasoningField);
        }
        for (String f : REASONING_FIELDS) {
            String v = stringField(delta, f);
            if (v != null && !v.isEmpty()) {
                acc.reasoningField = f;
                return v;
            }
        }
        return null;
    }

    private static String stringField(JsonObject o, String key) {
        var el = o.get(key);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()
                ? el.getAsString() : null;
    }

    /**
     * 无状态版思考增量抽取(供 UI 实时流)。没有跨 chunk 的字段锁定,同流
     * 双字段的极端场合可能重复;权威去重版本是累积后的
     * {@link AssistantTurn#reasoning}。
     */
    @Override
    public String extractReasoningDelta(JsonObject chunk) {
        if (!chunk.has("choices") || !chunk.get("choices").isJsonArray()) return null;
        JsonArray choices = chunk.getAsJsonArray("choices");
        if (choices.isEmpty() || !choices.get(0).isJsonObject()) return null;
        JsonObject choice = choices.get(0).getAsJsonObject();
        if (!choice.has("delta") || !choice.get("delta").isJsonObject()) return null;
        JsonObject delta = choice.getAsJsonObject("delta");
        for (String f : REASONING_FIELDS) {
            String v = stringField(delta, f);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /** Set of delta fields we treat as "standard" — subclasses use this for partition. */
    protected static java.util.Set<String> standardDeltaFields() {
        return STANDARD_DELTA_FIELDS;
    }

    private static void accumulateToolCallDelta(JsonObject tc, StreamAccumulator acc) {
        int index = tc.has("index") && tc.get("index").isJsonPrimitive()
                ? tc.get("index").getAsInt() : 0;
        StreamAccumulator.ToolCallBuilder b = acc.toolCallAt(index);

        if (tc.has("id") && tc.get("id").isJsonPrimitive() && b.id == null) {
            b.id = tc.get("id").getAsString();
        }
        if (tc.has("function") && tc.get("function").isJsonObject()) {
            JsonObject fn = tc.getAsJsonObject("function");
            if (fn.has("name") && fn.get("name").isJsonPrimitive() && b.name == null) {
                b.name = fn.get("name").getAsString();
            }
            if (fn.has("arguments") && fn.get("arguments").isJsonPrimitive()) {
                b.arguments.append(fn.get("arguments").getAsString());
            }
        }
    }

    @Override
    public AssistantTurn finalizeStream(StreamAccumulator acc) {
        List<LlmToolCall> calls = new ArrayList<>(acc.toolCalls.size());
        for (StreamAccumulator.ToolCallBuilder b : acc.toolCalls.values()) {
            if (b.id == null && b.name == null && b.arguments.length() == 0) continue;
            String id = b.id == null ? "" : b.id;
            String name = b.name == null ? "" : b.name;
            calls.add(new LlmToolCall(id, name, b.arguments.toString()));
        }

        JsonObject extras = new JsonObject();
        for (var e : acc.extraBuffers.entrySet()) {
            extras.addProperty(e.getKey(), e.getValue().toString());
        }
        for (var e : acc.extraJson.entrySet()) {
            if (!extras.has(e.getKey())) extras.add(e.getKey(), e.getValue());
        }

        return new AssistantTurn(acc.content.toString(), calls, extras,
                acc.reasoning.toString());
    }

    private static String stringOrEmpty(JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString();
        return el.toString();
    }

    private static JsonElement mapToJson(Object value) {
        return GSON.toJsonTree(value);
    }
}
