package com.dwinovo.numen.api.agent.memory;

import com.dwinovo.numen.api.agent.llm.ConvoState;
import com.dwinovo.numen.api.agent.llm.ToolOutcome;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把要摘要的那段历史写成一段文字(照 pi 的 {@code serializeConversation}):摘要请求是一条 user 消息,历史当材料放在里面,
 * 模型就不会把它当成要接着往下说的对话。
 *
 * <p>只写看得见的:主人和她说的话、她的工具调用(名字加参数)、工具结果。<b>思考不写</b>——服务商返回的只是真实思考的摘要,
 * 回传的思考也常不在模型实际的上下文里(换了模型会被脱掉),写进来会让摘要请求比被摘要的上下文还大;发生过什么由可见的话、
 * 调用和结果记着。工具结果截到 {@link #TOOL_RESULT_MAX_CHARS} 字:摘要用不着全文。
 */
final class ConversationText {

    private ConversationText() {}

    /** 一条工具结果写进摘要请求的字数上限。 */
    static final int TOOL_RESULT_MAX_CHARS = 2000;

    static String of(List<ConvoState.Msg> messages) {
        List<String> parts = new ArrayList<>();
        for (ConvoState.Msg msg : messages) {
            switch (msg) {
                case ConvoState.Msg.User u -> {
                    if (u.content() != null && !u.content().isEmpty()) {
                        parts.add("[User]: " + u.content());
                    }
                }
                case ConvoState.Msg.Assistant a -> {
                    if (!a.turn().content().isEmpty()) {
                        parts.add("[Assistant]: " + a.turn().content());
                    }
                    if (a.turn().hasToolCalls()) {
                        parts.add("[Assistant tool calls]: "
                                + String.join("; ", a.turn().toolCalls().stream().map(ConversationText::call).toList()));
                    }
                }
                case ConvoState.Msg.Tool t -> {
                    String text = ToolOutcome.modelText(t.content());
                    if (text != null && !text.isEmpty()) {
                        parts.add("[Tool result]: " + truncate(text));
                    }
                }
                // 切断点:这一轮被打断或没连上。下一次请求里模型读得到,摘要也得留着
                case ConvoState.Msg.Halt h -> parts.add("[Interrupted]: " + h.reason());
            }
        }
        return String.join("\n\n", parts);
    }

    /** {@code 名字(参数=值, …)},值按 JSON 写。 */
    private static String call(LlmToolCall call) {
        JsonObject args = JsonParser.parseString(call.arguments()).getAsJsonObject();
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, com.google.gson.JsonElement> e : args.entrySet()) {
            pairs.add(e.getKey() + "=" + e.getValue());
        }
        return call.name() + "(" + String.join(", ", pairs) + ")";
    }

    private static String truncate(String text) {
        if (text.length() <= TOOL_RESULT_MAX_CHARS) {
            return text;
        }
        return text.substring(0, TOOL_RESULT_MAX_CHARS) + "\n\n[... " + (text.length() - TOOL_RESULT_MAX_CHARS)
                + " more characters truncated]";
    }
}
