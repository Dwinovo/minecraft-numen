package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.task.TaskResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lets the model recover a capability omitted by relevance-based tool routing. */
public final class RequestToolGroupTool implements NumenTool {
    @Override public String name() { return "request_tool_group"; }

    @Override public String description() {
        return "Enable an omitted tool group for the next reasoning turn. Use only when the current tool list "
                + "does not contain a capability needed for the owner's task. Groups: world, gathering, building, "
                + "crafting, combat, inventory, creative, knowledge, or all.";
    }

    @Override public Map<String, Object> parameterSchema() {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("type", "string");
        group.put("enum", List.of("world", "gathering", "building", "crafting", "combat",
                "inventory", "creative", "knowledge", "all"));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", Map.of("group", group));
        root.put("required", List.of("group"));
        root.put("additionalProperties", false);
        return root;
    }

    @Override public void invoke(ToolCall call) {
        String group = call.args().has("group") ? call.args().get("group").getAsString() : "";
        if (!ToolRouter.request(call.ctx().entityUuid(), group)) {
            call.complete(TaskResult.fail("unknown tool group: " + group, "unknown_tool_group",
                    Map.of("available_groups", ToolRouter.groupNames())).toJson());
            return;
        }
        call.complete(TaskResult.ok("enabled " + group + " tools for the next turn",
                Map.of("tool_group", group)).toJson());
    }
}
