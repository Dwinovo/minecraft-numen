package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.client.agent.AutonomyMemory;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.Map;

/** Client-local management surface for persistent goals, places and resource reservations. */
public final class AutonomyTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private record Args(String action, String id, String status, String note, String name,
                        String dimension, Integer x, Integer y, Integer z,
                        String item, Integer count, String purpose) { }

    @Override public String name() { return "manage_autonomy"; }

    @Override
    public String description() {
        return "Manage persistent execution state across conversation compaction and game restarts. "
                + "Use checkpoint_goal after verified progress; remember_location for named bases, portals, storage and project anchors; "
                + "reserve_resource before multi-step crafting/building so another goal does not consume it; release reservations when done. "
                + "Use list to inspect all current goals, checkpoints, locations and reservations.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("action", "Operation.", "list", "checkpoint_goal", "remember_location",
                        "forget_location", "reserve_resource", "release_resource")
                .optionalString("id", "Goal id for checkpoint_goal, e.g. goal-1.")
                .optionalEnum("status", "New goal status.", "pending", "in_progress", "blocked", "completed", "cancelled")
                .optionalString("note", "Verified checkpoint or location note.")
                .optionalString("name", "Location name.")
                .optionalString("dimension", "Dimension id for a location.")
                .optionalInteger("x", "Location X.", -30000000, 30000000)
                .optionalInteger("y", "Location Y.", -2048, 2048)
                .optionalInteger("z", "Location Z.", -30000000, 30000000)
                .optionalString("item", "Namespaced item id for a reservation.")
                .optionalInteger("count", "Reserved item count.", 1, 1000000)
                .optionalString("purpose", "Goal or step that owns the reservation.")
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        try {
            Args a = GSON.fromJson(call.rawArgs(), Args.class);
            AutonomyMemory memory = memory(call);
            Object data;
            String message;
            switch (a.action()) {
                case "list" -> {
                    data = Map.of("state_xml", memory.formatXml());
                    message = "persistent autonomy state listed";
                }
                case "checkpoint_goal" -> {
                    require(a.id(), "id"); require(a.status(), "status");
                    data = memory.checkpoint(a.id(), a.status(), a.note());
                    message = "goal checkpoint persisted";
                }
                case "remember_location" -> {
                    require(a.name(), "name"); require(a.dimension(), "dimension");
                    if (a.x() == null || a.y() == null || a.z() == null) throw new IllegalArgumentException("x, y and z are required");
                    data = memory.rememberLocation(a.name(), a.dimension(), a.x(), a.y(), a.z(), a.note());
                    message = "location remembered";
                }
                case "forget_location" -> {
                    require(a.name(), "name");
                    data = Map.of("removed", memory.forgetLocation(a.name()), "name", a.name());
                    message = "location removal checked";
                }
                case "reserve_resource" -> {
                    require(a.item(), "item");
                    if (a.count() == null) throw new IllegalArgumentException("count is required");
                    data = memory.reserve(a.item(), a.count(), a.purpose());
                    message = "resource reserved";
                }
                case "release_resource" -> {
                    require(a.item(), "item");
                    data = Map.of("released", memory.release(a.item()), "item", a.item());
                    message = "resource reservation released";
                }
                default -> throw new IllegalArgumentException("unknown action: " + a.action());
            }
            call.complete(TaskResult.ok(message, Map.of("result", data)).toJson());
        } catch (RuntimeException error) {
            call.complete(TaskResult.fail(error.getMessage()).toJson());
        }
    }

    static AutonomyMemory memory(ToolCall call) {
        Path root = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("numen").resolve("memory");
        return AutonomyMemory.forEntity(root, call.ctx().entityUuid());
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
