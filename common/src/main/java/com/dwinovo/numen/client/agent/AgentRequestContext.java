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
     * Attach live state to the final request message. Reusing the existing role avoids creating an
     * artificial user turn after a tool result. The source list and messages remain untouched.
     */
    static List<ConvoState.Msg> attach(List<ConvoState.Msg> messages, String runtimeXml) {
        List<ConvoState.Msg> cleaned = withoutLegacyCurrentTask(messages);
        if (runtimeXml == null || runtimeXml.isBlank()) return cleaned;
        if (cleaned.isEmpty()) return List.of(new ConvoState.Msg.User(runtimeXml));

        List<ConvoState.Msg> out = new ArrayList<>(cleaned);
        int last = out.size() - 1;
        ConvoState.Msg tail = out.get(last);
        String suffix = "\n\n" + runtimeXml;
        switch (tail) {
            case ConvoState.Msg.User user ->
                    out.set(last, new ConvoState.Msg.User(user.content() + suffix));
            case ConvoState.Msg.Tool tool ->
                    out.set(last, new ConvoState.Msg.Tool(tool.toolCallId(), tool.content() + suffix));
            case ConvoState.Msg.Assistant assistant -> {
                if (!assistant.turn().hasToolCalls()) {
                    out.add(new ConvoState.Msg.User(runtimeXml));
                }
            }
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
