package com.dwinovo.numen.agent.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 工具结果的成败判据——单一真源。展示层(聊天流的工具 chip、聊天框字幕)
 * 一律问这里,不各自猜字符串。
 *
 * <h2>判据顺序</h2>
 * <ol>
 *   <li>结果是 JSON 且带布尔 {@code success} → 以它为准。这是
 *       {@code TaskResult} 信封的形状,是唯一权威的失败声明。</li>
 *   <li>以 {@code ERROR} 开头 → 失败。工具抛异常时由派发层兜底写成这个形状。</li>
 *   <li>其余一律不算失败。</li>
 * </ol>
 *
 * <p>第三条是刻意的:多数工具返回的是数据(观察结果/清单/坐标),它们没有
 * "失败"这个概念,拿关键词去猜只会误判——旧实现里 {@code contains("\"error\"")}
 * 让任何正文提到 error 的正常结果都标红,就是这么来的。工具若确实失败却没在
 * 结果里声明,那是工具侧该补 {@code success} 字段,不是展示层猜得更狠。
 */
public final class ToolOutcome {

    private ToolOutcome() {}

    /** 一条成功结果 {@code {"success":true,"message":…}}:只管大脑自己的事的工具(技能、计划、札记)回的就是这个形状。 */
    public static String success(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("message", message);
        return result.toString();
    }

    /** 一条失败结果 {@code {"success":false,"message":…}}:不是工具自己回的、由循环替它写下的结果都是这个形状。 */
    public static String failure(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("message", message);
        return result.toString();
    }

    /**
     * 交给模型的那份文字——规则只此一处:把工具结果交给模型的出口(对话历史变成请求时的 {@code ProtocolView}、外接智能体拿到的工具结果)
     * 都问这里。结果是信封({@code success} 与 {@code message})就是它的 {@code message},成败已在文字第一行;不是信封的(技能正文、远端 MCP
     * 工具的文字)原样。历史里存的就是信封(界面据 {@code success} 画成败),模型读到的只有它的文字;程序的结构化结局不在信封里,在 {@code Program.Outcome}。
     */
    public static String modelText(String result) {
        JsonObject envelope = envelope(result);
        return envelope == null ? result : envelope.get("message").getAsString();
    }

    /** 结果是 {@code {"success": 布尔, "message": 字符串, …}} 的信封就是它;不是是 null。 */
    private static JsonObject envelope(String result) {
        if (result == null || !result.stripLeading().startsWith("{")) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(result);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject obj = parsed.getAsJsonObject();
            JsonElement success = obj.get("success");
            JsonElement message = obj.get("message");
            boolean envelope = success != null && success.isJsonPrimitive() && success.getAsJsonPrimitive().isBoolean()
                    && message != null && message.isJsonPrimitive() && message.getAsJsonPrimitive().isString();
            return envelope ? obj : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    /** 这条工具结果是否宣告了失败。 */
    public static boolean failed(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String trimmed = content.stripLeading();
        if (trimmed.startsWith("ERROR")) {
            return true;
        }
        if (!trimmed.startsWith("{")) {
            return false;
        }
        try {
            JsonElement parsed = JsonParser.parseString(trimmed);
            if (!parsed.isJsonObject()) {
                return false;
            }
            JsonObject obj = parsed.getAsJsonObject();
            JsonElement success = obj.get("success");
            return success != null && success.isJsonPrimitive()
                    && success.getAsJsonPrimitive().isBoolean()
                    && !success.getAsBoolean();
        } catch (RuntimeException notJson) {
            return false;   // 半截 JSON / 非法转义:不是失败声明,按正常结果对待
        }
    }
}
