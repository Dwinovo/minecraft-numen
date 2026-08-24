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
