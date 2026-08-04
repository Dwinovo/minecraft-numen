package com.dwinovo.numen.client.agent.goal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Pure parser for the {@code todowrite} result JSON used by the goal card. */
public final class GoalTodoHarvest {

    private GoalTodoHarvest() {}

    public static Optional<List<GoalTodo>> parse(String resultJson, long nowMs) {
        if (resultJson == null || resultJson.isBlank()) return Optional.empty();
        try {
            JsonObject root = JsonParser.parseString(resultJson).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) {
                return Optional.empty();
            }
            if (!root.has("todos") || !root.get("todos").isJsonArray()) {
                return Optional.empty();
            }
            List<GoalTodo> out = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray("todos")) {
                if (!el.isJsonObject()) continue;
                JsonObject item = el.getAsJsonObject();
                String content = item.has("content") && !item.get("content").isJsonNull()
                        ? item.get("content").getAsString() : "";
                String status = item.has("status") && !item.get("status").isJsonNull()
                        ? item.get("status").getAsString() : "pending";
                out.add(GoalTodo.of(content, status, nowMs));
            }
            return Optional.of(out);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
