package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.blueprint.Blueprint;
import com.dwinovo.numen.core.blueprint.BlueprintPlanner;
import com.dwinovo.numen.core.blueprint.BlueprintStore;
import com.dwinovo.numen.core.net.TaskResultPayload;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

/** Analyses materials, conflicts and target coordinates without changing the world. */
public final class PlanBlueprintTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private record Args(String name, int x, int y, int z, String rotation) {}

    @Override public String name() { return "plan_blueprint"; }

    @Override
    public String description() {
        return "Preview a saved blueprint at an absolute anchor (the blueprint's local minimum corner). Supports Y-axis rotation 0/90/180/270. Returns dimensions, blocks already correct, blocks to place, same-block state fixes, occupied conflicts, required/available/missing materials, and bounded coordinate previews. It never changes the world. Always run this before build_blueprint.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("name", "Saved blueprint name.")
                .integer("x", "Target anchor X for local 0,0,0.")
                .integer("y", "Target anchor Y for local 0,0,0.")
                .integer("z", "Target anchor Z for local 0,0,0.")
                .optionalEnum("rotation", "Y-axis clockwise rotation; default 0.", "0", "90", "180", "270")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Args a = GSON.fromJson(args, Args.class);
            Blueprint blueprint = BlueprintStore.load(companion.level.getServer(),
                    companion.getOwnerUuid(), a.name());
            BlueprintPlanner.Turn turn = BlueprintPlanner.Turn.parse(a.rotation());
            BlueprintPlanner.Plan plan = BlueprintPlanner.plan(blueprint, companion,
                    new BlockPos(a.x(), a.y(), a.z()), turn);
            JsonObject result = plan.toJson(companion.isCreative());
            String payload = TaskResult.ok(result.toString()).toJson();
            if (payload.length() > TaskResultPayload.MAX_RESULT_JSON_LENGTH) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("blueprint", blueprint.name());
                summary.put("already_correct", plan.alreadyCorrect());
                summary.put("blocks_to_place", plan.placements().size());
                summary.put("state_fixes", plan.stateFixes().size());
                summary.put("conflicts", plan.conflicts().size());
                summary.put("missing_material_types", plan.missing().size());
                summary.put("unplaceable_types", plan.unplaceable().size());
                summary.put("buildable_now", plan.buildable(companion.isCreative()));
                payload = TaskResult.ok("blueprint plan was too large for coordinate previews; use this summary", summary).toJson();
            }
            reply.accept(payload);
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail(ex.getMessage()).toJson());
        }
    }
}
