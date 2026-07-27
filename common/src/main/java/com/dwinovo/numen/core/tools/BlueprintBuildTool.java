package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.ctx;
import static com.dwinovo.numen.task.TaskDispatch.dispatchAsync;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.core.blueprint.BlueprintStore;
import com.dwinovo.numen.core.task.BuildTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Build a whole structure from a blueprint file as one background task. */
public final class BlueprintBuildTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final long MIN_TIMEOUT_TICKS = 2 * 60 * 20;
    private static final long TICKS_PER_BLOCK = 12 * 20;

    private record Args(String file, int x, int y, int z, Integer rotation) {}

    @Override
    public String name() {
        return "blueprint_build";
    }

    @Override
    public String description() {
        return "Build an entire structure from a blueprint file in config/numen/blueprints (see blueprint_list). "
                + "Give the file name (no extension), the anchor `x`,`y`,`z` = the LOWEST corner (min x/y/z) where "
                + "the structure will stand, and an optional clockwise `rotation` of 0/90/180/270 degrees. The task "
                + "walks the site and places every block bottom-up layer by layer, exact block states included "
                + "(stairs facing, log axis, doors, beds). Explicit air cells in the blueprint CLEAR what is there. "
                + "Materials are not consumed yet. Pick a flat, clear area big enough for the blueprint size. "
                + "BACKGROUND: after acceptance, wait for task_finished; never resend while it is running.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("file", Map.of("type", "string",
                "description", "Blueprint name from blueprint_list, without extension."));
        props.put("x", Map.of("type", "integer", "description", "Anchor: minimum X of the placed structure."));
        props.put("y", Map.of("type", "integer", "description", "Anchor: bottom Y (structure floor level)."));
        props.put("z", Map.of("type", "integer", "description", "Anchor: minimum Z of the placed structure."));
        props.put("rotation", Map.of("type", "integer", "enum", java.util.List.of(0, 90, 180, 270),
                "description", "Optional clockwise rotation in degrees, default 0."));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", java.util.List.of("file", "x", "y", "z"));
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        if (a.file() == null || a.file().isBlank()) {
            throw new IllegalArgumentException("file is required; call blueprint_list first");
        }
        int quarters = a.rotation() == null ? 0 : Math.floorMod(a.rotation(), 360) / 90;
        BlueprintStore.Loaded loaded = BlueprintStore.load((ServerLevel) companion.level(), a.file(),
                new BlockPos(a.x(), a.y(), a.z()), quarters);
        if (loaded.targets().isEmpty()) {
            throw new IllegalArgumentException("blueprint " + a.file() + " contains no buildable cells");
        }
        long timeout = Math.max(MIN_TIMEOUT_TICKS, loaded.targets().size() * TICKS_PER_BLOCK);
        dispatchAsync(companion, new BuildTaskRecord(toolCallId,
                ctx(toolCallId, companion).deadline(timeout), loaded.targets(),
                true, 0, false), reply);
    }
}
