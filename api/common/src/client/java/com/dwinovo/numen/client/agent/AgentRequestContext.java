package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.llm.ConvoState;

import java.util.ArrayList;
import java.util.List;

/** Adds ephemeral runtime state to one model request without persisting it in conversation history. */
final class AgentRequestContext {

    private static final String CURRENT_TASK_OPEN = "<current_task>";
    private static final String CURRENT_TASK_CLOSE = "</current_task>";

    private AgentRequestContext() {}

    /**
     * 把运行期状态挂进这一次请求。<b>永远落在 role=user 的消息里</b>:跟主人的话同一条
     * (尾巴就是 user 时并进去),否则新起一条。源列表与其中的消息一个字不动。
     *
     * <h2>为什么必须是 user</h2>
     * "这一轮发出去的 user 消息"得是一个<b>完整</b>的答案。把它挂到工具结果上,
     * 这句话就有了例外——而例外只能靠"再去别处看一眼"补,面板、日志、排查的人
     * 各补各的。顺带工具结果也就不再是服务端原样交回的那串,读日志时会以为
     * 工具自己吐了个 {@code <runtime_state>}。
     *
     * <p>唯一挂不上的时刻:assistant 的 {@code tool_calls} 还没等到它的结果——那中间
     * 插任何东西上游都会 400。那一刻宁可不带,不找地方硬塞。
     */
    static List<ConvoState.Msg> attach(List<ConvoState.Msg> messages, String runtimeXml) {
        List<ConvoState.Msg> cleaned = withoutLegacyCurrentTask(messages);
        if (runtimeXml == null || runtimeXml.isBlank()) return cleaned;
        if (cleaned.isEmpty()) return List.of(new ConvoState.Msg.User(runtimeXml));

        List<ConvoState.Msg> out = new ArrayList<>(cleaned);
        int last = out.size() - 1;
        switch (out.get(last)) {
            case ConvoState.Msg.User user ->
                    out.set(last, new ConvoState.Msg.User(user.content() + "\n\n" + runtimeXml));
            case ConvoState.Msg.Assistant assistant -> {
                if (!assistant.turn().hasToolCalls()) {
                    out.add(new ConvoState.Msg.User(runtimeXml));
                }
            }
            case ConvoState.Msg.Tool ignored -> out.add(new ConvoState.Msg.User(runtimeXml));
        }
        return List.copyOf(out);
    }

    /** Remove generated current-task blocks persisted by older builds, request-locally only. */
    private static List<ConvoState.Msg> withoutLegacyCurrentTask(List<ConvoState.Msg> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        List<ConvoState.Msg> out = null;
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof ConvoState.Msg.User user)) continue;
            String content = user.content();
            int open = content.indexOf(CURRENT_TASK_OPEN);
            int query = content.indexOf("<query>");
            // Generated state preceded owner input. Never strip similarly named text inside <query>.
            if (open < 0 || (query >= 0 && open > query)) continue;
            int close = content.indexOf(CURRENT_TASK_CLOSE,
                    open + CURRENT_TASK_OPEN.length());
            if (close < 0) continue;
            int end = close + CURRENT_TASK_CLOSE.length();
            if (end < content.length() && content.charAt(end) == '\r') end++;
            if (end < content.length() && content.charAt(end) == '\n') end++;
            String cleaned = (content.substring(0, open) + content.substring(end)).strip();
            if (out == null) out = new ArrayList<>(messages);
            out.set(i, new ConvoState.Msg.User(cleaned));
        }
        return out == null ? List.copyOf(messages) : List.copyOf(out);
    }
}
