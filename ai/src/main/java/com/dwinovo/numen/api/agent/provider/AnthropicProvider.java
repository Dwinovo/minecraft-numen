package com.dwinovo.numen.api.agent.provider;

import com.dwinovo.numen.api.agent.http.ProviderReply;
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
 * 第二协议。凡是暴露 Anthropic 兼容端点的厂家(官方与兼容站)都由本类承载,
 * 厂家差异走 {@link AnthropicCompat}(自适应思考还是预算、收不收温度),不为兼容站开子类。
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
 *       JSON 对象不是字符串 → {@link #buildToolList} / {@link #assistantToRequestItems}</li>
 *   <li><b>思考:</b>content block 形态({@code thinking} 块 + 签名),多轮
 *       必须带签名回传 → 签名存 extras 的 {@value #SIGNATURE_KEY},
 *       {@link #assistantToRequestItems} 重建思考块</li>
 *   <li><b>流式:</b>事件按 {@code type} 分派(块级 start/delta/stop),
 *       usage 分两截(message_start 进/message_delta 出),累积时归一成
 *       OpenAI 形字段名,client 与记账下游无感 → {@link #accumulateChunk}</li>
 *   <li><b>{@code max_tokens} 必填;</b>未知顶层字段会被严格拒收(所以
 *       {@link #buildRequestBody} 流式只发 {@code stream:true})</li>
 * </ul>
 */
public final class AnthropicProvider implements LlmProvider {

    /** 思考块签名在 extras 里的落位(回传重建思考块时取用)。 */
    public static final String SIGNATURE_KEY = "anthropic_thinking_signature";

    /** 无思考时的输出上限;开思考后在此之上再加预算(预算必须小于 max_tokens)。 */
    private static final int BASE_MAX_TOKENS = 8192;
    private static final String API_VERSION = "2023-06-01";

    private final AnthropicCompat compat;

    public AnthropicProvider(AnthropicCompat compat) {
        this.compat = compat;
    }

    @Override public String chatPath() { return "/messages"; }

    @Override
    public Map<String, String> authHeaders(String apiKey) {
        return Map.of("x-api-key", apiKey == null ? "" : apiKey,
                      "anthropic-version", API_VERSION);
    }

    // ---- 出向:消息构建 ----

    @Override
    public JsonObject buildUserMessage(String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        m.addProperty("content", content == null ? "" : content);
        return m;
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
    public List<JsonObject> assistantToRequestItems(AssistantTurn turn) {
        return List.of(assistantMessage(turn));
    }

    private JsonObject assistantMessage(AssistantTurn turn) {
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
            // 本协议里 input 是对象;LlmToolCall 已经保证 arguments 是合法对象文本
            use.add("input", JsonParser.parseString(tc.arguments()).getAsJsonObject());
            blocks.add(use);
        }
        JsonObject m = new JsonObject();
        m.addProperty("role", "assistant");
        m.add("content", blocks);
        return m;
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

    /**
     * 本协议的 prompt 缓存要显式打断点({@code cache_control}),不打就一个 token 都不缓存。
     * 缓存前缀的顺序是 工具 → system → 消息,每个请求至多 4 个断点,这里正好用满:
     * <ul>
     *   <li>最后一个工具:工具清单单独成段,改了人设也照样命中;</li>
     *   <li>system:字节级稳定的系统提示;</li>
     *   <li>消息里最后两个可打断点的块:最后一块常是每轮现算、不进历史的
     *       {@code <runtime_state>},下一轮就变;倒数第二块是历史的末尾,下一轮还在原处,
     *       服务端回看时从它命中整段历史。</li>
     * </ul>
     */
    @Override
    public JsonObject buildRequestBody(ModelSpec model, String systemPrompt, List<JsonObject> messages,
                                       JsonArray tools, RequestOptions options, String sessionId) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model.id());
        // max_tokens 是本协议的必填项;玩家没设就用协议自己的缺省
        int maxTokens = options.maxTokens() > 0 ? options.maxTokens() : BASE_MAX_TOKENS;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", systemPrompt);
            text.add("cache_control", CacheBreakpoints.ephemeral());
            JsonArray system = new JsonArray();
            system.add(text);
            body.add("system", system);
        }
        JsonArray merged = mergeConsecutiveRoles(messages);
        markLastBlocks(merged, CacheBreakpoints.HISTORY);
        body.add("messages", merged);
        if (tools != null && !tools.isEmpty()) {
            JsonArray marked = tools.deepCopy();
            marked.get(marked.size() - 1).getAsJsonObject().add("cache_control", CacheBreakpoints.ephemeral());
            body.add("tools", marked);
        }

        // 思考:这一档写成什么由 ThinkingPlan 定(自适应型号写 effort,老型号写预算,关写 disabled)。
        // 预算必须小于 max_tokens,所以加在玩家设的上限之上,玩家的值仍然是"正文的上限"。
        ThinkingPlan.Wire wire = ThinkingPlan.of(model.thinking(), compat).wireFor(options.thinking());
        boolean thinking = false;
        JsonObject t = new JsonObject();
        switch (wire.kind()) {
            case AUTO -> { }
            case OFF -> {
                t.addProperty("type", "disabled");
                body.add("thinking", t);
            }
            case EFFORT -> {
                t.addProperty("type", "adaptive");
                JsonObject output = new JsonObject();
                output.addProperty("effort", wire.effort());
                body.add("output_config", output);
                body.add("thinking", t);
                thinking = true;
            }
            case BUDGET -> {
                t.addProperty("type", "enabled");
                t.addProperty("budget_tokens", wire.tokens());
                body.add("thinking", t);
                maxTokens += wire.tokens();
                thinking = true;
            }
            case ON -> throw new IllegalStateException("ThinkingPlan 不会给 Anthropic 排出 " + wire.kind());
        }
        body.addProperty("max_tokens", maxTokens);

        // 开着思考时协议只允许默认温度,所以不发
        Double temperature = options.temperatureFor(model);
        if (temperature != null && compat.temperature() && !thinking) {
            body.addProperty("temperature", temperature);
        }
        // 严格校验未知字段的协议:流式只发 stream:true,没有 stream_options
        body.addProperty("stream", true);
        return body;
    }

    /**
     * 从末尾往前给 {@code count} 个块打缓存断点。思考块不能打断点,跳过;
     * 块对象可能与调用方的消息共用,先换成副本再改。
     */
    private static void markLastBlocks(JsonArray messages, int count) {
        int left = count;
        for (int m = messages.size() - 1; m >= 0 && left > 0; m--) {
            JsonArray blocks = messages.get(m).getAsJsonObject().getAsJsonArray("content");
            for (int b = blocks.size() - 1; b >= 0 && left > 0; b--) {
                JsonObject block = blocks.get(b).getAsJsonObject();
                String type = block.get("type").getAsString();
                if (type.equals("thinking") || type.equals("redacted_thinking")) continue;
                JsonObject marked = block.deepCopy();
                marked.add("cache_control", CacheBreakpoints.ephemeral());
                blocks.set(b, marked);
                left--;
            }
        }
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
                    JsonObject delta = chunk.getAsJsonObject("delta");
                    String stop = str(delta, "stop_reason");
                    if (stop != null) acc.finishReason = finishReason(stop, delta, acc);
                }
                mergeUsage(acc, chunk.get("usage"));
            }
            // 收尾:没有 stop_reason 的 message_stop 不算数,流还是没收好
            case "message_stop" -> acc.ended = acc.finishReason != null;
            case "error" -> {
                String reply = ProviderReply.of(chunk);
                acc.failure = reply != null ? reply : chunk.toString();
            }
            default -> { }   // ping 等
        }
    }

    /**
     * usage 分两截(message_start 带输入与缓存量,message_delta 带输出量),按字段并进同一个对象。
     * 只收有值的字段:缺失或 null 都不覆盖已有的——有的代理在 message_delta 里省掉输入量。
     */
    private static void mergeUsage(StreamAccumulator acc, JsonElement usageEl) {
        if (usageEl == null || !usageEl.isJsonObject()) return;
        JsonObject u = acc.usage != null ? acc.usage : new JsonObject();
        for (var e : usageEl.getAsJsonObject().entrySet()) {
            if (e.getValue().isJsonPrimitive()) u.add(e.getKey(), e.getValue());
        }
        acc.usage = u;
    }

    /**
     * 本协议的 {@code input_tokens} 本身不含缓存:读({@code cache_read_input_tokens})与写
     * ({@code cache_creation_input_tokens})各自单列,和四元口径一致,不用减。
     */
    @Override
    public Usage usage(JsonObject usage) {
        if (usage == null) return Usage.ZERO;
        return new Usage(LlmProvider.usageInt(usage, "input_tokens"), LlmProvider.usageInt(usage, "output_tokens"),
                LlmProvider.usageInt(usage, "cache_read_input_tokens"),
                LlmProvider.usageInt(usage, "cache_creation_input_tokens"));
    }

    /**
     * stop_reason → OpenAI 形 finish_reason,日志与下游判读统一口径。正常收尾之外的原因算流失败:
     * 模型拒绝({@code refusal},附带它的解释)、内容被安全过滤({@code sensitive})、以及不认识的新取值。
     * {@code pause_turn} 只是服务端工具回合暂停,与正常结束同样处理。
     */
    private static String finishReason(String stop, JsonObject delta, StreamAccumulator acc) {
        switch (stop) {
            case "end_turn", "stop_sequence", "pause_turn" -> { return "stop"; }
            case "tool_use" -> { return "tool_calls"; }
            case "max_tokens" -> { return "length"; }
            case "refusal" -> {
                String why = delta.has("stop_details") && delta.get("stop_details").isJsonObject()
                        ? str(delta.getAsJsonObject("stop_details"), "explanation") : null;
                acc.failure = why != null && !why.isEmpty() ? why : "The model refused to complete the request";
            }
            case "sensitive" -> acc.failure = "Provider stopped with: sensitive";
            default -> acc.failure = "Unhandled stop reason: " + stop;
        }
        return stop;
    }


    @Override
    public AssistantTurn finalizeStream(StreamAccumulator acc) {
        acc.requireEnded();
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
