package com.dwinovo.numen.api.agent.provider;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * OpenAI Responses API(POST /v1/responses)。逐项对照 pi-mono 的 {@code openai-responses.ts} 与
 * {@code openai-responses-shared.ts}:请求体的字段与条目形状、多轮回传、流事件、用量口径都取同一种写法。
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
 * <h2>多轮回传:两样东西原样带回去</h2>
 * 服务端按 id 校验思考与它后面那条输出(文本或工具调用)的配对,所以:
 * <ul>
 *   <li>思考 item 整条原样收进 {@link AssistantTurn#extras}(键 {@value #REASONING_ITEMS}),下一轮一字不改地
 *       放回 input 里该回合的最前面,<b>不自己重建</b>(里面有 id 与加密内容);</li>
 *   <li>文本 message 与 function_call 的 item id(及 message 的 {@code phase})收进键 {@value #ITEM_IDS},
 *       回传时按原 id 写回;{@code call_id} 才是我们的工具调用 id,用它配对工具结果。</li>
 * </ul>
 * 没有记下 id 的回合(换过模型,私货被脱掉)就没有思考 item,message 用确定的占位 id,function_call 不带 id。
 *
 * <p>换绑模型之后不回传——那一步在 NumenLlmClient 按回合出处统一挡下,不在这里判。
 */
public final class OpenAIResponsesProvider implements LlmProvider {

    /** extras 里存思考 item 原文(数组)的键。 */
    public static final String REASONING_ITEMS = "numen_reasoning_items";
    /** extras 里存文本 message 与 function_call 的 item id(数组,每项 {@code {type, id, call_id?, phase?}})的键。 */
    public static final String ITEM_IDS = "numen_item_ids";

    /** 接口拒绝低于 16 的 {@code max_output_tokens}。 */
    private static final int MIN_OUTPUT_TOKENS = 16;
    private static final int CACHE_KEY_MAX = 64;

    private static final Gson GSON = new Gson();

    private final ResponsesCompat compat;

    public OpenAIResponsesProvider(ResponsesCompat compat) {
        this.compat = compat;
    }

    @Override public String chatPath() { return "/responses"; }

    // ==================== 请求侧:消息 → input 条目 ====================

    @Override
    public JsonObject buildUserMessage(String content) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "input_text");
        part.addProperty("text", content == null ? "" : content);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        m.add("content", parts);
        return m;
    }

    /** 系统话的角色:推理型号且兼容项允许就用 {@code developer},否则 {@code system}。 */
    private JsonObject systemMessage(ModelSpec model, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", model.thinking().thinks() && compat.developerRole() ? "developer" : "system");
        m.addProperty("content", content);
        return m;
    }

    @Override
    public JsonObject buildToolResultMessage(String toolCallId, String content) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "function_call_output");
        out.addProperty("call_id", toolCallId == null ? "" : toolCallId);
        // 空输出接口不收
        out.addProperty("output", content == null || content.isEmpty() ? "(no tool output)" : content);
        return out;
    }

    @Override
    public List<JsonObject> assistantToRequestItems(AssistantTurn turn) {
        List<JsonObject> items = new ArrayList<>();
        // 思考排在最前:服务端按顺序校验它和后面那些输出的配对
        for (JsonElement el : arrayOf(turn.extras().get(REASONING_ITEMS))) {
            if (el.isJsonObject()) {
                items.add(el.getAsJsonObject().deepCopy());
            }
        }
        JsonArray ids = arrayOf(turn.extras().get(ITEM_IDS));
        if (!turn.content().isEmpty()) {
            JsonObject recorded = idEntry(ids, "message", null);
            JsonObject text = new JsonObject();
            text.addProperty("type", "output_text");
            text.addProperty("text", turn.content());
            text.add("annotations", new JsonArray());
            JsonArray content = new JsonArray();
            content.add(text);
            JsonObject m = new JsonObject();
            m.addProperty("type", "message");
            m.addProperty("role", "assistant");
            m.add("content", content);
            m.addProperty("status", "completed");
            // message 的 id 是必填;没记下的用内容算一个确定的占位
            String recordedId = str(recorded, "id");
            m.addProperty("id", recordedId.isEmpty()
                    ? "msg_numen_" + Integer.toHexString(turn.content().hashCode()) : recordedId);
            if (!str(recorded, "phase").isEmpty()) {
                m.addProperty("phase", str(recorded, "phase"));
            }
            items.add(m);
        }
        for (LlmToolCall tc : turn.toolCalls()) {
            JsonObject call = new JsonObject();
            call.addProperty("type", "function_call");
            JsonObject recorded = idEntry(ids, "function_call", tc.id());
            // 服务端只认 fc_ 开头的 item id
            if (recorded != null && str(recorded, "id").startsWith("fc_")) {
                call.addProperty("id", str(recorded, "id"));
            }
            call.addProperty("call_id", tc.id());
            call.addProperty("name", tc.name());
            call.addProperty("arguments", tc.arguments().isBlank() ? "{}" : tc.arguments());
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
    public JsonObject buildRequestBody(ModelSpec model, String systemPrompt, List<JsonObject> messages,
                                       JsonArray tools, RequestOptions options, String sessionId) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model.id());
        JsonArray input = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            input.add(systemMessage(model, systemPrompt));
        }
        for (JsonObject m : messages) {
            input.add(m);
        }
        body.add("input", input);
        body.addProperty("stream", true);
        // 同一段对话落到同一个缓存分片,命中才稳;键最长 64 个字符
        if (sessionId != null && !sessionId.isBlank()) {
            body.addProperty("prompt_cache_key", sessionId.codePointCount(0, sessionId.length()) <= CACHE_KEY_MAX
                    ? sessionId : sessionId.substring(0, sessionId.offsetByCodePoints(0, CACHE_KEY_MAX)));
        }
        // 不让服务端替我们存这次对话:历史是我们自己的账本,存一份在别人那儿就成了第二处真源。
        // 代价是思考 item 带加密内容回传,而那正是 extras 保管的东西。
        body.addProperty("store", false);
        if (tools != null && !tools.isEmpty()) {
            body.add("tools", tools);
        }
        Double temperature = options.temperatureFor(model);
        if (temperature != null) {
            body.addProperty("temperature", temperature);
        }
        if (options.maxTokens() > 0) {
            body.addProperty("max_output_tokens", Math.max(options.maxTokens(), MIN_OUTPUT_TOKENS));
        }
        applyReasoning(body, model, ThinkingPlan.of(model.thinking(), compat).wireFor(options.thinking()));
        return body;
    }

    /**
     * 思考怎么请求。选了力度:{@code reasoning:{effort, summary:"auto"}},并要加密思考内容(要摘要才有的看,
     * 要加密内容下一轮才带得回去)。选了关:只写 effort 的"关"字,不会有思考 item,也就不要加密内容。
     * 选了自动:不写 {@code reasoning}(交给服务端),但会思考的型号照样要加密内容——服务端默认也会思考,
     * 带 id 却没有加密内容的思考 item 在 store:false 下回传会被拒;不会思考的型号不收这个 include。
     */
    private static void applyReasoning(JsonObject body, ModelSpec model, ThinkingPlan.Wire wire) {
        switch (wire.kind()) {
            case EFFORT -> {
                JsonObject r = new JsonObject();
                r.addProperty("effort", wire.effort());
                r.addProperty("summary", "auto");
                body.add("reasoning", r);
                includeEncryptedReasoning(body);
            }
            case OFF -> {
                JsonObject r = new JsonObject();
                r.addProperty("effort", wire.effort());
                body.add("reasoning", r);
            }
            case AUTO -> {
                if (model.thinking().thinks()) {
                    includeEncryptedReasoning(body);
                }
            }
            case ON, BUDGET -> throw new IllegalStateException("ThinkingPlan 不会给 Responses 排出 " + wire.kind());
        }
    }

    private static void includeEncryptedReasoning(JsonObject body) {
        JsonArray include = new JsonArray();
        include.add("reasoning.encrypted_content");
        body.add("include", include);
    }

    // ==================== 响应侧:output 条目 → 一条回合 ====================

    @Override
    public AssistantTurn parseResponseBody(JsonObject body) {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<LlmToolCall> calls = new ArrayList<>();
        JsonArray reasoningItems = new JsonArray();
        JsonArray ids = new JsonArray();
        if (body.has("output") && body.get("output").isJsonArray()) {
            for (JsonElement el : body.getAsJsonArray("output")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject item = el.getAsJsonObject();
                switch (str(item, "type")) {
                    case "message" -> {
                        text.append(textOf(item));
                        ids.add(idOf(item));
                    }
                    case "function_call" -> {
                        calls.add(new LlmToolCall(str(item, "call_id"), str(item, "name"), str(item, "arguments")));
                        ids.add(idOf(item));
                    }
                    case "reasoning" -> {
                        reasoningItems.add(item.deepCopy());
                        reasoning.append(summaryOf(item));
                    }
                    default -> { }
                }
            }
        }
        return turn(text.toString(), calls, reasoningItems, ids, reasoning.toString());
    }

    // ==================== 流式:带类型的事件 → 累加器 ====================

    /**
     * Responses 的流是<b>带类型的事件</b>,不是 chat/completions 那种 delta 片段。
     * 认的有:文本与拒绝语的增量、思考摘要与思考原文的增量、output_item 的 added / done、
     * 工具参数的增量与 done、三种收尾(completed / incomplete / failed)和 error;其余(created / in_progress /
     * content_part 等)无视。
     */
    @Override
    public void accumulateChunk(JsonObject chunk, StreamAccumulator acc) {
        String type = str(chunk, "type");
        switch (type) {
            case "response.output_text.delta", "response.refusal.delta" -> acc.content.append(str(chunk, "delta"));
            case "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> {
                acc.reasoningField = "reasoning";
                acc.reasoning.append(str(chunk, "delta"));
            }
            // 一条思考可以分好几段摘要;段与段之间空一行
            case "response.reasoning_summary_part.added" -> {
                if (acc.reasoning.length() > 0) {
                    acc.reasoning.append("\n\n");
                }
            }
            case "response.output_item.added" -> {
                JsonObject item = obj(chunk, "item");
                if (item != null && "function_call".equals(str(item, "type"))) {
                    StreamAccumulator.ToolCallBuilder b = acc.toolCallAt(intOf(chunk, "output_index"));
                    b.id = str(item, "call_id");
                    b.name = str(item, "name");
                    b.arguments.append(str(item, "arguments"));
                }
            }
            case "response.function_call_arguments.delta" ->
                    acc.toolCallAt(intOf(chunk, "output_index")).arguments.append(str(chunk, "delta"));
            case "response.function_call_arguments.done" -> {
                if (chunk.has("arguments")) {
                    replaceArguments(acc.toolCallAt(intOf(chunk, "output_index")), str(chunk, "arguments"));
                }
            }
            // item 只有在 done 里才是完整的(思考带 id 与加密内容,工具调用带最终参数),原样收着
            case "response.output_item.done" -> {
                JsonObject item = obj(chunk, "item");
                if (item != null) {
                    itemDone(chunk, item, acc);
                }
            }
            case "response.completed", "response.incomplete" -> {
                JsonObject response = obj(chunk, "response");
                if (response != null && response.has("usage") && response.get("usage").isJsonObject()) {
                    acc.usage = response.getAsJsonObject("usage");
                }
                acc.finishReason = type.substring(type.lastIndexOf(46) + 1);
                acc.ended = true;
            }
            case "response.failed" -> {
                JsonObject response = obj(chunk, "response");
                JsonObject error = obj(response, "error");
                JsonObject details = obj(response, "incomplete_details");
                acc.finishReason = "failed";
                acc.ended = true;
                acc.failure = error != null
                        ? orElse(str(error, "code"), "unknown") + ": " + orElse(str(error, "message"), "no message")
                        : details != null ? "incomplete: " + str(details, "reason")
                        : "Unknown error (no error details in response)";
            }
            case "error" -> acc.failure = "Error Code " + str(chunk, "code") + ": " + str(chunk, "message");
            default -> { }
        }
        acc.chunkCount++;
    }

    private static void itemDone(JsonObject chunk, JsonObject item, StreamAccumulator acc) {
        switch (str(item, "type")) {
            case "reasoning" -> acc.mergeExtraJson(REASONING_ITEMS, singleton(item.deepCopy()));
            case "message" -> acc.mergeExtraJson(ITEM_IDS, singleton(idOf(item)));
            case "function_call" -> {
                acc.mergeExtraJson(ITEM_IDS, singleton(idOf(item)));
                StreamAccumulator.ToolCallBuilder b = acc.toolCallAt(intOf(chunk, "output_index"));
                b.id = str(item, "call_id");
                b.name = str(item, "name");
                if (item.has("arguments")) {
                    replaceArguments(b, str(item, "arguments"));
                }
            }
            default -> { }
        }
    }

    @Override
    public AssistantTurn finalizeStream(StreamAccumulator acc) {
        acc.requireEnded();
        List<LlmToolCall> calls = new ArrayList<>();
        for (StreamAccumulator.ToolCallBuilder b : acc.toolCalls.values()) {
            if (b.id != null && b.name != null) {
                calls.add(new LlmToolCall(b.id, b.name, b.arguments.toString()));
            }
        }
        return turn(acc.content.toString(), calls, arrayOf(acc.extraJson.get(REASONING_ITEMS)),
                arrayOf(acc.extraJson.get(ITEM_IDS)), acc.reasoning.toString());
    }

    /**
     * 用量字段名与 chat/completions 不同:{@code input_tokens}/{@code output_tokens},缓存在
     * {@code input_tokens_details} 里({@code cached_tokens} 是读,{@code cache_write_tokens} 是写)。
     * {@code input_tokens} 含这两项,四元里的 input 要把它们减掉,口径与其它协议一致,总账不变。
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
        long cacheWrite = 0;
        if (usage.has("input_tokens_details") && usage.get("input_tokens_details").isJsonObject()) {
            JsonObject details = usage.getAsJsonObject("input_tokens_details");
            cacheRead = LlmProvider.usageInt(details, "cached_tokens");
            cacheWrite = LlmProvider.usageInt(details, "cache_write_tokens");
        }
        return new Usage(Math.max(0, total - cacheRead - cacheWrite), output, cacheRead, cacheWrite);
    }

    // ==================== 小工具 ====================

    /** 一条回合:思考 item 与 item id 进 extras(空的不放)。 */
    private static AssistantTurn turn(String text, List<LlmToolCall> calls, JsonArray reasoningItems,
                                      JsonArray ids, String reasoning) {
        JsonObject extras = new JsonObject();
        if (!reasoningItems.isEmpty()) {
            extras.add(REASONING_ITEMS, reasoningItems);
        }
        if (!ids.isEmpty()) {
            extras.add(ITEM_IDS, ids);
        }
        return new AssistantTurn(text, calls, extras, reasoning);
    }

    /** 记下一条 message 或 function_call 的 item id(message 还有 phase)。 */
    private static JsonObject idOf(JsonObject item) {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", str(item, "type"));
        entry.addProperty("id", str(item, "id"));
        if (item.has("call_id")) {
            entry.addProperty("call_id", str(item, "call_id"));
        }
        if (!str(item, "phase").isEmpty()) {
            entry.addProperty("phase", str(item, "phase"));
        }
        return entry;
    }

    /** 记下的 id 里第一条对得上的:message 只看类型,function_call 还要 call_id 相同。 */
    private static JsonObject idEntry(JsonArray ids, String type, String callId) {
        for (JsonElement el : ids) {
            if (el.isJsonObject() && type.equals(str(el.getAsJsonObject(), "type"))
                    && (callId == null || callId.equals(str(el.getAsJsonObject(), "call_id")))) {
                return el.getAsJsonObject();
            }
        }
        return null;
    }

    private static void replaceArguments(StreamAccumulator.ToolCallBuilder b, String arguments) {
        b.arguments.setLength(0);
        b.arguments.append(arguments);
    }

    private static JsonArray singleton(JsonElement el) {
        JsonArray one = new JsonArray();
        one.add(el);
        return one;
    }

    private static JsonArray arrayOf(JsonElement el) {
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
    }

    private static String orElse(String s, String fallback) {
        return s.isEmpty() ? fallback : s;
    }

    /** message 的正文:output_text 的文字,以及模型拒绝时的 refusal 语。 */
    private static String textOf(JsonObject message) {
        StringBuilder sb = new StringBuilder();
        if (message.has("content") && message.get("content").isJsonArray()) {
            for (JsonElement c : message.getAsJsonArray("content")) {
                if (!c.isJsonObject()) {
                    continue;
                }
                JsonObject part = c.getAsJsonObject();
                switch (str(part, "type")) {
                    case "output_text" -> sb.append(str(part, "text"));
                    case "refusal" -> sb.append(str(part, "refusal"));
                    default -> { }
                }
            }
        }
        return sb.toString();
    }

    /** 思考 item 里给人看的那部分,摘要段之间空一行;加密内容不在这里,它随 item 原文一起走 extras。 */
    private static String summaryOf(JsonObject item) {
        StringBuilder sb = new StringBuilder();
        if (item.has("summary") && item.get("summary").isJsonArray()) {
            for (JsonElement s : item.getAsJsonArray("summary")) {
                if (s.isJsonObject()) {
                    if (sb.length() > 0) {
                        sb.append("\n\n");
                    }
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
