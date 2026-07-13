package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.client.agent.LongTermMemory;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;

/** Client-local tool: query durable companion memory. */
public final class RecallMemoryTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(String query, String category, Integer limit) {}

    @Override
    public String name() {
        return "recall_memory";
    }

    @Override
    public String description() {
        return "Search this companion's durable memory for named locations, storage notes, owner preferences, and long-lived facts. Use before planning around bases, warehouses, named places, or known preferences.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("query", "Text to search in label/content/dimension. Empty returns recent memories.")
                .optionalEnum("category", "Optional category filter.", "location", "storage", "preference", "note")
                .optionalInteger("limit", "Maximum results.", 1, 32)
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        try {
            Args a = GSON.fromJson(call.rawArgs(), Args.class);
            int limit = a.limit() == null ? 8 : a.limit();
            JsonArray arr = new JsonArray();
            for (LongTermMemory.Entry e : RememberMemoryTool.memory(call).search(a.query(), a.category(), limit)) {
                JsonObject o = new JsonObject();
                o.addProperty("category", e.category().id());
                o.addProperty("label", e.label());
                o.addProperty("content", e.content());
                if (e.dimension() != null) o.addProperty("dimension", e.dimension());
                if (e.x() != null) o.addProperty("x", e.x());
                if (e.y() != null) o.addProperty("y", e.y());
                if (e.z() != null) o.addProperty("z", e.z());
                arr.add(o);
            }
            JsonObject root = new JsonObject();
            root.addProperty("success", true);
            root.add("memories", arr);
            call.complete(root.toString());
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail(ex.getMessage()).toJson());
        }
    }
}
