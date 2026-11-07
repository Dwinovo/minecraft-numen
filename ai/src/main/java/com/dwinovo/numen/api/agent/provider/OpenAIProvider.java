package com.dwinovo.numen.api.agent.provider;

import com.dwinovo.numen.api.agent.http.ProviderReply;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Chat Completions 线协议(POST /v1/chat/completions)的实现,OpenAI 官方与绝大多数
 * OpenAI 兼容端点共用。厂家之间的差异不分子类,由 {@link ChatCompat} 承载:思考参数怎么写、
 * 输出上限的字段名、是否要回传 {@code reasoning_content}、流式带不带用量。
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
public final class OpenAIProvider implements LlmProvider {

    /** 带工具调用的助手消息缺 {@code reasoning_content} 时补的值:API 接受的最小非空串。 */
    private static final String EMPTY_REASONING = " ";

    private static final Gson GSON = new Gson();

    private final ChatCompat compat;

    public OpenAIProvider(ChatCompat compat) {
        this.compat = compat;
    }

    /**
     * 思考参数:这一档发不发、带什么,已经由 {@link ThinkingPlan} 定好(玩家选得到的档位都真会写进请求);
     * 这里只管按兼容项的 thinkingFormat 把它写成这个端点认的字段。
     */
    private void applyThinking(JsonObject body, ThinkingPlan.Wire wire) {
        if (wire.kind() == ThinkingPlan.Wire.Kind.AUTO) return;
        boolean off = wire.kind() == ThinkingPlan.Wire.Kind.OFF;
        switch (compat.thinkingFormat()) {
            case NONE -> { }
            case ZAI -> body.add("thinking", thinkingType(!off));
            case DEEPSEEK -> {
                body.add("thinking", thinkingType(!off));
                if (wire.kind() == ThinkingPlan.Wire.Kind.EFFORT) body.addProperty("reasoning_effort", wire.effort());
            }
            case QWEN -> body.addProperty("enable_thinking", !off);
            case OPENROUTER -> {
                if (wire.effort() == null) return;
                JsonObject r = new JsonObject();
                r.addProperty("effort", wire.effort());
                body.add("reasoning", r);
            }
            case OPENAI -> {
                if (wire.effort() != null) body.addProperty("reasoning_effort", wire.effort());
            }
        }
    }

    private static JsonObject thinkingType(boolean enabled) {
        JsonObject t = new JsonObject();
        t.addProperty("type", enabled ? "enabled" : "disabled");
        return t;
    }

    @Override
    public JsonObject buildUserMessage(String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        m.addProperty("content", content == null ? "" : content);
        return m;
    }

    private static JsonObject buildSystemMessage(String content) {
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
    public List<JsonObject> assistantToRequestItems(AssistantTurn turn) {
        return List.of(assistantMessage(turn));
    }

    /** chat/completions 家族里,一条助手回合就是一条消息。方言差异覆写这里。 */
    private JsonObject assistantMessage(AssistantTurn turn) {
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
        if (compat.replayReasoningContent() && m.has("tool_calls") && !m.has("reasoning_content")) {
            m.addProperty("reasoning_content", EMPTY_REASONING);
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
    public JsonObject buildRequestBody(ModelSpec model, String systemPrompt, List<JsonObject> messages,
                                       JsonArray tools, RequestOptions options, String sessionId) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model.id());

        JsonArray msgs = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgs.add(buildSystemMessage(systemPrompt));
        }
        for (JsonObject m : messages) msgs.add(m);
        body.add("messages", compat.anthropicCacheControl() ? ChatCacheControl.markMessages(msgs) : msgs);

        if (tools != null && !tools.isEmpty()) {
            body.add("tools", compat.anthropicCacheControl() ? ChatCacheControl.markLastTool(tools) : tools);
            body.addProperty("parallel_tool_calls", true);
        }

        Double temperature = options.temperatureFor(model);
        if (temperature != null) body.addProperty("temperature", temperature);
        if (options.maxTokens() > 0) body.addProperty(compat.maxTokensField().wire(), options.maxTokens());
        applyThinking(body, ThinkingPlan.of(model.thinking(), compat).wireFor(options.thinking()));

        body.addProperty("stream", true);
        if (compat.streamUsage()) {
            JsonObject streamOpts = new JsonObject();
            streamOpts.addProperty("include_usage", true);
            body.add("stream_options", streamOpts);
        }
        return body;
    }

    @Override
    public AssistantTurn parseResponseBody(JsonObject body) {
        JsonObject msg = extractMessage(body);
        StringBuilder content = new StringBuilder();
        StringBuilder thought = new StringBuilder();
        ThinkTagSplitter tags = new ThinkTagSplitter();
        tags.feed(stringOrEmpty(msg.get("content")), content, thought);
        tags.flush(content, thought);
        List<LlmToolCall> toolCalls = parseToolCalls(msg);
        JsonObject extras = extractExtras(msg);
        String reasoning = "";
        for (String f : REASONING_FIELDS) {
            String v = stringField(msg, f);
            if (v != null && !v.isEmpty()) { reasoning = v; break; }
        }
        return new AssistantTurn(content.toString(), toolCalls, extras, reasoning + thought);
    }

    /** Pull {@code choices[0].message} out of the response body. */
    private JsonObject extractMessage(JsonObject body) {
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
    private List<LlmToolCall> parseToolCalls(JsonObject msg) {
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
     * (DeepSeek's {@code reasoning_content}, future
     * vendors' whatever they invent) need no vendor-specific code to keep
     * their extensions intact across turns.
     *
     * <p>Pure OpenAI responses contain only the standard fields, so this
     * captures nothing and the extras bag stays empty — no behaviour change
     * for standard OpenAI.
     */
    private JsonObject extractExtras(JsonObject msg) {
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

        // 流中途的错误帧({"error":{...}}):厂家在 200 之后才出的错,和 HTTP 错误体同一批写法
        if (chunk.has("error") && !chunk.get("error").isJsonNull()) {
            String reply = ProviderReply.of(chunk);
            acc.failure = reply != null ? reply : chunk.get("error").toString();
            return;
        }
        if (!chunk.has("choices")) return;
        JsonArray choices = chunk.getAsJsonArray("choices");
        if (choices.isEmpty()) return;
        JsonElement first = choices.get(0);
        if (!first.isJsonObject()) return;
        JsonObject choice = first.getAsJsonObject();

        // 有的厂家(Moonshot)把用量放在 choice 里而不是顶层
        if (!chunk.has("usage") && choice.has("usage") && choice.get("usage").isJsonObject()) {
            acc.usage = choice.getAsJsonObject("usage");
        }

        // finish_reason 是本协议的收尾;正常收尾之外的取值(content_filter、network_error、没见过的)算流失败
        if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
            String finish = choice.get("finish_reason").getAsString();
            acc.finishReason = finish;
            acc.ended = true;
            if (!NORMAL_FINISH.contains(finish)) {
                acc.failure = "Provider finish_reason: " + finish;
            }
        }

        // delta — body of the chunk's payload
        if (!choice.has("delta") || !choice.get("delta").isJsonObject()) return;
        JsonObject delta = choice.getAsJsonObject("delta");

        // content fragment
        if (delta.has("content") && delta.get("content").isJsonPrimitive()) {
            acc.appendContent(delta.get("content").getAsString());
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
    private void captureChunkExtras(JsonObject delta, StreamAccumulator acc) {
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

    private static final java.util.Set<String> NORMAL_FINISH =
            java.util.Set.of("stop", "end", "length", "tool_calls", "function_call");

    /** 思考方言字段,按优先序;首个非空者在流内锁定(有的后端双字段同文,防重复计入)。 */
    private static final List<String> REASONING_FIELDS =
            List.of("reasoning_content", "reasoning", "reasoning_text");

    /** 从 delta 抽思考增量;负责字段锁定语义。无思考返回 null。 */
    private String reasoningDelta(JsonObject delta, StreamAccumulator acc) {
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
        acc.requireEnded();
        acc.endContent();
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
