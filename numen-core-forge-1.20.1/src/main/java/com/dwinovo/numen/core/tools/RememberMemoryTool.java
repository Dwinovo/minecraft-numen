package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.client.agent.LongTermMemory;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.Map;

/** Client-local tool: persist durable companion memory across sessions. */
public final class RememberMemoryTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(String category, String label, String content,
                        String dimension, Integer x, Integer y, Integer z) {}

    @Override
    public String name() {
        return "remember_memory";
    }

    @Override
    public String description() {
        return "Save a durable memory for this companion. Use for stable owner preferences, named locations, warehouse/storage notes, base coordinates, portal locations, and facts that should survive restarts. Do not store temporary task chatter.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("category", "Memory category.", "location", "storage", "preference", "note")
                .string("label", "Short stable name, e.g. main_base, wood_chest, building_style.")
                .string("content", "The durable fact to remember.")
                .optionalString("dimension", "Optional dimension id, e.g. minecraft:overworld.")
                .optionalInteger("x", "Optional block X coordinate.", -30000000, 30000000)
                .optionalInteger("y", "Optional block Y coordinate.", -2048, 2048)
                .optionalInteger("z", "Optional block Z coordinate.", -30000000, 30000000)
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        try {
            Args a = GSON.fromJson(call.rawArgs(), Args.class);
            LongTermMemory.Entry e = memory(call).remember(a.category(), a.label(), a.content(),
                    a.dimension(), a.x(), a.y(), a.z());
            call.complete(TaskResult.ok("remembered " + e.category().id() + " memory: " + e.label(),
                    Map.of("category", e.category().id(), "label", e.label())).toJson());
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail(ex.getMessage()).toJson());
        }
    }

    static LongTermMemory memory(ToolCall call) {
        Path root = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("numen").resolve("memory");
        return LongTermMemory.forEntity(root, call.ctx().entityUuid());
    }
}
