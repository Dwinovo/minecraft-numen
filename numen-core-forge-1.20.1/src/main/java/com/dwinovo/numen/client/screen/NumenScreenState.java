package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure transcript/status calculations kept outside the stateful Minecraft screen. */
final class NumenScreenState {
    private NumenScreenState() { }

    static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static List<String> joinReasoningValues(List<String> detected) {
        List<String> out = new ArrayList<>();
        out.add("auto");
        out.add("none");
        for (String value : detected) if (!out.contains(value)) out.add(value);
        return List.copyOf(out);
    }

    static Set<String> completedToolIds(List<ConvoState.Msg> messages) {
        Set<String> result = new HashSet<>();
        for (ConvoState.Msg message : messages) {
            if (message instanceof ConvoState.Msg.Tool tool) result.add(tool.toolCallId());
        }
        return result;
    }

    static Set<String> failedToolIds(List<ConvoState.Msg> messages) {
        Set<String> result = new HashSet<>();
        for (ConvoState.Msg message : messages) {
            if (message instanceof ConvoState.Msg.Tool tool && looksFailed(tool.content())) {
                result.add(tool.toolCallId());
            }
        }
        return result;
    }

    static List<LlmToolCall> activeToolCalls(List<ConvoState.Msg> messages) {
        Set<String> done = completedToolIds(messages);
        List<LlmToolCall> result = new ArrayList<>();
        for (ConvoState.Msg message : messages) {
            if (message instanceof ConvoState.Msg.Assistant assistant) {
                for (LlmToolCall call : assistant.turn().toolCalls()) {
                    if (!done.contains(call.id())) result.add(call);
                }
            }
        }
        return result;
    }

    static JsonArray latestPlan(List<ConvoState.Msg> messages) {
        JsonArray latest = null;
        for (ConvoState.Msg message : messages) {
            if (!(message instanceof ConvoState.Msg.Assistant assistant)) continue;
            for (LlmToolCall call : assistant.turn().toolCalls()) {
                if (!"todowrite".equals(call.name())) continue;
                try {
                    JsonObject args = JsonParser.parseString(call.arguments()).getAsJsonObject();
                    if (args.has("todos") && args.get("todos").isJsonArray()) {
                        latest = args.getAsJsonArray("todos");
                    }
                } catch (RuntimeException ignored) {
                    // Keep the most recent valid plan.
                }
            }
        }
        return latest;
    }

    static List<String> recentFailures(List<ConvoState.Msg> messages, int max) {
        List<String> result = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0 && result.size() < max; i--) {
            if (!(messages.get(i) instanceof ConvoState.Msg.Tool tool) || !looksFailed(tool.content())) continue;
            result.add(failureSummary(tool.content()));
        }
        return result;
    }

    static boolean looksFailed(String content) {
        if (content == null) return false;
        String compact = content.replaceAll("\\s+", "");
        return compact.contains("\"success\":false") || compact.startsWith("ERROR")
                || compact.contains("\"error\"");
    }

    static String failureSummary(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String message = jsonString(root, "message");
            if (root.has("data") && root.get("data").isJsonObject()) {
                String code = jsonString(root.getAsJsonObject("data"), "failure_code");
                if (!code.isBlank()) message = "[" + code + "] " + message;
            }
            return message.isBlank() ? json : message;
        } catch (RuntimeException ignored) {
            return json.length() > 120 ? json.substring(0, 120) + "…" : json;
        }
    }

    static String jsonString(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    static String oneLine(String text, int max) {
        if (text == null) return "";
        String value = text.replaceAll("\\s+", " ").trim();
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    static String formatEta(int seconds) {
        if (seconds < 60) return seconds + "秒";
        int minutes = seconds / 60;
        return minutes < 60 ? minutes + "分" : (minutes / 60) + "时" + (minutes % 60) + "分";
    }

    static String formatTokens(int tokens) {
        if (tokens >= 1_000_000 && tokens % 1_000_000 == 0) return (tokens / 1_000_000) + "M";
        if (tokens >= 1_000 && tokens % 1_000 == 0) return (tokens / 1_000) + "K";
        return Integer.toString(tokens);
    }
}
