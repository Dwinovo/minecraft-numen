package com.dwinovo.numen.agent.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages 线协议的完整实现——与 {@link OpenAIProvider} 同级的
 * 第二协议。凡是暴露 Anthropic 兼容端点的站点(官方与兼容站)都由本类承载,
 * 站点差异照旧走数据({@code numen_models.json} 的 baseUrl/headers/
 * thinkingFormat),不为兼容站开子类。
 *
 * <h2>与 OpenAI 方言的关键分歧(逐条落在对应方法上)</h2>
 * <ul>
 *   <li><b>鉴权:</b>{@code x-api-key} + {@code anthropic-version} 头,不是
 *       {@code Authorization: Bearer} → {@link #authHeaders}</li>
 *   <li><b>端点:</b>{@code /messages},不是 {@code /chat/completions}
 *       → {@link #chatPath}</li>
 *   <li><b>system:</b>顶层参数,消息数组里没有 system 角色
 *       → {@link #buildRequestBody}</li>
 *   <li><b>角色严格交替:</b>连续同角色消息必须并成一条(多块 content)——
 *       并行工具结果就是多个 user 块 → {@link #buildRequestBody} 的归并</li>
 *   <li><b>工具:</b>{@code input_schema} 平铺,无 function 包装;调用参数是
 *       JSON 对象不是字符串 → {@link #buildToolList} / {@link #assistantToRequestMessage}</li>
 *   <li><b>思考:</b>content block 形态({@code thinking} 块 + 签名),多轮
 *       必须带签名回传 → 签名存 extras 的 {@value #SIGNATURE_KEY},
 *       {@link #assistantToRequestMessage} 重建思考块</li>
 *   <li><b>流式:</b>事件按 {@code type} 分派(块级 start/delta/stop),
 *       usage 分两截(message_start 进/message_delta 出),累积时归一成
 *       OpenAI 形字段名,client 与记账下游无感 → {@link #accumulateChunk}</li>
 *   <li><b>{@code max_tokens} 必填;</b>未知顶层字段会被严格拒收(所以
 *       {@link #applyStreaming} 只发 {@code stream:true})</li>
 * </ul>
 */
public class AnthropicProvider implements LlmProvider {

    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1";
    /** 思考块签名在 extras 里的落位(回传重建思考块时取用)。 */
    public static final String SIGNATURE_KEY = "anthropic_thinking_signature";

    /** 无思考时的输出上限;开思考后在此之上再加预算(预算必须小于 max_tokens)。 */
    private static final int BASE_MAX_TOKENS = 8192;
    private static final String API_VERSION = "2023-06-01";

    private final String name;
    private final String defaultBaseUrl;
    private final String thinkingFormat;

    public AnthropicProvider() {
        this("anthropic", DEFAULT_BASE_URL, LlmProvider.THINKING_BUDGET);
    }

    /** 兼容站参数化:站名/基址来自站点数据;方言只在 budget(缺省)与 none 之间选。 */
    public AnthropicProvider(String name, String defaultBaseUrl, String thinkingFormat) {
        this.name = name;
        this.defaultBaseUrl = defaultBaseUrl;
        this.thinkingFormat = thinkingFormat == null || thinkingFormat.isBlank()
                ? LlmProvider.THINKING_BUDGET : thinkingFormat;
    }

    @Override public String name() { return name; }
    @Override public String defaultBaseUrl() { return defaultBaseUrl; }
    @Override public String chatPath() { return "/messages"; }

    /** 本站思考开关的方言家族(budget 或 none)。 */
    public String thinkingFormat() { return thinkingFormat; }

    @Override
    public Map<String, String> authHeaders(String apiKey) {
        return Map.of("x-api-key", apiKey == null ? "" : apiKey,
                      "anthropic-version", API_VERSION);
    }

    /** 严格校验未知字段的协议:只发 {@code stream:true},没有 stream_options。 */
    @Override
    public void applyStreaming(JsonObject body) {
        body.addProperty("stream", true);
    }

    // ---- 出向:消息构建 ----

    @Override
    public JsonObject buildUserMessage(String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        m.addProperty("content", content == null ? "" : content);
        return m;
    }

    /** 协议无 system 角色(system 是顶层参数),本方法仅满足接口,不参与请求组装。 */
    @Override
    public JsonObject buildSystemMessage(String content) {
        return buildUserMessage(content);
    }

    @Override
    public JsonObject buildToolResultMessage(String toolCallId, String content) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_result");
        block.addProperty("tool_use_id", toolCallId);
        block.addProperty("content", content == null ? "" : content);
        JsonArray blocks = new JsonArray();
        blocks.add(block);
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        m.add("content", blocks);
        return m;
    }

    @Override
    public JsonObject assistantToRequestMessage(AssistantTurn turn) {
        JsonArray blocks = new JsonArray();
        // 思考块必须带签名回传,缺签名的思考块会被拒收——没有签名就整块不发。
        String signature = turn.extras().has(SIGNATURE_KEY)
                ? turn.extras().get(SIGNATURE_KEY).getAsString() : "";
        if (turn.hasReasoning() && !signature.isEmpty()) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "thinking");
            thinking.addProperty("thinking", turn.reasoning());
            thinking.addProperty("signature", signature);
            blocks.add(thinking);
        }
        if (!turn.content().isEmpty()) {
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", turn.content());
            blocks.add(text);
        }
        for (LlmToolCall tc : turn.toolCalls()) {
            JsonObject use = new JsonObject();
            use.addProperty("type", "tool_use");
            use.addProperty("id", tc.id());
            use.addProperty("name", tc.name());
            use.add("input", parseArgsObject(tc.arguments()));
            blocks.add(use);
        }
        JsonObject m = new JsonObject();
        m.addProperty("role", "assistant");
        m.add("content", blocks);
        return m;
    }

    /** 调用参数在本协议里是 JSON 对象;内部存的是字符串,解析失败退空对象。 */
    private static JsonObject parseArgsObject(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return new JsonObject();
        try {
            JsonElement el = JsonParser.parseString(argumentsJson);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ex) {
            return new JsonObject();
        }
    }

    @Override
    public JsonArray buildToolList(Collection<? extends IToolSpec> tools) {
        JsonArray arr = new JsonArray();
        for (IToolSpec t : tools) {
            JsonObject tool = new JsonObject();
            tool.addProperty("name", t.name());
            tool.addProperty("description", t.description());
            tool.add("input_schema", GSON_TREE.toJsonTree(t.parameterSchema()));
            arr.add(tool);
        }
        return arr;
    }

    private static final com.google.gson.Gson GSON_TREE = new com.google.gson.Gson();

    @Override
    public JsonObject buildRequestBody(String model, String systemPrompt,
                                        List<JsonObject> messages, JsonArray tools) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", BASE_MAX_TOKENS);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.addProperty("system", systemPrompt);
        }
        body.add("messages", mergeConsecutiveRoles(messages));
        if (tools != null && !tools.isEmpty()) {
            body.add("tools", tools);
        }
        return body;
    }

    /**
     * 角色严格交替的归并:连续同角色消息并成一条,content 统一成块数组
     * (字符串 content 转 text 块)。并行工具结果(连续多条 tool_result 的
     * user 消息)在这里合体,这是本协议消息组装里最容易 400 的一处。
     */
    static JsonArray mergeConsecutiveRoles(List<JsonObject> messages) {
        JsonArray out = new JsonArray();
        JsonObject current = null;
        for (JsonObject m : messages) {
            String role = m.get("role").getAsString();
            if (current != null && role.equals(current.get("role").getAsString())) {
                current.getAsJsonArray("content").addAll(toBlocks(m.get("content")));
                continue;
            }
            current = new JsonObject();
            current.addProperty("role", role);
            current.add("content", toBlocks(m.get("content")));
            out.add(current);
        }
        return out;
    }

    /** content 归一成块数组:字符串 → 单个 text 块;已是数组则原样。 */
    private static JsonArray toBlocks(JsonElement content) {
        if (content != null && content.isJsonArray()) {
            JsonArray copy = new JsonArray();
            copy.addAll(content.getAsJsonArray());
            return copy;
        }
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", content == null || content.isJsonNull() ? "" : content.getAsString());
        JsonArray arr = new JsonArray();
        arr.add(text);
        return arr;
    }

    /**
     * 力度 → 预算的映射({@code budget} 族)。开思考时把 {@code max_tokens}
     * 抬到预算之上(协议要求预算必须小于输出上限)。{@code none} 族与
     * {@code off} 不发 thinking 参数(缺省即不思考,无需显式 disabled)。
     */
    @Override
    public void applyReasoning(JsonObject body, String effort) {
        if (LlmProvider.THINKING_NONE.equals(thinkingFormat) || "off".equals(effort)) return;
        int budget = switch (effort) {
            case "low" -> 2_048;
            case "high" -> 16_384;
            default -> 8_192;   // medium
        };
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", "enabled");
        thinking.addProperty("budget_tokens", budget);
        body.add("thinking", thinking);
        body.addProperty("max_tokens", budget + BASE_MAX_TOKENS);
    }

    // ---- 入向:流式累积 ----

    @Override
    public void accumulateChunk(JsonObject chunk, StreamAccumulator acc) {
        acc.chunkCount++;
        String type = chunk.has("type") ? chunk.get("type").getAsString() : "";
        switch (type) {
            case "message_start" -> {
                if (chunk.has("message") && chunk.get("message").isJsonObject()) {
                    mergeUsage(acc, chunk.getAsJsonObject("message").get("usage"));
                }
            }
            case "content_block_start" -> {
                JsonObject block = chunk.has("content_block") && chunk.get("content_block").isJsonObject()
                        ? chunk.getAsJsonObject("content_block") : null;
                if (block != null && "tool_use".equals(str(block, "type"))) {
                    int index = chunk.has("index") ? chunk.get("index").getAsInt() : 0;
                    StreamAccumulator.ToolCallBuilder b = acc.toolCallAt(index);
                    if (b.id == null) b.id = str(block, "id");
                    if (b.name == null) b.name = str(block, "name");
                }
            }
            case "content_block_delta" -> {
                JsonObject delta = chunk.has("delta") && chunk.get("delta").isJsonObject()
                        ? chunk.getAsJsonObject("delta") : null;
                if (delta == null) return;
                int index = chunk.has("index") ? chunk.get("index").getAsInt() : 0;
                switch (str(delta, "type")) {
                    case "text_delta" -> acc.content.append(nonNull(str(delta, "text")));
                    case "thinking_delta" -> {
                        acc.reasoningField = "thinking";
                        acc.reasoning.append(nonNull(str(delta, "thinking")));
                    }
                    case "input_json_delta" ->
                            acc.toolCallAt(index).arguments.append(nonNull(str(delta, "partial_json")));
                    case "signature_delta" ->
                            acc.appendExtra(SIGNATURE_KEY, str(delta, "signature"));
                    default -> { }
                }
            }
            case "message_delta" -> {
                if (chunk.has("delta") && chunk.get("delta").isJsonObject()) {
                    String stop = str(chunk.getAsJsonObject("delta"), "stop_reason");
                    if (stop != null) acc.finishReason = mapStopReason(stop);
                }
                mergeUsage(acc, chunk.get("usage"));
            }
            default -> { }   // message_stop / ping / error帧由传输层处理
        }
    }

    /** usage 两截归一成 OpenAI 形字段名——client 的日志与记账下游对协议无感。 */
    private static void mergeUsage(StreamAccumulator acc, JsonElement usageEl) {
        if (usageEl == null || !usageEl.isJsonObject()) return;
        JsonObject in = usageEl.getAsJsonObject();
        JsonObject u = acc.usage != null ? acc.usage : new JsonObject();
        if (in.has("input_tokens")) {
            int input = in.get("input_tokens").getAsInt();
            int cacheRead = LlmProvider.usageInt(in, "cache_read_input_tokens");
            int cacheWrite = LlmProvider.usageInt(in, "cache_creation_input_tokens");
            // input_tokens 本身不含缓存命中,prompt_tokens 报全量口径,命中量入 details。
            u.addProperty("prompt_tokens", input + cacheRead + cacheWrite);
            JsonObject details = new JsonObject();
            details.addProperty("cached_tokens", cacheRead);
            u.add("prompt_tokens_details", details);
        }
        if (in.has("output_tokens")) {
            u.addProperty("completion_tokens", in.get("output_tokens").getAsInt());
        }
        u.addProperty("total_tokens",
                LlmProvider.usageInt(u, "prompt_tokens") + LlmProvider.usageInt(u, "completion_tokens"));
        acc.usage = u;
    }

    /** stop_reason → OpenAI 形 finish_reason,日志与下游判读统一口径。 */
    private static String mapStopReason(String stop) {
        return switch (stop) {
            case "end_turn", "stop_sequence" -> "stop";
            case "tool_use" -> "tool_calls";
            case "max_tokens" -> "length";
            default -> stop;
        };
    }

    /** usage 已归一成 OpenAI 形:命中量在 details 里,减法口径与 OpenAI 系一致。 */
    @Override
    public long freshTokens(JsonObject usage) {
        long fresh = LlmProvider.usageInt(usage, "prompt_tokens")
                + LlmProvider.usageInt(usage, "completion_tokens");
        if (usage != null && usage.has("prompt_tokens_details")
                && usage.get("prompt_tokens_details").isJsonObject()) {
            fresh -= LlmProvider.usageInt(usage.getAsJsonObject("prompt_tokens_details"), "cached_tokens");
        }
        return Math.max(0, fresh);
    }

    @Override
    public AssistantTurn finalizeStream(StreamAccumulator acc) {
        List<LlmToolCall> calls = new ArrayList<>(acc.toolCalls.size());
        for (StreamAccumulator.ToolCallBuilder b : acc.toolCalls.values()) {
            if (b.id == null && b.name == null && b.arguments.length() == 0) continue;
            String args = b.arguments.length() == 0 ? "{}" : b.arguments.toString();
            calls.add(new LlmToolCall(b.id == null ? "" : b.id, b.name == null ? "" : b.name, args));
        }
        JsonObject extras = new JsonObject();
        for (var e : acc.extraBuffers.entrySet()) {
            extras.addProperty(e.getKey(), e.getValue().toString());
        }
        for (var e : acc.extraJson.entrySet()) {
            if (!extras.has(e.getKey())) extras.add(e.getKey(), e.getValue());
        }
        return new AssistantTurn(acc.content.toString(), calls, extras, acc.reasoning.toString());
    }

    @Override
    public String extractReasoningDelta(JsonObject chunk) {
        if (!"content_block_delta".equals(str(chunk, "type"))) return null;
        if (!chunk.has("delta") || !chunk.get("delta").isJsonObject()) return null;
        JsonObject delta = chunk.getAsJsonObject("delta");
        if (!"thinking_delta".equals(str(delta, "type"))) return null;
        String v = str(delta, "thinking");
        return v == null || v.isEmpty() ? null : v;
    }

    // ---- 入向:非流式 ----

    @Override
    public AssistantTurn parseResponseBody(JsonObject body) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<LlmToolCall> calls = new ArrayList<>();
        JsonObject extras = new JsonObject();
        if (body.has("content") && body.get("content").isJsonArray()) {
            for (JsonElement el : body.getAsJsonArray("content")) {
                if (!el.isJsonObject()) continue;
                JsonObject block = el.getAsJsonObject();
                switch (nonNull(str(block, "type"))) {
                    case "text" -> content.append(nonNull(str(block, "text")));
                    case "thinking" -> {
                        reasoning.append(nonNull(str(block, "thinking")));
                        String sig = str(block, "signature");
                        if (sig != null && !sig.isEmpty()) extras.addProperty(SIGNATURE_KEY, sig);
                    }
                    case "tool_use" -> calls.add(new LlmToolCall(
                            nonNull(str(block, "id")), nonNull(str(block, "name")),
                            block.has("input") && block.get("input").isJsonObject()
                                    ? block.get("input").toString() : "{}"));
                    default -> { }
                }
            }
        }
        return new AssistantTurn(content.toString(), calls, extras, reasoning.toString());
    }

    private static String str(JsonObject o, String key) {
        var el = o.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static String nonNull(String s) { return s == null ? "" : s; }
}
