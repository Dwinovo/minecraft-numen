package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.core.blueprint.BlueprintStore;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** List the blueprint files available for {@code blueprint_build}. */
public final class BlueprintListTool implements NumenTool {

    @Override
    public String name() {
        return "blueprint_list";
    }

    @Override
    public String description() {
        return "List the structure blueprints in config/numen/blueprints (.nbt from structure blocks, or .snbt "
                + "text) with their sizes. Call this before blueprint_build to see what can be built and how much "
                + "room each one needs.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", Map.of());
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : BlueprintStore.list(self.getServer())) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            try {
                Vec3i size = BlueprintStore.peekSize(self.getServer(), name);
                entry.put("size", size.getX() + "x" + size.getY() + "x" + size.getZ());
            } catch (Exception e) {
                entry.put("size", "unreadable: " + e.getMessage());
            }
            out.add(entry);
        }
        String message = out.isEmpty()
                ? "no blueprints yet; put .nbt or .snbt structure files into config/numen/blueprints"
                : out.size() + " blueprint(s) available";
        reply.accept(TaskResult.ok(message, Map.of("blueprints", out)).toJson());
    }
}
