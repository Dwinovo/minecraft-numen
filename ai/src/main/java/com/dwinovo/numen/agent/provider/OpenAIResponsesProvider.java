package com.dwinovo.numen.agent.provider;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * OpenAI Responses API(POST /v1/responses)。
 *
 * <h2>为什么要单独一条,而不是给 chat/completions 打补丁</h2>
 * 推理型号在 chat/completions 上<b>不把思考交出来</b>(最多给一句摘要),所以每一轮都从零重推,
 * reasoning token 照烧、连贯性却拿不回。Responses 把思考做成可回传的 item(带 id;store:false
 * 时是加密内容),下一轮原样塞回 input 就能接着想。这是这条协议对我们唯一不可替代的价值。
 *
 * <h2>线格式的差别:消息 → 条目流</h2>
 * chat/completions 是"一条消息一个对象";Responses 是一串 item。一条助手回合会拆成<b>多条</b>:
 * 思考 item、文本 message、每个工具调用一条 function_call。这就是
 * {@link LlmProvider#assistantToRequestItems} 收条目而不是收单个对象的原因。
 *
 * <h2>思考怎么带回去</h2>
 * 收到的思考 item 原样收进 {@link AssistantTurn#extras}(键 {@value #REASONING_ITEMS}),下一轮
 * 一字不改地放回 input 的最前面。<b>不自己重建</b>:item 里有 id 与加密内容,服务端按它校验
 * 工具调用与思考的配对关系,改一个字节就会被拒。
 *
 * <p>换绑模型之后不回传——那一步在 NumenLlmClient 按回合出处统一挡下,不在这里判。
 */
public final class OpenAIResponsesProvider implements LlmProvider {

    public static final String NAME = "openai-responses";
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    /** extras 里存思考 item 原文的键。 */
    public static final String REASONING_ITEMS = "numen_reasoning_items";

    private static final Gson GSON = new Gson();

    private final String name;
    private final String defaultBaseUrl;

    public OpenAIResponsesProvider() {
        this(NAME, DEFAULT_BASE_URL);
    }

    public OpenAIResponsesProvider(String name, String defaultBaseUrl) {
        this.name = name == null || name.isBlank() ? NAME : name;
        this.defaultBaseUrl = defaultBaseUrl == null || defaultBaseUrl.isBlank()
                ? DEFAULT_BASE_URL : defaultBaseUrl;
    }

    @Override public String name() { return name; }

    @Override public String defaultBaseUrl() { return defaultBaseUrl; }

    @Override public String chatPath() { return "/responses"; }

    // ==================== 请求侧:消息 → input 条目 ====================

    @Override
    public JsonObject buildUserMessage(String content) {
        return message("user", "input_text", content);
    }

    /** 系统话走 developer 角色——Responses 上推理型号认的是它,老型号也接受,所以不按型号分叉。 */
    @Override
    public JsonObject buildSystemMessage(String content) {
        return message("developer", "input_text", content);
    }

    @Override
    public JsonObject buildToolResultMessage(String toolCallId, String content) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "function_call_output");
        out.addProperty("call_id", toolCallId == null ? "" : toolCallId);
        out.addProperty("output", content == null ? "" : content);
        return out;
    }

    @Override
    public List<JsonObject> assistantToRequestItems(AssistantTurn turn) {
        List<JsonObject> items = new ArrayList<>();
        // 思考排在最前:服务端按顺序校验它和后面那些工具调用的配对
        for (JsonElement el : reasoningItemsOf(turn)) {
            if (el.isJsonObject()) {
                items.add(el.getAsJsonObject().deepCopy());
            }
        }
        if (!turn.content().isEmpty()) {
            items.add(message("assistant", "output_text", turn.content()));
        }
        for (LlmToolCall tc : turn.toolCalls()) {
            JsonObject call = new JsonObject();
            call.addProperty("type", "function_call");
            call.addProperty("call_id", tc.id());
            call.addProperty("name", tc.name());
            call.addProperty("arguments", tc.arguments());
            items.add(call);
        }
        return items;
    }

    @Override
    public JsonArray buildToolList(Collection<? extends IToolSpec> tools) {
        JsonArray arr = new JsonArray();
        for (IToolSpec t : tools) {
            // Responses 的工具是平的:没有 chat/completions 那层 function 包装
            JsonObject fn = new JsonObject();
            fn.addProperty("type", "function");
            fn.addProperty("name", t.name());
            fn.addProperty("description", t.description());
            fn.add("parameters", GSON.toJsonTree(t.parameterSchema()));
            arr.add(fn);
        }
        return arr;
    }

    @Override
    public JsonObject buildRequestBody(String model, String systemPrompt,
                                       List<JsonObject> messages, JsonArray tools) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray input = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            input.add(buildSystemMessage(systemPrompt));
        }
        for (JsonObject m : messages) {
            input.add(m);
        }
        body.add("input", input);
        if (tools != null && !tools.isEmpty()) {
            body.add("tools", tools);
            body.addProperty("parallel_tool_calls", true);
        }
        // 不让服务端替我们存这次对话:历史是我们自己的账本,存一份在别人那儿就成了第二处真源。
        // 代价是思考 item 带加密内容回传,而那正是 extras 保管的东西。
        body.addProperty("store", false);
        JsonArray include = new JsonArray();
        include.add("reasoning.encrypted_content");
        body.add("include", include);
        return body;
    }

    /** 力度:Responses 只认嵌套写法。 */
    @Override
    public void applyReasoning(JsonObject body, String effort) {
        if ("off".equals(effort)) {
            return;
        }
        JsonObject r = new JsonObject();
        r.addProperty("effort", effort);
        body.add("reasoning", r);
    }

    @Override
    public void applyGenerationParams(JsonObject body, Double temperature, int maxTokens) {
        if (temperature != null && temperature > 0) {
            body.addProperty("temperature", temperature);
        }
        if (maxTokens > 0) {
            body.addProperty("max_output_tokens", maxTokens);
        }
    }

    // ==================== 响应侧:output 条目 → 一条回合 ====================

    @Override
    public AssistantTurn parseResponseBody(JsonObject body) {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<LlmToolCall> calls = new ArrayList<>();
        JsonArray reasoningItems = new JsonArray();
        if (body.has("output") && body.get("output").isJsonArray()) {
            for (JsonElement el : body.getAsJsonArray("output")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject item = el.getAsJsonObject();
                switch (str(item, "type")) {
                    case "message" -> text.append(textOf(item));
                    case "function_call" -> calls.add(new LlmToolCall(
                            str(item, "call_id"), str(item, "name"), str(item, "arguments")));
                    case "reasoning" -> {
                        reasoningItems.add(item.deepCopy());
                        reasoning.append(summaryOf(item));
                    }
                    default -> { }
                }
            }
        }
        JsonObject extras = new JsonObject();
        if (!reasoningItems.isEmpty()) {
            extras.add(REASONING_ITEMS, reasoningItems);
        }
        return new AssistantTurn(text.toString(), calls, extras, reasoning.toString());
    }

    // ==================== 流式:带类型的事件 → 累加器 ====================

    /**
     * Responses 的流是<b>带类型的事件</b>,不是 chat/completions 那种 delta 片段。
     * 只认我们要的那几种,其余(created / in_progress / part.added)无视。
     */
    @Override
    public void accumulateChunk(JsonObject chunk, StreamAccumulator acc) {
        String type = str(chunk, "type");
        switch (type) {
            case "response.output_text.delta" -> acc.content.append(str(chunk, "delta"));
            case "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> {
                acc.reasoningField = "reasoning";
                acc.reasoning.append(str(chunk, "delta"));
            }
            case "response.output_item.added" -> {
                JsonObject item = obj(chunk, "item");
                if (item != null && "function_call".equals(str(item, "type"))) {
                    StreamAccumulator.ToolCallBuilder b = acc.toolCallAt(intOf(chunk, "output_index"));
                    b.id = str(item, "call_id");
                    b.name = str(item, "name");
                }
            }
            case "response.function_call_arguments.delta" ->
                    acc.toolCallAt(intOf(chunk, "output_index")).arguments.append(str(chunk, "delta"));
            case "response.output_item.done" -> {
                // 思考 item 只有在 done 里才是完整的(带 id 与加密内容),原样收着
                JsonObject item = obj(chunk, "item");
                if (item != null && "reasoning".equals(str(item, "type"))) {
                    acc.mergeExtraJson(REASONING_ITEMS, item.deepCopy());
                }
            }
            case "response.completed", "response.incomplete", "response.failed" -> {
                JsonObject response = obj(chunk, "response");
                if (response != null && response.has("usage") && response.get("usage").isJsonObject()) {
                    acc.usage = response.getAsJsonObject("usage");
                }
                acc.finishReason = type.substring(type.lastIndexOf(46) + 1);
            }
            default -> { }
        }
        acc.chunkCount++;
    }

    @Override
    public AssistantTurn finalizeStream(StreamAccumulator acc) {
        List<LlmToolCall> calls = new ArrayList<>();
        for (StreamAccumulator.ToolCallBuilder b : acc.toolCalls.values()) {
            if (b.id != null && b.name != null) {
                calls.add(new LlmToolCall(b.id, b.name, b.arguments.toString()));
            }
        }
        JsonObject extras = new JsonObject();
        JsonElement items = acc.extraJson.get(REASONING_ITEMS);
        if (items != null) {
            extras.add(REASONING_ITEMS, items);
        }
        return new AssistantTurn(acc.content.toString(), calls, extras, acc.reasoning.toString());
    }

    @Override
    public String extractReasoningDelta(JsonObject chunk) {
        String type = str(chunk, "type");
        return "response.reasoning_text.delta".equals(type)
                || "response.reasoning_summary_text.delta".equals(type) ? str(chunk, "delta") : "";
    }

    /**
     * 用量字段名与 chat/completions 不同:{@code input_tokens}/{@code output_tokens},缓存在
     * {@code input_tokens_details} 里。四元的口径照旧(input 是"这轮真正新处理的",缓存单列),
     * 所以换协议不影响账面的可比性。
     *
     * <p>{@code output_tokens_details.reasoning_tokens} 这一项不单列——它已经含在 output 里,
     * 四元里也没有它的位置,单拉出来只会让总账对不上。
     */
    @Override
    public Usage usage(JsonObject usage) {
        if (usage == null) {
            return Usage.ZERO;
        }
        long total = LlmProvider.usageInt(usage, "input_tokens");
        long output = LlmProvider.usageInt(usage, "output_tokens");
        long cacheRead = 0;
        if (usage.has("input_tokens_details") && usage.get("input_tokens_details").isJsonObject()) {
            cacheRead = LlmProvider.usageInt(usage.getAsJsonObject("input_tokens_details"), "cached_tokens");
        }
        return new Usage(Math.max(0, total - cacheRead), output, cacheRead, 0);
    }

    // ==================== 小工具 ====================

    private static JsonObject message(String role, String contentType, String text) {
        JsonObject part = new JsonObject();
        part.addProperty("type", contentType);
        part.addProperty("text", text == null ? "" : text);
        JsonArray content = new JsonArray();
        content.add(part);
        JsonObject m = new JsonObject();
        m.addProperty("type", "message");
        m.addProperty("role", role);
        m.add("content", content);
        return m;
    }

    private static JsonArray reasoningItemsOf(AssistantTurn turn) {
        JsonElement el = turn.extras().get(REASONING_ITEMS);
        if (el == null) {
            return new JsonArray();
        }
        if (el.isJsonArray()) {
            return el.getAsJsonArray();
        }
        JsonArray one = new JsonArray();
        one.add(el);
        return one;
    }

    private static String textOf(JsonObject message) {
        StringBuilder sb = new StringBuilder();
        if (message.has("content") && message.get("content").isJsonArray()) {
            for (JsonElement c : message.getAsJsonArray("content")) {
                if (c.isJsonObject() && "output_text".equals(str(c.getAsJsonObject(), "type"))) {
                    sb.append(str(c.getAsJsonObject(), "text"));
                }
            }
        }
        return sb.toString();
    }

    /** 思考 item 里给人看的那部分;加密内容不在这里,它随 item 原文一起走 extras。 */
    private static String summaryOf(JsonObject item) {
        StringBuilder sb = new StringBuilder();
        if (item.has("summary") && item.get("summary").isJsonArray()) {
            for (JsonElement s : item.getAsJsonArray("summary")) {
                if (s.isJsonObject()) {
                    sb.append(str(s.getAsJsonObject(), "text"));
                }
            }
        }
        return sb.toString();
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    private static int intOf(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : 0;
    }
}
