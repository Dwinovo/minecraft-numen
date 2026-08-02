package com.dwinovo.numen.agent.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-streaming-call scratchpad: accumulates the partial state of an
 * assistant response as SSE chunks arrive, then gets finalised by the
 * provider into a complete {@link AssistantTurn}.
 *
 * <h2>Why a builder DTO instead of pure functional folding</h2>
 * Tool-call arguments arrive as JSON-string fragments across many chunks
 * (OpenAI's actual behaviour — the model can't emit the complete argument
 * object until it's generated all tokens). Accumulating into mutable
 * {@link StringBuilder}s indexed by {@code tool_calls[].index} is by far
 * the simplest correct implementation. The DTO is per-call and never
 * shared across threads.
 *
 * <h2>What gets stored</h2>
 * <ul>
 *   <li>{@link #content} — concatenation of every {@code delta.content}
 *       fragment</li>
 *   <li>{@link #toolCalls} — partial tool calls indexed by their stream
 *       position (the {@code index} field in each delta tool_call entry)</li>
 *   <li>{@link #extraBuffers} — per-field accumulator for non-standard
 *       string fields (e.g. DeepSeek's {@code reasoning_content}, captured
 *       by {@link OpenAIProvider#captureChunkExtras})</li>
 *   <li>{@link #finishReason} — set by the last meaningful chunk
 *       ({@code "stop"} / {@code "tool_calls"} / {@code "length"} / ...)</li>
 *   <li>{@link #usage} — set by the final usage-bearing chunk when the
 *       request enables {@code stream_options.include_usage:true}</li>
 * </ul>
 *
 * <p>Chunks counted ({@link #chunkCount}) for debug logging — useful to
 * see at a glance how chatty a backend was on a given response.
 */
public final class StreamAccumulator {

    public final StringBuilder content = new StringBuilder();
    public final Map<Integer, ToolCallBuilder> toolCalls = new LinkedHashMap<>();
    public final Map<String, StringBuilder> extraBuffers = new LinkedHashMap<>();

    /** 思考文本增量的累积(展示面;回传照旧走 extras,两不相扰)。 */
    public final StringBuilder reasoning = new StringBuilder();
    /** 思考流锁定的方言字段名——首个非空者胜。有的后端同流同文双字段
     *  (reasoning_content 和 reasoning 内容相同),锁定后只认一家,防重复计入。 */
    public String reasoningField;

    /** 非字符串型的非标字段(如 reasoning_details 数组):数组逐块并入,其他末值胜。 */
    public final Map<String, JsonElement> extraJson = new LinkedHashMap<>();

    public String finishReason;
    public JsonObject usage;

    public int chunkCount;

    /** Look up (or create) the tool-call builder for the given stream index. */
    public ToolCallBuilder toolCallAt(int index) {
        return toolCalls.computeIfAbsent(index, k -> new ToolCallBuilder());
    }

    /** Append a fragment to the named extras buffer (lazy creation). */
    public void appendExtra(String key, String fragment) {
        if (fragment == null || fragment.isEmpty()) return;
        extraBuffers.computeIfAbsent(key, k -> new StringBuilder()).append(fragment);
    }

    /** 并入一个非字符串型的非标字段:数组按元素追加(流式分块到达),其他类型末值胜。 */
    public void mergeExtraJson(String key, JsonElement el) {
        if (el == null || el.isJsonNull()) return;
        if (el.isJsonArray() && extraJson.get(key) instanceof JsonArray existing) {
            existing.addAll(el.getAsJsonArray());
            return;
        }
        extraJson.put(key, el.deepCopy());
    }

    public static final class ToolCallBuilder {
        /** Tool call id from the LLM. Set on first chunk that carries it. */
        public String id;
        /** Tool name. Set on first chunk that carries it. */
        public String name;
        /** Concatenated JSON-string fragments of {@code arguments}. */
        public final StringBuilder arguments = new StringBuilder();
    }
}
