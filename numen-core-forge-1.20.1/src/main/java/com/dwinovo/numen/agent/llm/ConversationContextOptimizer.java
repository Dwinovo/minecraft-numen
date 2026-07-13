package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a cheaper wire-context copy without mutating the persisted conversation.
 * Recent and not-yet-seen tool results stay byte-for-byte intact; older bulky
 * observations are reduced to bounded structured evidence.
 */
public final class ConversationContextOptimizer {

    private static final int LARGE_RESULT_CHARS = 1_800;
    private static final int RECENT_TOOL_RESULTS = 4;
    private static final int MAX_COMPACT_RESULT_CHARS = 1_600;
    private static final int MAX_STRING_CHARS = 360;
    private static final int MAX_ARRAY_ITEMS = 3;

    private ConversationContextOptimizer() {}

    public static List<ConvoState.Msg> optimize(List<ConvoState.Msg> history,
                                                 Set<String> previouslySentToolResultIds) {
        if (history == null || history.isEmpty()) return List.of();

        Map<String, String> toolNames = toolNames(history);
        Set<String> keepRecent = recentToolResultIds(history, RECENT_TOOL_RESULTS);
        List<ConvoState.Msg> optimized = new ArrayList<>(history.size());
        for (ConvoState.Msg message : history) {
            if (!(message instanceof ConvoState.Msg.Tool tool)) {
                optimized.add(message);
                continue;
            }

            String name = toolNames.getOrDefault(tool.toolCallId(), "unknown");
            boolean alreadySeen = previouslySentToolResultIds.contains(tool.toolCallId());
            boolean compactSkill = alreadySeen && "load_skill".equals(name);
            boolean compactLarge = alreadySeen
                    && !keepRecent.contains(tool.toolCallId())
                    && tool.content() != null
                    && tool.content().length() > LARGE_RESULT_CHARS;
            if (compactSkill || compactLarge) {
                optimized.add(new ConvoState.Msg.Tool(tool.toolCallId(),
                        compactResult(name, tool.content(), compactSkill)));
            } else {
                optimized.add(message);
            }
        }
        return List.copyOf(optimized);
    }

    public static Set<String> toolResultIds(List<ConvoState.Msg> history) {
        Set<String> ids = new HashSet<>();
        if (history == null) return ids;
        for (ConvoState.Msg message : history) {
            if (message instanceof ConvoState.Msg.Tool tool) ids.add(tool.toolCallId());
        }
        return ids;
    }

    /** Results followed by an assistant turn were necessarily consumed by that request. */
    public static Set<String> consumedToolResultIds(List<ConvoState.Msg> history) {
        Set<String> pending = new HashSet<>();
        Set<String> consumed = new HashSet<>();
        if (history == null) return consumed;
        for (ConvoState.Msg message : history) {
            if (message instanceof ConvoState.Msg.Tool tool) {
                pending.add(tool.toolCallId());
            } else if (message instanceof ConvoState.Msg.Assistant) {
                consumed.addAll(pending);
                pending.clear();
            }
        }
        return consumed;
    }

    private static Map<String, String> toolNames(List<ConvoState.Msg> history) {
        Map<String, String> names = new HashMap<>();
        for (ConvoState.Msg message : history) {
            if (!(message instanceof ConvoState.Msg.Assistant assistant)) continue;
            for (LlmToolCall call : assistant.turn().toolCalls()) names.put(call.id(), call.name());
        }
        return names;
    }

    private static Set<String> recentToolResultIds(List<ConvoState.Msg> history, int count) {
        Set<String> ids = new HashSet<>();
        for (int i = history.size() - 1; i >= 0 && ids.size() < count; i--) {
            if (history.get(i) instanceof ConvoState.Msg.Tool tool) ids.add(tool.toolCallId());
        }
        return ids;
    }

    private static String compactResult(String toolName, String content, boolean loadedSkill) {
        if (loadedSkill) {
            return "{\"success\":true,\"context_compacted\":true,\"tool\":\"load_skill\","
                    + "\"message\":\"The full skill was delivered and consumed earlier. Relevant instructions remain in the system context; call load_skill again only when omitted detail is required.\"}";
        }

        JsonObject root = new JsonObject();
        root.addProperty("context_compacted", true);
        root.addProperty("tool", toolName);
        try {
            JsonElement parsed = JsonParser.parseString(content == null ? "" : content);
            root.add("evidence", reduce(parsed, 0));
        } catch (RuntimeException ex) {
            root.addProperty("evidence", truncate(content, MAX_STRING_CHARS));
        }
        String result = root.toString();
        if (result.length() <= MAX_COMPACT_RESULT_CHARS) return result;
        JsonObject bounded = new JsonObject();
        bounded.addProperty("context_compacted", true);
        bounded.addProperty("tool", toolName);
        bounded.addProperty("evidence_summary", truncate(result, MAX_COMPACT_RESULT_CHARS - 160));
        bounded.addProperty("truncated", true);
        return bounded.toString();
    }

    private static JsonElement reduce(JsonElement value, int depth) {
        if (value == null || value.isJsonNull()) return com.google.gson.JsonNull.INSTANCE;
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isString()) return new JsonPrimitive(truncate(primitive.getAsString(), MAX_STRING_CHARS));
            return primitive.deepCopy();
        }
        if (depth >= 3) return new JsonPrimitive("[detail omitted]");
        if (value.isJsonArray()) {
            JsonArray source = value.getAsJsonArray();
            JsonArray out = new JsonArray();
            for (int i = 0; i < Math.min(source.size(), MAX_ARRAY_ITEMS); i++) {
                out.add(reduce(source.get(i), depth + 1));
            }
            if (source.size() > MAX_ARRAY_ITEMS) {
                JsonObject omitted = new JsonObject();
                omitted.addProperty("omitted_items", source.size() - MAX_ARRAY_ITEMS);
                out.add(omitted);
            }
            return out;
        }

        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            out.add(entry.getKey(), reduce(entry.getValue(), depth + 1));
        }
        return out;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
