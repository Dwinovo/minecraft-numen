package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.ctx;
import static com.dwinovo.numen.task.TaskDispatch.dispatchAsync;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.core.blueprint.BlueprintStore;
import com.dwinovo.numen.core.task.BuildTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 蓝图工具:列出结构文件,或按图整幢施工——一个入口两个动作。 */
public final class BlueprintTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final long MIN_TIMEOUT_TICKS = 2 * 60 * 20;
    private static final long TICKS_PER_BLOCK = 12 * 20;

    private record Args(String action, String file, Integer x, Integer y, Integer z, Integer rotation) {}

    @Override
    public String name() {
        return "blueprint";
    }

    @Override
    public String description() {
        return "Work with structure blueprint files in the server's schematics/ folder — the conventional "
                + "location, so anything the player already downloaded is available without moving files. "
                + "Formats: .litematic, .schem, .nbt (structure block export), .snbt (text). "
                + "action=list shows available blueprints with sizes — call this first. "
                + "action=build constructs one whole file: give `file` (name without extension), anchor `x`,`y`,`z` "
                + "= the LOWEST corner (min x/y/z) where the structure will stand, and optional clockwise "
                + "`rotation` 0/90/180/270. She walks to the site once, then works inside it, placing cells in "
                + "batches from the ground up with exact states (stairs facing, doors, beds); explicit air cells "
                + "CLEAR terrain; liquids are skipped. MATERIALS: creative builds freely; in survival every cell "
                + "consumes 1 matching item and the job is refused up front with an itemized shortfall — treat "
                + "that as an invitation to gather together, not as an error. "
                + "Pick a flat, clear area big enough for the size from list. "
                + "For freeform construction use the build tool instead. BACKGROUND (build action): wait for "
                + "task_finished; never resend while running.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", Map.of("type", "string", "enum", List.of("list", "build"),
                "description", "list = show blueprints; build = construct one."));
        props.put("file", Map.of("type", "string",
                "description", "build only: blueprint name from list, no extension."));
        props.put("x", Map.of("type", "integer", "description", "build only: anchor minimum X."));
        props.put("y", Map.of("type", "integer", "description", "build only: anchor bottom Y (floor level)."));
        props.put("z", Map.of("type", "integer", "description", "build only: anchor minimum Z."));
        props.put("rotation", Map.of("type", "integer", "enum", List.of(0, 90, 180, 270),
                "description", "build only: optional clockwise rotation, default 0."));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("action"));
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        if ("list".equals(a.action())) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (String name : BlueprintStore.list(companion.getServer())) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", name);
                try {
                    Vec3i size = BlueprintStore.peekSize(companion.getServer(), name);
                    entry.put("size", size.getX() + "x" + size.getY() + "x" + size.getZ());
                } catch (Exception e) {
                    entry.put("size", "unreadable: " + e.getMessage());
                }
                out.add(entry);
            }
            String message = out.isEmpty()
                    ? "no blueprints yet; drop .litematic / .schem / .nbt files into the schematics folder"
                    : out.size() + " blueprint(s) available";
            reply.accept(TaskResult.ok(message, Map.of("blueprints", out)).toJson());
            return;
        }
        if (!"build".equals(a.action())) {
            throw new IllegalArgumentException("action must be list or build");
        }
        if (a.file() == null || a.file().isBlank()) {
            throw new IllegalArgumentException("file is required; use action=list first");
        }
        if (a.x() == null || a.y() == null || a.z() == null) {
            throw new IllegalArgumentException("build needs anchor x, y, z (minimum corner)");
        }
        int quarters = a.rotation() == null ? 0 : Math.floorMod(a.rotation(), 360) / 90;
        BlueprintStore.Loaded loaded = BlueprintStore.load((ServerLevel) companion.level(), a.file(),
                new BlockPos(a.x(), a.y(), a.z()), quarters);
        if (loaded.targets().isEmpty()) {
            throw new IllegalArgumentException("blueprint " + a.file() + " contains no buildable cells");
        }
        long timeout = Math.max(MIN_TIMEOUT_TICKS, loaded.targets().size() * TICKS_PER_BLOCK);
        // 材料记账随能力画像(同 build 工具):免耗材想建就建,否则消耗并预检报缺。
        boolean consume = !com.dwinovo.numen.core.task.WorkProfile.of(companion).freeMaterials();
        dispatchAsync(companion, new BuildTaskRecord(toolCallId,
                ctx(toolCallId, companion).deadline(timeout), loaded.targets(),
                true, consume), reply);
    }
}
