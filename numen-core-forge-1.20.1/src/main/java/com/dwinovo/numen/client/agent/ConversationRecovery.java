package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.LlmToolCall;

import java.util.List;

/** Stateless context-budget and recovery text helpers shared by the agent loop. */
final class ConversationRecovery {
    private static final int ESTIMATED_FIXED_OVERHEAD_TOKENS = 8_000;
    private ConversationRecovery() { }

    static int estimateContextTokens(List<ConvoState.Msg> history) {
        long cjk = 0, ascii = 0;
        for (ConvoState.Msg msg : history) {
            String text;
            if (msg instanceof ConvoState.Msg.User user) text = user.content();
            else if (msg instanceof ConvoState.Msg.Tool tool) text = tool.content();
            else if (msg instanceof ConvoState.Msg.Assistant assistant) {
                StringBuilder value = new StringBuilder(assistant.turn().content() == null ? "" : assistant.turn().content());
                for (LlmToolCall call : assistant.turn().toolCalls()) value.append(call.name()).append(call.arguments());
                text = value.toString();
            } else throw new IllegalStateException("Unexpected conversation message: " + msg);
            if (text == null) continue;
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) > 0x2E7F) cjk++; else ascii++;
        }
        return (int) (cjk + ascii / 4 + history.size() * 8L) + ESTIMATED_FIXED_OVERHEAD_TOKENS;
    }

    static String extractSummary(String raw) {
        if (raw == null) return null;
        int open = raw.indexOf("<summary>");
        if (open >= 0) {
            int bodyStart = open + "<summary>".length();
            int close = raw.indexOf("</summary>", bodyStart);
            String body = close >= 0 ? raw.substring(bodyStart, close) : raw.substring(bodyStart);
            if (!body.isBlank()) return body.strip();
        }
        return raw.replaceFirst("(?s)<analysis>.*?(</analysis>|$)", "").strip();
    }

    static String escapeXml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current != current.getCause()) current = current.getCause();
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }
}
