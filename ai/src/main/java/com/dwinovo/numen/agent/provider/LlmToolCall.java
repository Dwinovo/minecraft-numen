package com.dwinovo.numen.agent.provider;

import com.dwinovo.numen.ai.AiLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Single tool invocation extracted from an LLM response. Carries enough to
 * round-trip back into the next request (the {@code id} must be echoed
 * verbatim in the {@code tool_call_id} of the role:tool reply, or backends
 * 400).
 *
 * <h2>arguments 一定是合法的 JSON 对象文本</h2>
 * 这是本类型的<b>契约</b>,在构造时立起来,不由调用方各自把关——任何一处漏了,后果都不是
 * 一次报错而是整个会话报废:
 *
 * <ol>
 *   <li>这个字符串进对话历史,而历史每轮全量回传。一句截断的 {@code {"x": } 进去了,
 *       之后<b>每一次</b>请求都带着它,上游每次都 400;</li>
 *   <li>400 不在重试白名单里(那是给限流和 5xx 的),重试用的又是同一份历史;</li>
 *   <li>历史会落盘,重启原样读回。</li>
 * </ol>
 *
 * 所以模型吐出解析不了的东西时,在这里就归一成空对象:工具随后会报"缺少必填参数",
 * 模型看得懂、会自己重来,而历史始终是干净的。
 *
 * <p>合法的那份<b>原样保留</b>,不重新序列化——避免 replay 时的往返差异,也匹配 OpenAI
 * 线上那个字段本身就是字符串。
 *
 * @param id           identifier the LLM minted for this tool_call; must be
 *                     echoed in the matching tool result
 * @param name         tool name (matches a registered {@code NumenTool.name()})
 * @param arguments    JSON 对象文本;构造时保证合法
 */
public record LlmToolCall(String id, String name, String arguments) {

    /** 没有参数时的规范写法。 */
    public static final String NO_ARGS = "{}";

    public LlmToolCall {
        arguments = asObjectText(arguments);
    }

    /**
     * 归一成合法的 JSON 对象文本。解析不了、或者解析出来不是对象(模型偶尔吐数组或裸值),
     * 一律换成 {@link #NO_ARGS} 并留一行日志——那串原文对排查有用,但绝不能进历史。
     */
    private static String asObjectText(String raw) {
        String trimmed = raw == null ? "" : raw.strip();
        if (trimmed.isEmpty()) {
            return NO_ARGS;
        }
        try {
            if (JsonParser.parseString(trimmed).isJsonObject()) {
                return raw;
            }
        } catch (RuntimeException notJson) {
            // 落到下面统一处理
        }
        AiLog.LOG.warn("[numen-llm] 工具参数不是合法 JSON 对象,按无参处理: {}", brief(trimmed));
        return NO_ARGS;
    }

    private static String brief(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…(" + s.length() + " chars)";
    }

    /** Build the OpenAI-shape JSON for this tool call (used inside the assistant message). */
    public JsonObject toOpenAIJson() {
        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        fn.addProperty("arguments", arguments);
        JsonObject root = new JsonObject();
        root.addProperty("id", id);
        root.addProperty("type", "function");
        root.add("function", fn);
        return root;
    }

    /** Parse an OpenAI-shape tool_call object back into this DTO. */
    public static LlmToolCall fromOpenAIJson(JsonObject obj) {
        String id = stringOrEmpty(obj.get("id"));
        JsonObject fn = obj.has("function") && obj.get("function").isJsonObject()
                ? obj.getAsJsonObject("function") : new JsonObject();
        String name = stringOrEmpty(fn.get("name"));
        String args = stringOrEmpty(fn.get("arguments"));
        return new LlmToolCall(id, name, args);
    }

    private static String stringOrEmpty(JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString();
        return el.toString();
    }
}
